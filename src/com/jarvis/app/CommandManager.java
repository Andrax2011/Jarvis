package com.jarvis.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommandManager {

    public interface StatusCallback {
        void onStatus(String message);
    }

    private final Context context;
    private final SharedPreferences prefs;

    public CommandManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);
    }

    public void execute(String rawCommand, StatusCallback cb) {
        if (rawCommand == null) return;
        String cmd = rawCommand.toLowerCase(new Locale("ru")).trim();
        if (cmd.isEmpty()) return;

        // 1. Пользовательские команды
        Set<String> customCmds = prefs.getStringSet("custom_commands", new HashSet<String>());
        for (String entry : customCmds) {
            String[] parts = entry.split("\\|\\|");
            if (parts.length == 2) {
                String phrase = parts[0].toLowerCase(new Locale("ru")).trim();
                String target = parts[1].trim();
                if (!phrase.isEmpty() && cmd.contains(phrase)) {
                    cb.onStatus("Выполняю вашу команду: " + parts[0] + ".");
                    boolean ok = (target.startsWith("http://") || target.startsWith("https://"))
                            ? openUrl(target)
                            : openAppFuzzy(target) != null;
                    cb.onStatus(ok ? "Команда выполнена." : "Не удалось выполнить команду.");
                    return;
                }
            }
        }

        // 2. Фонарик
        if (cmd.contains("фонарик") || cmd.contains("вспышк")) {
            boolean turnOn = !cmd.contains("выкл") && !cmd.contains("погас") && !cmd.contains("убер");
            cb.onStatus(turnOn ? "Включаю фонарик." : "Выключаю фонарик.");
            boolean ok = toggleFlashlight(turnOn);
            cb.onStatus(ok
                    ? (turnOn ? "Фонарик включён." : "Фонарик выключен.")
                    : "Нет доступа к фонарику. Проверьте разрешение камеры.");
            return;
        }

        // 3. Звонки ("позвони маме", "набери 112")
        if (cmd.startsWith("позвони ") || cmd.startsWith("набери ") || cmd.startsWith("вызови ")) {
            String target = cmd
                    .replaceFirst("^позвони\\s+", "")
                    .replaceFirst("^набери\\s+", "")
                    .replaceFirst("^вызови\\s+", "")
                    .trim();
            handleCall(target, cb);
            return;
        }

        // 4. Яркость экрана
        if (cmd.contains("яркость") || cmd.contains("ярче") || cmd.contains("темнее")) {
            handleBrightness(cmd, cb);
            return;
        }

        // 5. Запуск приложений (через FuzzyFinder)
        if (cmd.startsWith("открой ") || cmd.startsWith("запусти ") || cmd.startsWith("включи ")) {
            String appQuery = cmd
                    .replaceFirst("^открой\\s+", "")
                    .replaceFirst("^запусти\\s+", "")
                    .replaceFirst("^включи\\s+", "")
                    .trim();
            if (appQuery.equals("вайфай") || appQuery.equals("wi-fi") || appQuery.equals("интернет")) {
                cb.onStatus("Открываю панель интернета.");
                openConnectivityPanel();
                cb.onStatus("Панель открыта.");
                return;
            }
            if (appQuery.equals("блютуз") || appQuery.equals("bluetooth")) {
                cb.onStatus("Открываю настройки блютуз.");
                openActivitySafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                cb.onStatus("Настройки блютуз открыты.");
                return;
            }
            cb.onStatus("Ищу и открываю " + appQuery + ".");
            String launched = openAppFuzzy(appQuery);
            cb.onStatus(launched != null
                    ? "Приложение " + launched + " запущено."
                    : "Приложение «" + appQuery + "» не найдено.");
            return;
        }

        // 6. Громкость
        if (cmd.contains("громкость") || cmd.contains("громче") || cmd.contains("тише") || cmd.contains("звук")) {
            handleVolume(cmd, cb);
            return;
        }

        // 7. Будильник ("будильник на 7 30")
        if (cmd.contains("будильник")) {
            List<Integer> nums = extractNumbers(cmd);
            int hour = nums.size() >= 1 ? Math.min(23, Math.max(0, nums.get(0))) : 8;
            int min = nums.size() >= 2 ? Math.min(59, Math.max(0, nums.get(1))) : 0;
            cb.onStatus(String.format(Locale.US, "Ставлю будильник на %02d:%02d.", hour, min));
            boolean ok = setAlarm(hour, min);
            cb.onStatus(ok ? "Будильник установлен." : "Не удалось открыть часы.");
            return;
        }

        // 8. Таймер ("таймер на 5 минут")
        if (cmd.contains("таймер")) {
            List<Integer> nums = extractNumbers(cmd);
            int num = nums.isEmpty() ? 1 : nums.get(0);
            int seconds;
            String unit;
            if (cmd.contains("секунд")) { seconds = num; unit = "секунд"; }
            else if (cmd.contains("час")) { seconds = num * 3600; unit = "часов"; }
            else { seconds = num * 60; unit = "минут"; }
            cb.onStatus("Ставлю таймер на " + num + " " + unit + ".");
            boolean ok = setTimer(seconds);
            cb.onStatus(ok ? "Таймер запущен." : "Не удалось запустить таймер.");
            return;
        }

        // 9. Время, дата, батарея
        if (cmd.contains("время") || cmd.contains("который час")) {
            String time = new SimpleDateFormat("HH:mm", new Locale("ru")).format(new Date());
            cb.onStatus("Сейчас " + time + ".");
            return;
        }
        if (cmd.contains("дата") || cmd.contains("какое число")) {
            String date = new SimpleDateFormat("d MMMM, EEEE", new Locale("ru")).format(new Date());
            cb.onStatus("Сегодня " + date + ".");
            return;
        }
        if (cmd.contains("заряд") || cmd.contains("батаре")) {
            cb.onStatus("Уровень заряда батареи: " + getBatteryLevel() + " процентов.");
            return;
        }

        // 10. Сеть и настройки
        if (cmd.contains("вайфай") || cmd.contains("wi-fi") || cmd.contains("интернет")) {
            cb.onStatus("Открываю панель сети.");
            openConnectivityPanel();
            cb.onStatus("Панель сети открыта.");
            return;
        }
        if (cmd.contains("блютуз") || cmd.contains("bluetooth")) {
            cb.onStatus("Открываю настройки блютуз.");
            openActivitySafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            cb.onStatus("Настройки блютуз открыты.");
            return;
        }
        if (cmd.contains("настройки")) {
            cb.onStatus("Открываю настройки телефона.");
            openActivitySafe(new Intent(Settings.ACTION_SETTINGS));
            cb.onStatus("Настройки открыты.");
            return;
        }

        // 11. Поиск в Google
        if (cmd.startsWith("найди ") || cmd.startsWith("погугли ") || cmd.startsWith("поиск ")) {
            String query = cmd
                    .replaceFirst("^найди\\s+", "")
                    .replaceFirst("^погугли\\s+", "")
                    .replaceFirst("^поиск\\s+", "")
                    .trim();
            cb.onStatus("Ищу в интернете: " + query + ".");
            boolean ok = openUrl("https://www.google.com/search?q=" + Uri.encode(query));
            cb.onStatus(ok ? "Результаты открыты." : "Не удалось открыть браузер.");
            return;
        }

        if (cmd.contains("привет") || cmd.contains("здаров")) {
            cb.onStatus("Привет. Системы в норме, жду команду.");
            return;
        }
        if (cmd.contains("спасибо")) {
            cb.onStatus("Всегда к вашим услугам.");
            return;
        }

        cb.onStatus("Команда «" + cmd + "» не распознана.");
    }

    private void handleVolume(String cmd, StatusCallback cb) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) { cb.onStatus("Не удалось получить доступ к звуку."); return; }

        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        List<Integer> nums = extractNumbers(cmd);

        if (cmd.contains("без звука") || cmd.contains("выключи звук") || cmd.contains("минимум")) {
            cb.onStatus("Отключаю звук.");
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI);
            cb.onStatus("Звук отключён.");
        } else if (cmd.contains("максимум") || cmd.contains("на полную")) {
            cb.onStatus("Ставлю максимальную громкость.");
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI);
            cb.onStatus("Максимальная громкость установлена.");
        } else if (!nums.isEmpty()) {
            int pct = Math.min(100, Math.max(0, nums.get(0)));
            int target = Math.round((pct / 100f) * max);
            cb.onStatus("Ставлю громкость на " + pct + " процентов.");
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
            cb.onStatus("Громкость изменена.");
        } else if (cmd.contains("громче") || cmd.contains("прибавь")) {
            cb.onStatus("Делаю громче.");
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            cb.onStatus("Громкость увеличена.");
        } else if (cmd.contains("тише") || cmd.contains("убавь")) {
            cb.onStatus("Делаю тише.");
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            cb.onStatus("Громкость уменьшена.");
        } else {
            cb.onStatus("Уточните: громче, тише или процент громкости.");
        }
    }

    private void handleBrightness(String cmd, StatusCallback cb) {
        if (!Settings.System.canWrite(context)) {
            cb.onStatus("Нет права на изменение яркости. Открываю экран разрешения.");
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            openActivitySafe(i);
            return;
        }
        try {
            int current = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
            List<Integer> nums = extractNumbers(cmd);
            int target;

            if (cmd.contains("максимум") || cmd.contains("на полную")) {
                target = 255;
            } else if (cmd.contains("минимум")) {
                target = 15;
            } else if (!nums.isEmpty()) {
                int pct = Math.min(100, Math.max(1, nums.get(0)));
                target = Math.round((pct / 100f) * 255f);
            } else if (cmd.contains("ярче") || cmd.contains("прибавь") || cmd.contains("больше")) {
                target = Math.min(255, current + 55);
            } else if (cmd.contains("темнее") || cmd.contains("убавь") || cmd.contains("меньше")) {
                target = Math.max(15, current - 55);
            } else {
                cb.onStatus("Открываю настройки экрана.");
                openActivitySafe(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
                return;
            }

            cb.onStatus("Меняю яркость экрана.");
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, target);
            int pctDone = Math.round((target / 255f) * 100f);
            cb.onStatus("Яркость установлена на " + pctDone + " процентов.");
        } catch (Exception e) {
            cb.onStatus("Не удалось изменить яркость.");
        }
    }

    private void handleCall(String target, StatusCallback cb) {
        String digits = target.replaceAll("[^0-9+]", "");
        if (digits.length() >= 3) {
            cb.onStatus("Набираю номер " + digits + ".");
            placeCall(digits, cb);
            return;
        }

        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            cb.onStatus("Нет доступа к контактам. Выдайте разрешение в настройках Jarvis.");
            return;
        }

        String bestName = null;
        String bestNum = null;
        double bestScore = 0.0;
        String qLow = target.toLowerCase(new Locale("ru"));
        String qEn = FuzzyFinder.ruToEn(qLow);

        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null, null, null
            );
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    String num = c.getString(1);
                    if (name == null || num == null) continue;
                    String nLow = name.toLowerCase(new Locale("ru"));
                    double s = Math.max(
                            FuzzyFinder.scorePair(qLow, nLow),
                            FuzzyFinder.scorePair(qEn, FuzzyFinder.ruToEn(nLow))
                    );
                    if (s > bestScore) {
                        bestScore = s;
                        bestName = name;
                        bestNum = num;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }

        if (bestNum != null && bestScore >= 0.55) {
            cb.onStatus("Звоню контакту " + bestName + ".");
            placeCall(bestNum, cb);
        } else {
            cb.onStatus("Контакт «" + target + "» не найден.");
        }
    }

    private void placeCall(String number, StatusCallback cb) {
        boolean canCall = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        String action = canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL;
        Intent intent = new Intent(action, Uri.parse("tel:" + Uri.encode(number)));
        boolean ok = openActivitySafe(intent);
        cb.onStatus(ok ? "Вызов запущен." : "Не удалось совершить вызов.");
    }

    private String openAppFuzzy(String query) {
        FuzzyFinder.AppMatch match = FuzzyFinder.findBestApp(context, query);
        if (match == null) return null;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(match.packageName);
        if (launch == null) return null;
        return openActivitySafe(launch) ? match.label : null;
    }

    private boolean toggleFlashlight(boolean state) {
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return false;
            for (String id : cm.getCameraIdList()) {
                Boolean hasFlash = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(hasFlash)) {
                    cm.setTorchMode(id, state);
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private int getBatteryLevel() {
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent == null) return -1;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return (level >= 0 && scale > 0) ? (level * 100) / scale : -1;
    }

    private List<Integer> extractNumbers(String text) {
        List<Integer> res = new ArrayList<Integer>();
        Matcher m = Pattern.compile("\\d+").matcher(text);
        while (m.find()) {
            try { res.add(Integer.parseInt(m.group())); } catch (Exception ignored) {}
        }
        return res;
    }

    private boolean setTimer(int seconds) {
        Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER);
        intent.putExtra(AlarmClock.EXTRA_LENGTH, seconds);
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        return openActivitySafe(intent);
    }

    private boolean setAlarm(int hour, int minutes) {
        Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
        intent.putExtra(AlarmClock.EXTRA_HOUR, hour);
        intent.putExtra(AlarmClock.EXTRA_MINUTES, minutes);
        intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false);
        return openActivitySafe(intent);
    }

    private void openConnectivityPanel() {
        if (Build.VERSION.SDK_INT >= 29) {
            if (openActivitySafe(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))) return;
        }
        openActivitySafe(new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    private boolean openActivitySafe(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean openUrl(String url) {
        return openActivitySafe(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
                               }
      
