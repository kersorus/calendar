package com.kersorus.timecalendar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView profileCardText;
    private TextView calendarTitleText;
    private GridLayout calendarGrid;
    private TextView forecastMainText;
    private TextView forecastDetailsText;
    private TextView sessionsText;

    private DatabaseHelper db;
    private ArrayList<Profile> profiles = new ArrayList<>();
    private long selectedProfileId = 1L;
    private boolean forecastExpanded = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshEverything();
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
        root.setPadding(dp(20), dp(18) + statusBarHeight(), dp(20), dp(28));
        scrollView.addView(root);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(topRow);

        TextView title = new TextView(this);
        title.setText("Часы и цели");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(70, 70, 70));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button settingsButton = new Button(this);
        settingsButton.setText("⚙");
        settingsButton.setTextSize(20);
        settingsButton.setOnClickListener(v -> showEmptyMenu("Настройки"));
        topRow.addView(settingsButton, new LinearLayout.LayoutParams(dp(56), dp(48)));

        LinearLayout profileRow = new LinearLayout(this);
        profileRow.setOrientation(LinearLayout.HORIZONTAL);
        profileRow.setGravity(Gravity.CENTER_VERTICAL);
        profileRow.setPadding(0, dp(18), 0, dp(6));
        root.addView(profileRow);

        profileCardText = new TextView(this);
        profileCardText.setTextSize(18);
        profileCardText.setTextColor(Color.rgb(45, 45, 45));
        profileCardText.setPadding(dp(16), dp(12), dp(16), dp(12));
        profileCardText.setBackground(cardBackground());
        profileCardText.setOnClickListener(v -> showProfileChooser());
        profileRow.addView(profileCardText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button addProfileButton = new Button(this);
        addProfileButton.setText("+");
        addProfileButton.setTextSize(22);
        addProfileButton.setOnClickListener(v -> showEmptyMenu("Создание профиля"));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        addParams.leftMargin = dp(10);
        profileRow.addView(addProfileButton, addParams);

        calendarTitleText = sectionTitle("Календарь");
        root.addView(calendarTitleText);

        calendarGrid = new GridLayout(this);
        calendarGrid.setColumnCount(7);
        calendarGrid.setPadding(0, dp(4), 0, dp(10));
        root.addView(calendarGrid);

        TextView timerTitle = sectionTitle("Таймер");
        root.addView(timerTitle);

        LinearLayout timerRow = new LinearLayout(this);
        timerRow.setOrientation(LinearLayout.HORIZONTAL);
        timerRow.setGravity(Gravity.CENTER);
        root.addView(timerRow);

        Button startButton = new Button(this);
        startButton.setText("Старт");
        startButton.setOnClickListener(v -> startTimer());
        timerRow.addView(startButton, new LinearLayout.LayoutParams(0, dp(54), 1f));

        Button pauseButton = new Button(this);
        pauseButton.setText("Пауза");
        pauseButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_PAUSE));
        LinearLayout.LayoutParams middleButtonParams = new LinearLayout.LayoutParams(0, dp(54), 1f);
        middleButtonParams.leftMargin = dp(6);
        middleButtonParams.rightMargin = dp(6);
        timerRow.addView(pauseButton, middleButtonParams);

        Button resumeButton = new Button(this);
        resumeButton.setText("Дальше");
        resumeButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_RESUME));
        timerRow.addView(resumeButton, new LinearLayout.LayoutParams(0, dp(54), 1f));

        Button stopButton = new Button(this);
        stopButton.setText("Стоп");
        stopButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_STOP));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        stopParams.topMargin = dp(8);
        root.addView(stopButton, stopParams);

        TextView forecastTitle = sectionTitle("Главный ответ");
        root.addView(forecastTitle);

        LinearLayout forecastCard = new LinearLayout(this);
        forecastCard.setOrientation(LinearLayout.VERTICAL);
        forecastCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        forecastCard.setBackground(cardBackground());
        forecastCard.setOnClickListener(v -> toggleForecast());
        root.addView(forecastCard);

        forecastMainText = new TextView(this);
        forecastMainText.setTextSize(18);
        forecastMainText.setTypeface(Typeface.DEFAULT_BOLD);
        forecastMainText.setTextColor(Color.rgb(50, 50, 50));
        forecastCard.addView(forecastMainText);

        forecastDetailsText = new TextView(this);
        forecastDetailsText.setTextSize(15);
        forecastDetailsText.setPadding(0, dp(10), 0, 0);
        forecastDetailsText.setVisibility(View.GONE);
        forecastCard.addView(forecastDetailsText);

        TextView sessionsTitle = sectionTitle("Последние записи");
        root.addView(sessionsTitle);

        sessionsText = new TextView(this);
        sessionsText.setTextSize(15);
        sessionsText.setPadding(dp(16), dp(12), dp(16), dp(12));
        sessionsText.setBackground(cardBackground());
        sessionsText.setOnClickListener(v -> showFullLog());
        root.addView(sessionsText);

        setContentView(scrollView);
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText("\n" + text);
        view.setTextSize(21);
        view.setTextColor(Color.rgb(80, 80, 80));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private void loadProfilesAndSelect(long profileIdToSelect) {
        profiles = db.getProfiles();

        long selected = profileIdToSelect;
        boolean found = false;
        for (Profile profile : profiles) {
            if (profile.id == selected) {
                found = true;
                break;
            }
        }
        if (!found && !profiles.isEmpty()) {
            selected = profiles.get(0).id;
        }

        selectedProfileId = selected;
        refreshEverything();
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

    private void showProfileChooser() {
        if (profiles.isEmpty()) {
            toast("Профилей пока нет");
            return;
        }

        String[] names = new String[profiles.size()];
        int checked = 0;
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            names[i] = profile.name + " — " + formatHours(profile.targetHours) + " ч";
            if (profile.id == selectedProfileId) {
                checked = i;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Выбрать профиль")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    selectedProfileId = profiles.get(which).id;
                    refreshEverything();
                    dialog.dismiss();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showEmptyMenu(String title) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Пока пусто. Здесь будет следующий шаг интерфейса.")
                .setPositiveButton("ОК", null)
                .show();
    }

    private void showFullLog() {
        Profile profile = currentProfile();
        if (profile == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Полный лог: " + profile.name)
                .setMessage(db.getLastSessionsText(profile.id, 200))
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private void toggleForecast() {
        forecastExpanded = !forecastExpanded;
        forecastDetailsText.setVisibility(forecastExpanded ? View.VISIBLE : View.GONE);
    }

    private void refreshEverything() {
        Profile profile = currentProfile();
        if (profile == null) {
            profileCardText.setText("Создайте профиль  ▾");
            forecastMainText.setText("Нет профиля");
            forecastDetailsText.setText("Нажмите +, чтобы позже создать профиль.");
            sessionsText.setText("Записей пока нет.");
            calendarGrid.removeAllViews();
            return;
        }

        profileCardText.setText(profile.name + "\n" + formatHours(profile.targetHours) + " ч · " + periodTitle(profile));
        refreshCalendar(profile);
        refreshForecast(profile);
        sessionsText.setText(db.getLastSessionsText(profile.id, 8));
    }

    private void refreshCalendar(Profile profile) {
        int year = DateUtils.currentYear();
        int month = DateUtils.currentMonth();
        int daysInMonth = DateUtils.daysInMonth(year, month);
        int firstWeekday = DateUtils.firstWeekdayMondayBased(year, month);
        HashMap<Integer, Long> workedByDay = db.getWorkedSecondsByDay(profile.id, year, month);

        calendarTitleText.setText("\nКалендарь · " + String.format(Locale.getDefault(), "%02d.%d", month, year));
        calendarGrid.removeAllViews();

        String[] weekDays = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
        for (String dayName : weekDays) {
            TextView cell = calendarCell(dayName, true);
            calendarGrid.addView(cell);
        }

        for (int i = 1; i < firstWeekday; i++) {
            calendarGrid.addView(calendarCell("", false));
        }

        for (int day = 1; day <= daysInMonth; day++) {
            Long seconds = workedByDay.get(day);
            String text = String.valueOf(day);
            if (seconds != null && seconds > 0L) {
                text += "\n" + formatHours(NativeBridge.secondsToHours(seconds)) + " ч";
            }
            TextView cell = calendarCell(text, false);
            if (seconds != null && seconds > 0L) {
                cell.setTypeface(Typeface.DEFAULT_BOLD);
                cell.setBackground(dayWithWorkBackground());
            }
            calendarGrid.addView(cell);
        }
    }

    private TextView calendarCell(String text, boolean header) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setTextSize(header ? 13 : 12);
        cell.setGravity(Gravity.CENTER);
        cell.setMinHeight(dp(46));
        cell.setTextColor(Color.rgb(70, 70, 70));
        cell.setPadding(dp(2), dp(4), dp(2), dp(4));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = getResources().getDisplayMetrics().widthPixels / 7 - dp(7);
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        cell.setLayoutParams(params);
        if (header) {
            cell.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return cell;
    }

    private void refreshForecast(Profile profile) {
        long now = DateUtils.nowSeconds();
        long from;
        long toExclusive;
        String periodLabel;

        if (Profile.PERIOD_WEEK.equals(profile.periodType)) {
            from = DateUtils.weekStartSeconds();
            toExclusive = DateUtils.nextWeekStartSeconds();
            periodLabel = "неделя";
        } else if (Profile.PERIOD_DEADLINE.equals(profile.periodType)) {
            from = profile.createdAtSeconds;
            toExclusive = profile.deadlineSeconds + 1L;
            periodLabel = "до " + DateUtils.formatDeadline(profile.deadlineSeconds);
        } else {
            int year = DateUtils.currentYear();
            int month = DateUtils.currentMonth();
            from = DateUtils.monthStartSeconds(year, month);
            toExclusive = DateUtils.nextMonthStartSeconds(year, month);
            periodLabel = "месяц";
        }

        long workedSeconds = db.getWorkedSecondsForRange(profile.id, from, toExclusive);
        double workedHours = NativeBridge.secondsToHours(workedSeconds);

        int daysInPeriod = DateUtils.periodDays(from, toExclusive);
        int daysPassed = DateUtils.elapsedPeriodDaysIncludingToday(from, now, toExclusive);
        int daysLeft = DateUtils.daysLeftInPeriodIncludingToday(now, toExclusive);

        double expectedHours = NativeBridge.expectedHours(profile.targetHours, daysInPeriod, daysPassed);
        double currentBalance = NativeBridge.balance(workedHours, expectedHours);
        double finalBalance = NativeBridge.balance(workedHours, profile.targetHours);
        double remainingHours = Math.max(0.0, profile.targetHours - workedHours);
        double requiredDaily = NativeBridge.requiredDailyHours(remainingHours, daysLeft);

        String main;
        if (remainingHours <= 0.0001) {
            main = "Норма закрыта. Можно остановиться или делать запас.  ▾";
        } else if (daysLeft <= 0) {
            main = "Период закончился. Проверь итоговый баланс.  ▾";
        } else {
            main = "Чтобы спокойно догнать: " + formatHours(requiredDaily) + " ч/день.  ▾";
        }

        String details = String.format(
                Locale.getDefault(),
                "Профиль: %s\n" +
                        "Период: %s\n" +
                        "Норма: %s ч\n" +
                        "Прошло дней: %d из %d\n" +
                        "Осталось дней: %d\n\n" +
                        "План к сегодня: %s ч\n" +
                        "Факт: %s ч\n" +
                        "Баланс сейчас: %s ч\n\n" +
                        "Осталось до цели: %s ч\n" +
                        "Нужно в день: %s ч\n" +
                        "Итоговый баланс, если остановиться сейчас: %s ч",
                profile.name,
                periodLabel,
                formatHours(profile.targetHours),
                daysPassed,
                daysInPeriod,
                daysLeft,
                formatHours(expectedHours),
                formatHours(workedHours),
                formatSignedHours(currentBalance),
                formatHours(remainingHours),
                formatHours(requiredDaily),
                formatSignedHours(finalBalance)
        );

        forecastMainText.setText(main);
        forecastDetailsText.setText(details);
        forecastDetailsText.setVisibility(forecastExpanded ? View.VISIBLE : View.GONE);
    }

    private String periodTitle(Profile profile) {
        if (Profile.PERIOD_WEEK.equals(profile.periodType)) {
            return "неделя";
        }
        if (Profile.PERIOD_DEADLINE.equals(profile.periodType)) {
            return "до " + DateUtils.formatDeadline(profile.deadlineSeconds);
        }
        return "месяц";
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

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(245, 245, 245));
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), Color.rgb(225, 225, 225));
        return drawable;
    }

    private GradientDrawable dayWithWorkBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(232, 232, 232));
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return dp(24);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String formatHours(double value) {
        return String.format(Locale.getDefault(), "%.2f", value);
    }

    private static String formatSignedHours(double value) {
        return String.format(Locale.getDefault(), "%+.2f", value);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
