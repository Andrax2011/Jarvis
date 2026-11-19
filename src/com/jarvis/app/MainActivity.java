package com.jarvis.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView logView;
    private TextView customListView;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#060B19"));
        root.setPadding(36, 72, 36, 80);

        // Заголовок
        TextView title = new TextView(this);
        title.setText("JARVIS AI v2.0");
        title.setTextSize(26f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#00E5FF"));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        // Главная кнопка запуска/остановки
        btnToggle = makeButton("", "#0284C7", "#FFFFFF");
        updateToggleButton();
        btnToggle.setOnClickListener(v -> {
            if (JarvisService.isRunning) {
                stopService(new Intent(this, JarvisService.class));
                updateToggleButton();
            } else {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestRuntimePermissions();
                    Toast.makeText(this, "Сначала выдайте доступ к микрофону", Toast.LENGTH_LONG).show();
                    return;
                }
                startForegroundService(new Intent(this, JarvisService.class));
                btnToggle.setText("ОСТАНОВИТЬ JARVIS");
                btnToggle.setBackground(roundBg("#DC2626", "#F87171", 24));
            }
        });
        root.addView(btnToggle);

        // Карточка разрешений
        LinearLayout permCard = makeCard("Разрешения системы");
        Button b1 = makeButton("1. Микрофон, Камера, Контакты, Звонки", "#1E293B", "#E2E8F0");
        b1.setOnClickListener(v -> requestRuntimePermissions());
        permCard.addView(b1);

        Button b2 = makeButton("2. Запуск приложений из фона (Поверх окон)", "#1E293B", "#E2E8F0");
        b2.setOnClickListener(v -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Уже разрешено", Toast.LENGTH_SHORT).show();
            }
        });
        permCard.addView(b2);

        Button b3 = makeButton("3. Управление яркостью (Системные настройки)", "#1E293B", "#E2E8F0");
        b3.setOnClickListener(v -> {
            if (!Settings.System.canWrite(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                Toast.makeText(this, "Уже разрешено", Toast.LENGTH_SHORT).show();
            }
        });
        permCard.addView(b3);
        root.addView(permCard);

        // Карточка быстрого теста
        LinearLayout testCard = makeCard("Тест команды текстом");
        EditText editManual = makeInput("Напр.: открой тг / яркость 80 / позвони ...");
        testCard.addView(editManual);
        Button btnRun = makeButton("Выполнить", "#0369A1", "#FFFFFF");
        btnRun.setOnClickListener(v -> {
            String txt = editManual.getText().toString().trim();
            if (txt.isEmpty()) return;
            if (JarvisService.isRunning) {
                Intent svc = new Intent(this, JarvisService.class);
                svc.putExtra("manual_command", txt);
                startForegroundService(svc);
            } else {
                new CommandManager(this).execute(txt, msg -> {
                    logView.append("\nJarvis: " + msg);
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                });
            }
            editManual.setText("");
        });
        testCard.addView(btnRun);
        root.addView(testCard);

        // Настройки голоса и активации
        LinearLayout voiceCard = makeCard("Голос и активация");
        voiceCard.addView(makeLabel("Слово активации:"));
        EditText editWake = makeInput("джарвис");
        editWake.setText(prefs.getString("wake_word", "джарвис"));
        voiceCard.addView(editWake);

        Switch swWake = new Switch(this);
        swWake.setText("Ждать слово активации перед командой");
        swWake.setTextColor(Color.parseColor("#E2E8F0"));
        swWake.setChecked(prefs.getBoolean("require_wake_word", true));
        swWake.setPadding(0, 12, 0, 12);
        voiceCard.addView(swWake);

        Switch swVoice = new Switch(this);
        swVoice.setText("Озвучивать ответы голосом");
        swVoice.setTextColor(Color.parseColor("#E2E8F0"));
        swVoice.setChecked(prefs.getBoolean("voice_enabled", true));
        swVoice.setPadding(0, 8, 0, 16);
        voiceCard.addView(swVoice);

        float savedPitch = prefs.getFloat("voice_pitch", 0.85f);
        TextView pitchLbl = makeLabel(String.format(Locale.US, "Тембр голоса: %.2f", savedPitch));
        voiceCard.addView(pitchLbl);
        SeekBar seekPitch = new SeekBar(this);
        seekPitch.setMax(150);
        seekPitch.setProgress((int) ((savedPitch - 0.5f) * 100));
        seekPitch.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                pitchLbl.setText(String.format(Locale.US, "Тембр голоса: %.2f", 0.5f + p / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        voiceCard.addView(seekPitch);

        float savedSpeed = prefs.getFloat("voice_speed", 1.05f);
        TextView speedLbl = makeLabel(String.format(Locale.US, "Скорость речи: %.2f", savedSpeed));
        voiceCard.addView(speedLbl);
        SeekBar seekSpeed = new SeekBar(this);
        seekSpeed.setMax(150);
        seekSpeed.setProgress((int) ((savedSpeed - 0.5f) * 100));
        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                speedLbl.setText(String.format(Locale.US, "Скорость речи: %.2f", 0.5f + p / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        voiceCard.addView(seekSpeed);

        Button btnSave = makeButton("Сохранить настройки", "#0369A1", "#FFFFFF");
        btnSave.setOnClickListener(v -> {
            String w = editWake.getText().toString().trim();
            if (w.isEmpty()) w = "джарвис";
            prefs.edit()
                    .putString("wake_word", w)
                    .putBoolean("require_wake_word", swWake.isChecked())
                    .putBoolean("voice_enabled", swVoice.isChecked())
                    .putFloat("voice_pitch", 0.5f + seekPitch.getProgress() / 100f)
                    .putFloat("voice_speed", 0.5f + seekSpeed.getProgress() / 100f)
                    .apply();
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
        });
        voiceCard.addView(btnSave);
        root.addView(voiceCard);

        // Свои команды
        LinearLayout customCard = makeCard("Свои команды");
        EditText editPhrase = makeInput("Фраза (напр.: музыка)");
        EditText editTarget = makeInput("Приложение или ссылка https://...");
        customCard.addView(editPhrase);
        customCard.addView(editTarget);

        Button btnAdd = makeButton("Добавить команду", "#0369A1", "#FFFFFF");
        btnAdd.setOnClickListener(v -> {
            String p = editPhrase.getText().toString().trim();
            String a = editTarget.getText().toString().trim();
            if (!p.isEmpty() && !a.isEmpty()) {
                Set<String> set = new HashSet<String>(prefs.getStringSet("custom_commands", new HashSet<String>()));
                set.add(p + "||" + a);
                prefs.edit().putStringSet("custom_commands", set).apply();
                editPhrase.setText("");
                editTarget.setText("");
                refreshCustomCommands();
                Toast.makeText(this, "Добавлено", Toast.LENGTH_SHORT).show();
            }
        });
        customCard.addView(btnAdd);

        Button btnClear = makeButton("Очистить список", "#334155", "#CBD5E1");
        btnClear.setOnClickListener(v -> {
            prefs.edit().remove("custom_commands").apply();
            refreshCustomCommands();
        });
        customCard.addView(btnClear);

        customListView = makeLabel("");
        customCard.addView(customListView);
        refreshCustomCommands();
        root.addView(customCard);

        // Журнал
        LinearLayout logCard = makeCard("Журнал системы");
        logView = new TextView(this);
        logView.setTextSize(13f);
        logView.setTextColor(Color.parseColor("#CBD5E1"));
        logView.setTypeface(Typeface.MONOSPACE);
        logCard.addView(logView);
        root.addView(logCard);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#060B19"));
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateToggleButton();
        String logs = JarvisService.getLogsText();
        logView.setText(logs.isEmpty() ? "Готов к работе. Выдайте разрешения и запустите Jarvis." : logs);
        JarvisService.setLogListener(line -> runOnUiThread(() -> logView.setText(JarvisService.getLogsText())));
    }

    @Override
    protected void onPause() {
        super.onPause();
        JarvisService.setLogListener(null);
    }

    private void updateToggleButton() {
        if (JarvisService.isRunning) {
            btnToggle.setText("ОСТАНОВИТЬ JARVIS");
            btnToggle.setBackground(roundBg("#DC2626", "#F87171", 24));
        } else {
            btnToggle.setText("ЗАПУСТИТЬ JARVIS");
            btnToggle.setBackground(roundBg("#0284C7", "#38BDF8", 24));
        }
    }

    private LinearLayout makeCard(String header) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundBg("#0F172A", "#1E3A5F", 28));
        card.setPadding(32, 28, 32, 28);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 24, 0, 0);
        card.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText(header);
        tv.setTextSize(16f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.parseColor("#38BDF8"));
        tv.setPadding(0, 0, 0, 14);
        card.addView(tv);
        return card;
    }

    private Button makeButton(String text, String fillHex, String textHex) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.parseColor(textHex));
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setBackground(roundBg(fillHex, "#334155", 20));
        b.setPadding(24, 20, 24, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 10, 0, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText makeInput(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#64748B"));
        et.setTextColor(Color.parseColor("#F8FAFC"));
        et.setBackground(roundBg("#1E293B", "#334155", 16));
        et.setPadding(24, 20, 24, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 10);
        et.setLayoutParams(lp);
        return et;
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#CBD5E1"));
        tv.setTextSize(14f);
        tv.setPadding(0, 10, 0, 4);
        return tv;
    }

    private GradientDrawable roundBg(String fillHex, String strokeHex, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setStroke(2, Color.parseColor(strokeHex));
        gd.setCornerRadius(radius);
        return gd;
    }

    private void refreshCustomCommands() {
        Set<String> set = prefs.getStringSet("custom_commands", new HashSet<String>());
        if (set.isEmpty()) {
            customListView.setText("Своих команд пока нет.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String entry : set) {
            String[] parts = entry.split("\\|\\|");
            if (parts.length == 2) sb.append("• «").append(parts[0]).append("» → ").append(parts[1]).append("\n");
        }
        customListView.setText(sb.toString());
    }

    private void requestRuntimePermissions() {
        List<String> list = new ArrayList<String>();
        list.add(Manifest.permission.RECORD_AUDIO);
        list.add(Manifest.permission.CAMERA);
        list.add(Manifest.permission.READ_CONTACTS);
        list.add(Manifest.permission.CALL_PHONE);
        if (Build.VERSION.SDK_INT >= 33) {
            list.add("android.permission.POST_NOTIFICATIONS");
        }
        requestPermissions(list.toArray(new String[0]), 2001);
    }

    @Override
    public void onRequestPermissionsResult(int reqCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(reqCode, perms, results);
        boolean allOk = true;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) allOk = false;
        Toast.makeText(this, allOk ? "Все базовые права выданы!" : "Часть прав не выдана.", Toast.LENGTH_LONG).show();
    }
                }
          
