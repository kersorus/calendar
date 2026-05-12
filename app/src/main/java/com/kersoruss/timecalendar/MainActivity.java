package com.kersoruss.timecalendar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Typeface;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "settings";
    private static final String KEY_TARGET_HOURS = "target_hours";

    private EditText targetHoursInput;
    private TextView reportText;
    private TextView sessionsText;

    private DatabaseHelper db;
    private SharedPreferences prefs;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshReport();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        NativeBridge.secondsToHours(0);
        db = new DatabaseHelper(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        requestNotificationPermissionIfNeeded();
        buildUi();
        refreshReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 40);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Time Calendar Minimal");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView profile = new TextView(this);
        profile.setText("\nПрофиль: Работа");
        profile.setTextSize(18);
        root.addView(profile);

        TextView targetLabel = new TextView(this);
        targetLabel.setText("\nНорма часов за месяц:");
        root.addView(targetLabel);

        targetHoursInput = new EditText(this);
        targetHoursInput.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER |
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        targetHoursInput.setText(
                String.format(
                        Locale.US,
                        "%.1f",
                        Double.longBitsToDouble(
                                prefs.getLong(
                                        KEY_TARGET_HOURS,
                                        Double.doubleToLongBits(30.0)
                                )
                        )
                )
        );
        root.addView(targetHoursInput);

        Button saveTargetButton = new Button(this);
        saveTargetButton.setText("Сохранить норму");
        saveTargetButton.setOnClickListener(v -> saveTargetHours());
        root.addView(saveTargetButton);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        root.addView(buttons);

        Button startButton = new Button(this);
        startButton.setText("Старт");
        startButton.setOnClickListener(v -> startTimer());
        buttons.addView(startButton);

        Button pauseButton = new Button(this);
        pauseButton.setText("Пауза");
        pauseButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_PAUSE));
        buttons.addView(pauseButton);

        Button resumeButton = new Button(this);
        resumeButton.setText("Дальше");
        resumeButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_RESUME));
        buttons.addView(resumeButton);

        Button stopButton = new Button(this);
        stopButton.setText("Стоп");
        stopButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_STOP));
        root.addView(stopButton);

        TextView reportTitle = new TextView(this);
        reportTitle.setText("\nОтчёт за текущий месяц");
        reportTitle.setTextSize(20);
        reportTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(reportTitle);

        reportText = new TextView(this);
        reportText.setTextSize(16);
        root.addView(reportText);

        TextView sessionsTitle = new TextView(this);
        sessionsTitle.setText("\nПоследние сессии");
        sessionsTitle.setTextSize(20);
        sessionsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(sessionsTitle);

        sessionsText = new TextView(this);
        sessionsText.setTextSize(15);
        root.addView(sessionsText);

        setContentView(scrollView);
    }

    private void saveTargetHours() {
        double target = parseTargetHours();

        prefs.edit()
                .putLong(KEY_TARGET_HOURS, Double.doubleToLongBits(target))
                .apply();

        refreshReport();
    }

    private double parseTargetHours() {
        try {
            String text = targetHoursInput.getText().toString().trim();
            double value = Double.parseDouble(text.replace(',', '.'));

            if (value < 0.0) {
                return 0.0;
            }

            return value;
        } catch (Exception e) {
            return 30.0;
        }
    }

    private double getSavedTargetHours() {
        return Double.longBitsToDouble(
                prefs.getLong(
                        KEY_TARGET_HOURS,
                        Double.doubleToLongBits(30.0)
                )
        );
    }

    private void startTimer() {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(TimerService.ACTION_START);
        intent.putExtra(TimerService.EXTRA_PROFILE, "Работа");

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(action);

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void refreshReport() {
        int year = DateUtils.currentYear();
        int month = DateUtils.currentMonth();
        int daysInMonth = DateUtils.daysInCurrentMonth();
        int daysPassed = DateUtils.currentDayOfMonth();

        long workedSeconds = db.getWorkedSecondsForMonth(year, month);
        double workedHours = NativeBridge.secondsToHours(workedSeconds);

        double targetHours = getSavedTargetHours();
        double expectedHours = NativeBridge.expectedHours(
                targetHours,
                daysInMonth,
                daysPassed
        );

        double currentBalance = NativeBridge.balance(workedHours, expectedHours);
        double monthBalance = NativeBridge.balance(workedHours, targetHours);

        String report = String.format(
                Locale.getDefault(),
                "Месячная норма: %.2f ч\n" +
                        "Прошло дней: %d из %d\n" +
                        "План к сегодняшнему дню: %.2f ч\n" +
                        "Отработано: %.2f ч\n" +
                        "Баланс сейчас: %.2f ч\n" +
                        "Баланс к полной месячной норме: %.2f ч",
                targetHours,
                daysPassed,
                daysInMonth,
                expectedHours,
                workedHours,
                currentBalance,
                monthBalance
        );

        reportText.setText(report);
        sessionsText.setText(db.getLastSessionsText(20));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    100
            );
        }
    }
}
