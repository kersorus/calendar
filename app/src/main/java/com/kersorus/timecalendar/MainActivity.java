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
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
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
    private long selectedProfileId = -1L;
    private boolean forecastExpanded = false;
    private boolean firstProfileDialogShown = false;

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
        loadProfilesAndSelect(-1L);
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
        addProfileButton.setOnClickListener(v -> showCreateProfileDialog(false));
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

        Button manualButton = new Button(this);
        manualButton.setText("+ запись вручную");
        manualButton.setOnClickListener(v -> showManualEntryDialog(null));
        LinearLayout.LayoutParams manualParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        manualParams.topMargin = dp(8);
        root.addView(manualButton, manualParams);

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

        if (profiles.isEmpty()) {
            selectedProfileId = -1L;
            refreshEverything();
            if (!firstProfileDialogShown) {
                firstProfileDialogShown = true;
                handler.postDelayed(() -> showCreateProfileDialog(true), 300L);
            }
            return;
        }

        long selected = profileIdToSelect;
        boolean found = false;
        for (Profile profile : profiles) {
            if (profile.id == selected) {
                found = true;
                break;
            }
        }
        if (!found) {
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
            showCreateProfileDialog(true);
            return;
        }

        String[] names = new String[profiles.size()];
        int checked = 0;
        for (int i = 0; i < profiles.size(); i++) {
            Profile profile = profiles.get(i);
            names[i] = formatProfileLine(profile);
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
                .setPositiveButton("Новый", (dialog, which) -> showCreateProfileDialog(false))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showCreateProfileDialog(boolean firstProfile) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText(firstProfile
                ? "Создайте первый профиль, чтобы начать учёт времени."
                : "Выберите тип профиля. Позже настройки можно будет расширить.");
        intro.setPadding(0, 0, 0, dp(10));
        root.addView(intro);

        EditText nameInput = new EditText(this);
        nameInput.setHint("Название, например Работа");
        nameInput.setSingleLine(true);
        nameInput.setText(firstProfile ? "Работа" : "");
        root.addView(labeled("Название", nameInput));

        Spinner typeSpinner = new Spinner(this);
        String[] typeLabels = {
                "Регулярная норма",
                "Цель до даты",
                "Просто учёт времени"
        };
        typeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, typeLabels));
        root.addView(labeled("Тип", typeSpinner));

        Spinner periodSpinner = new Spinner(this);
        String[] periodLabels = {"Месяц", "Неделя"};
        periodSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, periodLabels));
        LinearLayout periodRow = labeled("Период", periodSpinner);
        root.addView(periodRow);

        EditText targetInput = new EditText(this);
        targetInput.setHint("Например 30");
        targetInput.setSingleLine(true);
        targetInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        targetInput.setText("30");
        LinearLayout targetRow = labeled("Часов", targetInput);
        root.addView(targetRow);

        EditText deadlineInput = new EditText(this);
        deadlineInput.setHint("ГГГГ-ММ-ДД");
        deadlineInput.setSingleLine(true);
        deadlineInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        deadlineInput.setText(defaultDeadlineText());
        LinearLayout deadlineRow = labeled("Дедлайн", deadlineInput);
        root.addView(deadlineRow);

        Runnable updateVisibility = () -> {
            int type = typeSpinner.getSelectedItemPosition();
            boolean regular = type == 0;
            boolean deadline = type == 1;
            periodRow.setVisibility(regular ? View.VISIBLE : View.GONE);
            targetRow.setVisibility((regular || deadline) ? View.VISIBLE : View.GONE);
            deadlineRow.setVisibility(deadline ? View.VISIBLE : View.GONE);
            if (regular && targetInput.getText().toString().trim().length() == 0) {
                targetInput.setText("30");
            }
        };

        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateVisibility.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        updateVisibility.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(firstProfile ? "Первый профиль" : "Новый профиль")
                .setView(root)
                .setPositiveButton("Создать", null)
                .setNegativeButton(firstProfile ? "Позже" : "Отмена", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            if (name.length() == 0) {
                toast("Введите название профиля");
                return;
            }

            int type = typeSpinner.getSelectedItemPosition();
            String periodType;
            double targetHours = 0.0;
            long deadlineSeconds = 0L;

            if (type == 0) {
                periodType = periodSpinner.getSelectedItemPosition() == 1
                        ? Profile.PERIOD_WEEK
                        : Profile.PERIOD_MONTH;
                targetHours = parsePositiveDouble(targetInput.getText().toString(), -1.0);
                if (targetHours <= 0.0) {
                    toast("Введите количество часов больше нуля");
                    return;
                }
            } else if (type == 1) {
                periodType = Profile.PERIOD_DEADLINE;
                targetHours = parsePositiveDouble(targetInput.getText().toString(), -1.0);
                if (targetHours <= 0.0) {
                    toast("Введите количество часов больше нуля");
                    return;
                }
                try {
                    deadlineSeconds = DateUtils.parseDeadlineEndSeconds(deadlineInput.getText().toString().trim());
                } catch (Exception e) {
                    toast("Введите дедлайн в формате ГГГГ-ММ-ДД");
                    return;
                }
                if (deadlineSeconds <= DateUtils.nowSeconds()) {
                    toast("Дедлайн должен быть в будущем");
                    return;
                }
            } else {
                periodType = Profile.PERIOD_NONE;
            }

            long profileId = db.saveProfile(0L, name, targetHours, periodType, deadlineSeconds);
            if (profileId <= 0L) {
                toast("Не удалось сохранить профиль");
                return;
            }
            dialog.dismiss();
            loadProfilesAndSelect(profileId);
        }));

        dialog.show();
    }

    private LinearLayout labeled(String label, View input) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(4), 0, dp(6));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        wrapper.addView(labelView);
        wrapper.addView(input);
        return wrapper;
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
            toast("Сначала создайте профиль");
            return;
        }
        ArrayList<TimeSession> sessions = db.getSessions(profile.id, 200);
        if (sessions.isEmpty()) {
            toast("Записей пока нет");
            return;
        }

        String[] items = new String[sessions.size()];
        for (int i = 0; i < sessions.size(); i++) {
            items[i] = shortSessionLine(sessions.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle("Полный лог: " + profile.name)
                .setItems(items, (dialog, which) -> showManualEntryDialog(sessions.get(which)))
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private void showManualEntryDialog(TimeSession sessionToEdit) {
        Profile profile = currentProfile();
        if (profile == null) {
            showCreateProfileDialog(true);
            return;
        }

        boolean editing = sessionToEdit != null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        EditText dateInput = new EditText(this);
        dateInput.setHint("ГГГГ-ММ-ДД");
        dateInput.setSingleLine(true);
        dateInput.setInputType(InputType.TYPE_CLASS_DATETIME);
        dateInput.setText(editing ? DateUtils.formatDate(sessionToEdit.startTime) : DateUtils.formatDate(DateUtils.nowSeconds()));
        root.addView(labeled("Дата", dateInput));

        EditText hoursInput = new EditText(this);
        hoursInput.setHint("Например 1.5");
        hoursInput.setSingleLine(true);
        hoursInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        hoursInput.setText(editing ? formatHours(NativeBridge.secondsToHours(sessionToEdit.workedSeconds)) : "1.00");
        root.addView(labeled("Часов", hoursInput));

        EditText commentInput = new EditText(this);
        commentInput.setHint("Комментарий, необязательно");
        commentInput.setSingleLine(false);
        commentInput.setMinLines(2);
        commentInput.setText(editing ? sessionToEdit.comment : "");
        root.addView(labeled("Комментарий", commentInput));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "Редактировать запись" : "Добавить запись")
                .setView(root)
                .setPositiveButton(editing ? "Сохранить" : "Добавить", null)
                .setNegativeButton("Отмена", null)
                .setNeutralButton(editing ? "Удалить" : null, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                long dayStart;
                try {
                    dayStart = DateUtils.parseDayStartSeconds(dateInput.getText().toString().trim());
                } catch (Exception e) {
                    toast("Введите дату в формате ГГГГ-ММ-ДД");
                    return;
                }

                double hours = parsePositiveDouble(hoursInput.getText().toString(), -1.0);
                if (hours <= 0.0) {
                    toast("Введите количество часов больше нуля");
                    return;
                }

                long workedSeconds = Math.round(hours * 3600.0);
                long startTime = dayStart + 12L * 3600L;
                long endTime = startTime + workedSeconds;
                String comment = commentInput.getText().toString().trim();

                if (editing) {
                    db.updateSession(sessionToEdit.id, startTime, endTime, workedSeconds, comment);
                } else {
                    db.addSession(profile.id, profile.name, startTime, endTime, 0L, workedSeconds, comment);
                }

                dialog.dismiss();
                refreshEverything();
            });

            Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (neutral != null && editing) {
                neutral.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Удалить запись?")
                        .setMessage(shortSessionLine(sessionToEdit))
                        .setPositiveButton("Удалить", (confirmDialog, which) -> {
                            db.deleteSession(sessionToEdit.id);
                            dialog.dismiss();
                            refreshEverything();
                        })
                        .setNegativeButton("Отмена", null)
                        .show());
            }
        });

        dialog.show();
    }

    private void toggleForecast() {
        forecastExpanded = !forecastExpanded;
        forecastDetailsText.setVisibility(forecastExpanded ? View.VISIBLE : View.GONE);
    }

    private void refreshEverything() {
        Profile profile = currentProfile();
        if (profile == null) {
            profileCardText.setText("Создайте первый профиль  +");
            calendarTitleText.setText("\nКалендарь");
            calendarGrid.removeAllViews();
            forecastMainText.setText("Создайте профиль, чтобы начать.  ▾");
            forecastDetailsText.setText("Можно выбрать регулярную норму, цель до даты или простой учёт времени без нормы.");
            forecastDetailsText.setVisibility(forecastExpanded ? View.VISIBLE : View.GONE);
            sessionsText.setText("Записей пока нет.");
            return;
        }

        profileCardText.setText(profile.name + "\n" + formatProfileShort(profile));
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

        if (Profile.PERIOD_NONE.equals(profile.periodType)) {
            refreshSimpleProfileForecast(profile);
            return;
        }

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

        boolean todayHasWork = db.hasWorkOnDay(profile.id, DateUtils.todayStartSeconds());
        int daysInPeriod = DateUtils.periodDays(from, toExclusive);
        int daysPassed = DateUtils.elapsedPeriodDaysSmart(from, now, toExclusive, todayHasWork);
        int daysLeftAfterToday = DateUtils.daysLeftAfterTodaySmart(from, now, toExclusive, todayHasWork);
        int daysForWork = DateUtils.daysAvailableForWorkSmart(now, toExclusive, todayHasWork);

        double expectedHours = NativeBridge.expectedHours(profile.targetHours, daysInPeriod, daysPassed);
        double currentBalance = NativeBridge.balance(workedHours, expectedHours);
        double finalBalance = NativeBridge.balance(workedHours, profile.targetHours);
        double remainingHours = Math.max(0.0, profile.targetHours - workedHours);
        double requiredDaily = NativeBridge.requiredDailyHours(remainingHours, daysForWork);
        double requiredWeekly = requiredDaily * 7.0;

        String main;
        if (remainingHours <= 0.0001) {
            main = "Цель закрыта. Запас: " + formatHours(Math.max(0.0, workedHours - profile.targetHours)) + " ч.  ▾";
        } else if (daysForWork <= 0) {
            main = "Период закончился. Итог: " + formatSignedHours(finalBalance) + " ч.  ▾";
        } else {
            main = "Чтобы спокойно успеть: " + formatHours(requiredDaily) + " ч/день.  ▾";
        }

        String details = String.format(
                Locale.getDefault(),
                "Профиль: %s\n" +
                        "Период: %s\n" +
                        "Норма: %s ч\n" +
                        "Прошло дней: %d из %d\n" +
                        "Сегодня считается пройденным: %s\n" +
                        "Осталось дней: %d\n" +
                        "Дней для добора: %d\n\n" +
                        "План к сегодня: %s ч\n" +
                        "Факт: %s ч\n" +
                        "Баланс сейчас: %s ч\n\n" +
                        "Осталось до цели: %s ч\n" +
                        "Нужно в день: %s ч\n" +
                        "Нужно в неделю: %s ч\n" +
                        "Итоговый баланс, если остановиться сейчас: %s ч",
                profile.name,
                periodLabel,
                formatHours(profile.targetHours),
                daysPassed,
                daysInPeriod,
                todayHasWork ? "да" : "нет",
                daysLeftAfterToday,
                daysForWork,
                formatHours(expectedHours),
                formatHours(workedHours),
                formatSignedHours(currentBalance),
                formatHours(remainingHours),
                formatHours(requiredDaily),
                formatHours(requiredWeekly),
                formatSignedHours(finalBalance)
        );

        forecastMainText.setText(main);
        forecastDetailsText.setText(details);
        forecastDetailsText.setVisibility(forecastExpanded ? View.VISIBLE : View.GONE);
    }

    private void refreshSimpleProfileForecast(Profile profile) {
        int year = DateUtils.currentYear();
        int month = DateUtils.currentMonth();
        long from = DateUtils.monthStartSeconds(year, month);
        long toExclusive = DateUtils.nextMonthStartSeconds(year, month);
        long workedSeconds = db.getWorkedSecondsForRange(profile.id, from, toExclusive);
        double workedHours = NativeBridge.secondsToHours(workedSeconds);

        forecastMainText.setText("Без нормы: в этом месяце " + formatHours(workedHours) + " ч.  ▾");
        forecastDetailsText.setText(
                "Профиль: " + profile.name + "\n" +
                        "Тип: простой учёт времени\n" +
                        "Регулярной нормы нет. Приложение просто собирает сессии, показывает календарь и лог.\n\n" +
                        "За текущий месяц: " + formatHours(workedHours) + " ч"
        );
        forecastDetailsText.setVisibility(forecastExpanded ? View.VISIBLE : View.GONE);
    }

    private String periodTitle(Profile profile) {
        if (Profile.PERIOD_WEEK.equals(profile.periodType)) {
            return "неделя";
        }
        if (Profile.PERIOD_DEADLINE.equals(profile.periodType)) {
            return "до " + DateUtils.formatDeadline(profile.deadlineSeconds);
        }
        if (Profile.PERIOD_NONE.equals(profile.periodType)) {
            return "без нормы";
        }
        return "месяц";
    }

    private String formatProfileShort(Profile profile) {
        if (Profile.PERIOD_NONE.equals(profile.periodType)) {
            return "простой учёт · без нормы";
        }
        return formatHours(profile.targetHours) + " ч · " + periodTitle(profile);
    }

    private String formatProfileLine(Profile profile) {
        return profile.name + " — " + formatProfileShort(profile);
    }

    private String shortSessionLine(TimeSession session) {
        String line = DateUtils.formatDateTime(session.startTime) + " · " +
                formatHours(NativeBridge.secondsToHours(session.workedSeconds)) + " ч";
        if (session.comment != null && session.comment.trim().length() > 0) {
            line += " · " + session.comment.trim();
        }
        return line;
    }

    private void startTimer() {
        Profile profile = currentProfile();
        if (profile == null) {
            showCreateProfileDialog(true);
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

    private static double parsePositiveDouble(String text, double fallback) {
        try {
            String normalized = text.trim().replace(',', '.');
            double value = Double.parseDouble(normalized);
            return value > 0.0 ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String defaultDeadlineText() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MONTH, 1);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return format.format(new Date(calendar.getTimeInMillis()));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
