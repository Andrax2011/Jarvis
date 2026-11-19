package com.jarvis.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public class JarvisService extends Service implements TextToSpeech.OnInitListener {

    public interface LogListener {
        void onLog(String line);
    }

    public static volatile boolean isRunning = false;
    private static volatile LogListener logListener = null;
    private static final ArrayList<String> logHistory = new ArrayList<String>();

    public static void setLogListener(LogListener listener) {
        logListener = listener;
    }

    public static String getLogsText() {
        StringBuilder sb = new StringBuilder();
        synchronized (logHistory) {
            for (String s : logHistory) sb.append(s).append("\n");
        }
        return sb.toString();
    }

    private static void addLog(String text) {
        synchronized (logHistory) {
            logHistory.add(text);
            if (logHistory.size() > 30) logHistory.remove(0);
        }
        LogListener l = logListener;
        if (l != null) l.onLog(text);
    }

    private static final String CHANNEL_ID = "jarvis_v2_channel";
    private static final int NOTIF_ID = 202;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private CommandManager commandManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> speechQueue = new ArrayDeque<String>();

    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean awaitingCommand = false;
    private boolean ttsReady = false;

    @Override
    public void onCreate() {
        super.onCreate();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            addLog("Ошибка: нет разрешения на микрофон.");
            stopSelf();
            return;
        }
        isRunning = true;
        commandManager = new CommandManager(this);
        createNotificationChannel();
        startForegroundSafe();
        tts = new TextToSpeech(this, this);
        initSpeechRecognizer();
        addLog("Jarvis AI v2 запущен.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("manual_command")) {
            String manual = intent.getStringExtra("manual_command");
            if (manual != null && !manual.trim().isEmpty()) {
                addLog("Команда: " + manual);
                commandManager.execute(manual, this::speak);
            }
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Jarvis AI", NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private void startForegroundSafe() {
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Jarvis AI активен")
                .setContentText("Слушаю ключевое слово...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int res = tts.setLanguage(new Locale("ru", "RU"));
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                addLog("Предупреждение: русский пакет TTS не установлен.");
            }
            ttsReady = true;
            applyVoiceSettings();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { isSpeaking = true; }
                @Override public void onDone(String id) { mainHandler.post(() -> processNextSpeechOrListen()); }
                @Override public void onError(String id) { mainHandler.post(() -> processNextSpeechOrListen()); }
            });
            speak("Системы активны. Жду команду.");
        } else {
            addLog("TTS недоступен, работаю без голоса.");
            startListeningDelayed(500);
        }
    }

    private void applyVoiceSettings() {
        if (tts == null) return;
        SharedPreferences prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
        tts.setPitch(prefs.getFloat("voice_pitch", 0.85f));
        tts.setSpeechRate(prefs.getFloat("voice_speed", 1.05f));
    }

    private void speak(String text) {
        addLog("Jarvis: " + text);
        SharedPreferences prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("voice_enabled", true) || !ttsReady || tts == null) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), text, Toast.LENGTH_SHORT).show());
            startListeningDelayed(350);
            return;
        }
        mainHandler.post(() -> {
            applyVoiceSettings();
            speechQueue.addLast(text);
            if (!isSpeaking) {
                stopListening();
                speakNext();
            }
        });
    }

    private void speakNext() {
        String next = speechQueue.pollFirst();
        if (next != null) {
            isSpeaking = true;
            tts.speak(next, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString());
            mainHandler.postDelayed(() -> {
                if (isSpeaking && tts != null && !tts.isSpeaking()) processNextSpeechOrListen();
            }, 9000);
        } else {
            isSpeaking = false;
            startListeningDelayed(250);
        }
    }

    private void processNextSpeechOrListen() {
        if (!speechQueue.isEmpty()) speakNext();
        else {
            isSpeaking = false;
            startListeningDelayed(250);
        }
    }

    private void initSpeechRecognizer() {
        mainHandler.post(() -> {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                addLog("Ошибка: нет службы распознавания речи (нужно приложение Google).");
                return;
            }
            if (speechRecognizer != null) speechRecognizer.destroy();
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) { isListening = true; }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float v) {}
                @Override public void onBufferReceived(byte[] b) {}
                @Override public void onEndOfSpeech() { isListening = false; }

                @Override
                public void onError(int error) {
                    isListening = false;
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        addLog("Нет прав на микрофон.");
                        stopSelf();
                        return;
                    }
                    if (isRunning && !isSpeaking) {
                        startListeningDelayed(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1200 : 550);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    handleHeard(list != null && !list.isEmpty() ? list.get(0) : "");
                }

                @Override public void onPartialResults(Bundle p) {}
                @Override public void onEvent(int t, Bundle p) {}
            });
        });
    }

    private void handleHeard(String raw) {
        String heard = raw.toLowerCase(new Locale("ru")).trim();
        if (heard.isEmpty()) {
            startListeningDelayed(300);
            return;
        }
        addLog("Вы: " + heard);

        SharedPreferences prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
        boolean requireWake = prefs.getBoolean("require_wake_word", true);
        String wakeWord = prefs.getString("wake_word", "джарвис").toLowerCase(new Locale("ru")).trim();
        if (wakeWord.isEmpty()) wakeWord = "джарвис";

        if (!requireWake) {
            commandManager.execute(heard, this::speak);
            return;
        }

        if (heard.contains(wakeWord)) {
            int idx = heard.indexOf(wakeWord) + wakeWord.length();
            String afterWake = heard.substring(idx).trim();
            if (!afterWake.isEmpty()) {
                awaitingCommand = false;
                commandManager.execute(afterWake, this::speak);
            } else {
                awaitingCommand = true;
                speak("Слушаю.");
            }
        } else if (awaitingCommand) {
            awaitingCommand = false;
            commandManager.execute(heard, this::speak);
        } else {
            startListeningDelayed(300);
        }
    }

    private void startListeningDelayed(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (!isRunning || isSpeaking || isListening || speechRecognizer == null) return;
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            try {
                speechRecognizer.startListening(i);
            } catch (Exception e) {
                initSpeechRecognizer();
                startListeningDelayed(1200);
            }
        }, delayMs);
    }

    private void stopListening() {
        mainHandler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.stopListening();
                    speechRecognizer.cancel();
                }
            } catch (Exception ignored) {}
            isListening = false;
        });
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        addLog("Jarvis AI остановлен.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
                                }
