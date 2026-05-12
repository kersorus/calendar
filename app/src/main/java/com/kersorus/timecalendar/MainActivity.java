package com.kersorus.timecalendar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private Spinner profileSpinner;
    private Spinner periodSpinner;
    private EditText profileNameInput;
    private EditText targetHoursInput;
    private EditText deadlineInput;
    private TextView reportText;
    private TextView sessionsText;

    private DatabaseHelper db;
    private ArrayList<Profile> profiles = new ArrayList<>();
    private long selectedProfileId = 1L;
    private boolean loadingProfile = false;

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

        requestNotificationPermissionIfNeeded();
        buildUi();
        loadProfilesAndSelect(1L);
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
        title.setText("Часы и цели");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Минимальный трекер часов с нормой и прогнозом");
        subtitle.setTextSize(14);
        root.addView(subtitle);

        TextView profileLabel = new TextView(this);
        profileLabel.setText("\nПрофиль");
        profileLabel.setTextSize(18);
        profileLabel.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(profileLabel);

        profileSpinner = new Spinner(this);
        root.addView(profileSpinner);

        profileNameInput = new EditText(this);
        profileNameInput.setHint("Название, например Работа или Учёба");
        root.addView(profileNameInput);

        targetHoursInput = new EditText(this);
        targetHoursInput.setHint("Норма часов");
        targetHoursInput.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER |
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        root.addView(targetHoursInput);

        periodSpinner = new Spinner(this);
        ArrayAdapter<String> periodAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"Неделя", "Месяц", "До даты"}
        );
        periodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        periodSpinner.setAdapter(periodAdapter);
        root.addView(periodSpinner);

        deadlineInput = new EditText(this);
        deadlineInput.setHint("Дата цели: 2026-06-30");
        root.addView(deadlineInput);

        periodSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                deadlineInput.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
                if (!loadingProfile) {
                    refreshReport();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        LinearLayout profileButtons = new LinearLayout(this);
        profileButtons.setOrientation(LinearLayout.HORIZONTAL);
        profileButtons.setGravity(Gravity.CENTER);
        root.addView(profileButtons);

        Button newProfileButton = new Button(this);
        newProfileButton.setText("Новый");
        newProfileButton.setOnClickListener(v -> clearProfileForm());
        profileButtons.addView(newProfileButton);

        Button saveProfileButton = new Button(this);
        saveProfileButton.setText("Сохранить");
        saveProfileButton.setOnClickListener(v -> saveProfile());
        profileButtons.addView(saveProfileButton);

        TextView timerTitle = new TextView(this);
        timerTitle.setText("\nТаймер");
        timerTitle.setTextSize(20);
        timerTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(timerTitle);

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
        reportTitle.setText("\nПрогноз");
        reportTitle.setTextSize(20);
        reportTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(reportTitle);

        reportText = new TextView(this);
        reportText.setTextSize(16);
        root.addView(reportText);

        TextView sessionsTitle = new TextView(this);
        sessionsTitle.setText("\nПоследние сессии выбранного профиля");
        sessionsTitle.setTextSize(20);
        sessionsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(sessionsTitle);

        sessionsText = new TextView(this);
        sessionsText.setTextSize(15);
        root.addView(sessionsText);

        setContentView(scrollView);
    }

    private void loadProfilesAndSelect(long profileIdToSelect) {
        profiles = db.getProfiles();
        ArrayAdapter<Profile> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                profiles
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(adapter);

        int indexToSelect = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id == profileIdToSelect) {
                indexToSelect = i;
                break;
            }
        }

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < profiles.size()) {
                    selectedProfileId = profiles.get(position).id;
                    fillProfileForm(profiles.get(position));
                    refreshReport();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        profileSpinner.setSelection(indexToSelect);
        if (!profiles.isEmpty()) {
            selectedProfileId = profiles.get(indexToSelect).id;
            fillProfileForm(profiles.get(indexToSelect));
        }
        refreshReport();
    }

    private void fillProfileForm(Profile profile) {
        loadingProfile = true;
        selectedProfileId = profile.id;
        profileNameInput.setText(profile.name);
        targetHoursInput.setText(String.format(Locale.US, "%.1f", profile.targetHours));

        if (Profile.PERIOD_WEEK.equals(profile.periodType)) {
            periodSpinner.setSelection(0);
        } else if (Profile.PERIOD_DEADLINE.equals(profile.periodType)) {
            periodSpinner.setSelection(2);
        } else {
            periodSpinner.setSelection(1);
        }

        deadlineInput.setText(DateUtils.formatDeadline(profile.deadlineSeconds));
        deadlineInput.setVisibility(periodSpinner.getSelectedItemPosition() == 2 ? View.VISIBLE : View.GONE);
        loadingProfile = false;
    }

    private void clearProfileForm() {
        selectedProfileId = 0L;
        profileNameInput.setText("");
        targetHoursInput.setText("30.0");
        periodSpinner.setSelection(1);
        deadlineInput.setText("");
        deadlineInput.setVisibility(View.GONE);
        profileNameInput.requestFocus();
    }

    private void saveProfile() {
        String name = profileNameInput.getText().toString().trim();
        if (name.length() == 0) {
            toast("Введите название профиля");
            return;
        }

        double targetHours = parseTargetHours();
        String periodType = selectedPeriodType();
        long deadlineSeconds = 0L;

        if (Profile.PERIOD_DEADLINE.equals(periodType)) {
            try {
                deadlineSeconds = DateUtils.parseDeadlineEndSeconds(
                        deadlineInput.getText().toString().trim()
                );
            } catch (ParseException e) {
                toast("Дата должна быть в формате 2026-06-30");
                return;
            }
        }

        long savedId = db.saveProfile(
                selectedProfileId,
                name,
                targetHours,
                periodType,
                deadlineSeconds
        );

        toast("Профиль сохранён");
        loadProfilesAndSelect(savedId);
    }

    private double parseTargetHours() {
        try {
            String text = targetHoursInput.getText().toString().trim();
            double value = Double.parseDouble(text.replace(',', '.'));
            return Math.max(0.0, value);
        } catch (Exception e) {
            return 30.0;
        }
    }

    private String selectedPeriodType() {
        int position = periodSpinner.getSelectedItemPosition();
        if (position == 0) {
            return Profile.PERIOD_WEEK;
        }
        if (position == 2) {
            return Profile.PERIOD_DEADLINE;
        }
        return Profile.PERIOD_MONTH;
    }

    private Profile currentProfile() {
        for (Profile profile : profiles) {
            if (profile.id == selectedProfileId) {
                return profile;
            }
        }
        if (!profiles.isEmpty()) {
            return profiles.get(0);
        }
        return null;
    }

    private void startTimer() {
        Profile profile = currentProfile();
        if (profile == null) {
            toast("Сначала создайте профиль");
            return;
        }

        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(TimerService.ACTION_START);
        intent.putExtra(TimerService.EXTRA_PROFILE_ID, profile.id);
        intent.putExtra(TimerService.EXTRA_PROFILE_NAME, profile.name);

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
        if (reportText == null || sessionsText == null) {
            return;
        }

        Profile profile = currentProfile();
        if (profile == null) {
            reportText.setText("Создайте первый профиль.");
            sessionsText.setText("");
            return;
        }

        long now = DateUtils.nowSeconds();
        long from;
        long to;
        String periodLabel;

        if (Profile.PERIOD_WEEK.equals(profile.periodType)) {
            from = DateUtils.weekStartSeconds();
            to = DateUtils.nextWeekStartSeconds();
            periodLabel = "неделю";
        } else if (Profile.PERIOD_DEADLINE.equals(profile.periodType)) {
            from = profile.createdAtSeconds;
            to = profile.deadlineSeconds + 1L;
            periodLabel = "цель до " + DateUtils.formatDeadline(profile.deadlineSeconds);
        } else {
            int year = DateUtils.currentYear();
            int month = DateUtils.currentMonth();
            from = DateUtils.monthStartSeconds(year, month);
            to = DateUtils.nextMonthStartSeconds(year, month);
            periodLabel = "месяц";
        }

        long workedSeconds = db.getWorkedSecondsForRange(profile.id, from, to);
        double workedHours = NativeBridge.secondsToHours(workedSeconds);

        int daysInPeriod = DateUtils.inclusiveDays(from, to - 1L);
        int daysPassed = DateUtils.elapsedDaysInclusive(from, now, to - 1L);
        int daysLeft = DateUtils.daysLeftIncludingToday(now, to - 1L);

        double expectedHours = NativeBridge.expectedHours(
                profile.targetHours,
                daysInPeriod,
                daysPassed
        );
        double currentBalance = NativeBridge.balance(workedHours, expectedHours);
        double finalBalance = NativeBridge.balance(workedHours, profile.targetHours);
        double remainingHours = Math.max(0.0, profile.targetHours - workedHours);
        double requiredDaily = NativeBridge.requiredDailyHours(remainingHours, daysLeft);

        String moodLine;
        if (remainingHours <= 0.0001) {
            moodLine = "Норма закрыта. Можно спокойно остановиться или делать запас.";
        } else if (daysLeft <= 0) {
            moodLine = "Период закончился. Проверь итоговый баланс.";
        } else if (currentBalance >= 0.0) {
            moodLine = String.format(
                    Locale.getDefault(),
                    "Ты идёшь с запасом. Чтобы закрыть цель: %.2f ч/день.",
                    requiredDaily
            );
        } else {
            moodLine = String.format(
                    Locale.getDefault(),
                    "Чтобы спокойно догнать: %.2f ч/день.",
                    requiredDaily
            );
        }

        String report = String.format(
                Locale.getDefault(),
                "%s\n\n" +
                        "Период: %s\n" +
                        "Норма: %.2f ч\n" +
                        "Прошло дней: %d из %d\n" +
                        "Осталось дней: %d\n\n" +
                        "План к сегодня: %.2f ч\n" +
                        "Факт: %.2f ч\n" +
                        "Баланс сейчас: %.2f ч\n\n" +
                        "Осталось до цели: %.2f ч\n" +
                        "Нужно в день: %.2f ч\n" +
                        "Итоговый баланс, если остановиться сейчас: %.2f ч",
                moodLine,
                periodLabel,
                profile.targetHours,
                daysPassed,
                daysInPeriod,
                daysLeft,
                expectedHours,
                workedHours,
                currentBalance,
                remainingHours,
                requiredDaily,
                finalBalance
        );

        reportText.setText(report);
        sessionsText.setText(db.getLastSessionsText(profile.id, 20));
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
