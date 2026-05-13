package com.kersorus.timecalendar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
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
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
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
    private Button calendarModeButton;
    private GridLayout calendarGrid;
    private TextView forecastMainText;
    private TextView forecastDetailsText;
    private TextView sessionsText;

    private DatabaseHelper db;
    private ArrayList<Profile> profiles = new ArrayList<>();
    private long selectedProfileId = -1L;
    private boolean forecastExpanded = false;
    private boolean firstProfileDialogShown = false;
    private boolean calendarMonthMode = true;
    private float calendarTouchStartX = 0f;
    private float calendarTouchStartY = 0f;
    private long calendarTouchStartTime = 0L;
    private boolean ignoreNextCalendarClick = false;
    private int visibleYear = DateUtils.currentYear();
    private int visibleMonth = DateUtils.currentMonth();
    private long visibleWeekStartSeconds = DateUtils.weekStartSeconds();

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

        Button menuButton = new Button(this);
        menuButton.setText("☰");
        menuButton.setTextSize(22);
        menuButton.setOnClickListener(v -> showMainMenu());
        topRow.addView(menuButton, new LinearLayout.LayoutParams(dp(56), dp(48)));

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

        LinearLayout calendarControls = new LinearLayout(this);
        calendarControls.setOrientation(LinearLayout.HORIZONTAL);
        calendarControls.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(calendarControls);

        Button prevCalendarButton = new Button(this);
        prevCalendarButton.setText("‹");
        prevCalendarButton.setOnClickListener(v -> moveCalendar(-1));
        calendarControls.addView(prevCalendarButton, new LinearLayout.LayoutParams(dp(44), dp(46)));

        Button todayCalendarButton = new Button(this);
        todayCalendarButton.setText("Сегодня");
        todayCalendarButton.setOnClickListener(v -> resetCalendarToToday());
        LinearLayout.LayoutParams todayParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        todayParams.leftMargin = dp(4);
        todayParams.rightMargin = dp(4);
        calendarControls.addView(todayCalendarButton, todayParams);

        Button jumpCalendarButton = new Button(this);
        jumpCalendarButton.setText("Дата");
        jumpCalendarButton.setOnClickListener(v -> showCalendarJumpDialog());
        LinearLayout.LayoutParams jumpParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        jumpParams.rightMargin = dp(4);
        calendarControls.addView(jumpCalendarButton, jumpParams);

        calendarModeButton = new Button(this);
        calendarModeButton.setText("Месяц");
        calendarModeButton.setOnClickListener(v -> toggleCalendarMode());
        calendarControls.addView(calendarModeButton, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button nextCalendarButton = new Button(this);
        nextCalendarButton.setText("›");
        nextCalendarButton.setOnClickListener(v -> moveCalendar(1));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(dp(44), dp(46));
        nextParams.leftMargin = dp(4);
        calendarControls.addView(nextCalendarButton, nextParams);

        calendarGrid = new GridLayout(this);
        calendarGrid.setColumnCount(7);
        calendarGrid.setPadding(dp(2), dp(6), dp(2), dp(10));
        calendarGrid.setMinHeight(dp(300));
        calendarGrid.setBackground(calendarAreaBackground());
        calendarGrid.setClickable(true);
        calendarGrid.setOnTouchListener(this::handleCalendarSwipeTouch);
        root.addView(calendarGrid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView timerTitle = sectionTitle("Таймер");
        root.addView(timerTitle);

        LinearLayout timerRow = new LinearLayout(this);
        timerRow.setOrientation(LinearLayout.HORIZONTAL);
        timerRow.setGravity(Gravity.CENTER);
        root.addView(timerRow);

        Button startButton = new Button(this);
        startButton.setText("▶");
        startButton.setTextSize(22);
        startButton.setOnClickListener(v -> startTimer());
        timerRow.addView(startButton, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button pauseButton = new Button(this);
        pauseButton.setText("⏸");
        pauseButton.setTextSize(22);
        pauseButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_PAUSE));
        LinearLayout.LayoutParams middleButtonParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        middleButtonParams.leftMargin = dp(6);
        middleButtonParams.rightMargin = dp(6);
        timerRow.addView(pauseButton, middleButtonParams);

        Button resumeButton = new Button(this);
        resumeButton.setText("▶");
        resumeButton.setTextSize(22);
        resumeButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_RESUME));
        timerRow.addView(resumeButton, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button stopButton = new Button(this);
        stopButton.setText("■");
        stopButton.setTextSize(22);
        stopButton.setOnClickListener(v -> sendServiceAction(TimerService.ACTION_STOP));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        stopParams.leftMargin = dp(6);
        timerRow.addView(stopButton, stopParams);


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
                .setNeutralButton("Редактировать", (dialog, which) -> showEditCurrentProfileDialog())
                .setNegativeButton("Закрыть", null)
                .show();
    }


    private void showCreateProfileDialog(boolean firstProfile) {
        showProfileEditorDialog(null, firstProfile);
    }

    private void showEditCurrentProfileDialog() {
        Profile profile = currentProfile();
        if (profile == null) {
            showCreateProfileDialog(true);
            return;
        }
        showProfileEditorDialog(profile, false);
    }

    private void showProfileEditorDialog(Profile profileToEdit, boolean firstProfile) {
        boolean editing = profileToEdit != null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(4);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setText(firstProfile
                ? "Создайте первый профиль, чтобы начать учёт времени."
                : editing ? "Измените параметры профиля." : "Выберите тип профиля.");
        intro.setPadding(0, 0, 0, dp(10));
        root.addView(intro);

        EditText nameInput = new EditText(this);
        nameInput.setHint("Название, например Работа");
        nameInput.setSingleLine(true);
        nameInput.setText(editing ? profileToEdit.name : (firstProfile ? "Работа" : ""));
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
        targetInput.setText(editing ? formatHours(profileToEdit.targetHours) : "30");
        LinearLayout targetRow = labeled("Часов", targetInput);
        root.addView(targetRow);

        CheckBox workScheduleCheck = new CheckBox(this);
        workScheduleCheck.setText("Рабочий график: понедельник–пятница по 8 часов");
        workScheduleCheck.setPadding(0, dp(6), 0, dp(6));
        workScheduleCheck.setChecked(editing && profileToEdit.useWorkSchedule);
        root.addView(workScheduleCheck);

        EditText deadlineInput = new EditText(this);
        deadlineInput.setHint("Выберите дату");
        deadlineInput.setSingleLine(true);
        deadlineInput.setFocusable(false);
        deadlineInput.setInputType(InputType.TYPE_NULL);
        deadlineInput.setText(editing && profileToEdit.deadlineSeconds > 0L
                ? DateUtils.formatDate(profileToEdit.deadlineSeconds)
                : defaultDeadlineText());
        deadlineInput.setOnClickListener(v -> showDatePicker(deadlineInput, true));
        LinearLayout deadlineRow = labeled("Дедлайн", deadlineInput);
        root.addView(deadlineRow);

        if (editing) {
            if (Profile.PERIOD_DEADLINE.equals(profileToEdit.periodType)) {
                typeSpinner.setSelection(1);
            } else if (Profile.PERIOD_NONE.equals(profileToEdit.periodType)) {
                typeSpinner.setSelection(2);
            } else {
                typeSpinner.setSelection(0);
            }
            periodSpinner.setSelection(Profile.PERIOD_WEEK.equals(profileToEdit.periodType) ? 1 : 0);
        }

        Runnable updateVisibility = () -> {
            int type = typeSpinner.getSelectedItemPosition();
            boolean regular = type == 0;
            boolean deadline = type == 1;
            periodRow.setVisibility(regular ? View.VISIBLE : View.GONE);
            workScheduleCheck.setVisibility(regular ? View.VISIBLE : View.GONE);
            targetRow.setVisibility(((regular && !workScheduleCheck.isChecked()) || deadline) ? View.VISIBLE : View.GONE);
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
        workScheduleCheck.setOnCheckedChangeListener((buttonView, isChecked) -> updateVisibility.run());
        updateVisibility.run();

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editing ? "Редактировать профиль" : firstProfile ? "Первый профиль" : "Новый профиль")
                .setView(root)
                .setPositiveButton(editing ? "Сохранить" : "Создать", null)
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
            boolean useWorkSchedule = false;
            int workDaysMask = Profile.WORK_DAYS_MON_FRI;
            double workHoursPerDay = 8.0;

            if (type == 0) {
                periodType = periodSpinner.getSelectedItemPosition() == 1 ? Profile.PERIOD_WEEK : Profile.PERIOD_MONTH;
                useWorkSchedule = workScheduleCheck.isChecked();
                if (useWorkSchedule) {
                    targetHours = 0.0;
                    workHoursPerDay = 8.0;
                } else {
                    targetHours = parsePositiveDouble(targetInput.getText().toString(), -1.0);
                    if (targetHours <= 0.0) {
                        toast("Введите норму часов");
                        return;
                    }
                }
            } else if (type == 1) {
                periodType = Profile.PERIOD_DEADLINE;
                targetHours = parsePositiveDouble(targetInput.getText().toString(), -1.0);
                if (targetHours <= 0.0) {
                    toast("Введите количество часов до дедлайна");
                    return;
                }
                try {
                    deadlineSeconds = DateUtils.parseDeadlineEndSeconds(deadlineInput.getText().toString().trim());
                } catch (Exception e) {
                    toast("Выберите дату дедлайна");
                    return;
                }
                if (deadlineSeconds <= DateUtils.nowSeconds()) {
                    toast("Дедлайн должен быть в будущем");
                    return;
                }
            } else {
                periodType = Profile.PERIOD_NONE;
            }

            long profileId = db.saveProfile(
                    editing ? profileToEdit.id : 0L,
                    name,
                    targetHours,
                    periodType,
                    deadlineSeconds,
                    useWorkSchedule,
                    workDaysMask,
                    workHoursPerDay
            );
            if (profileId <= 0L) {
                toast("Не удалось сохранить профиль");
                return;
            }
            dialog.dismiss();
            loadProfilesAndSelect(profileId);
        }));

        dialog.show();
    }

    private void showDatePicker(EditText targetInput, boolean deadlineEnd) {
        long initialSeconds;
        try {
            String current = targetInput.getText().toString().trim();
            initialSeconds = current.length() == 0
                    ? DateUtils.todayStartSeconds()
                    : DateUtils.parseDayStartSeconds(current);
        } catch (Exception e) {
            initialSeconds = DateUtils.todayStartSeconds();
        }

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(initialSeconds * 1000L);

        DatePickerDialog picker = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    long selected = DateUtils.dayStartSeconds(year, month + 1, dayOfMonth);
                    targetInput.setText(DateUtils.formatDate(selected));
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        picker.show();
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


    private void showMainMenu() {
        String[] items = {"Лог", "Редактировать профиль", "Настройки"};
        new AlertDialog.Builder(this)
                .setTitle("Меню")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        showFullLog();
                    } else if (which == 1) {
                        showEditCurrentProfileDialog();
                    } else {
                        showEmptyMenu("Настройки");
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showCalendarJumpDialog() {
        long initialSeconds;
        if (calendarMonthMode) {
            initialSeconds = DateUtils.monthStartSeconds(visibleYear, visibleMonth);
        } else {
            initialSeconds = visibleWeekStartSeconds;
        }

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(initialSeconds * 1000L);

        DatePickerDialog picker = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    long selected = DateUtils.dayStartSeconds(year, month + 1, dayOfMonth);
                    visibleYear = year;
                    visibleMonth = month + 1;
                    visibleWeekStartSeconds = DateUtils.weekStartSecondsFor(selected);
                    refreshEverything();
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        );
        picker.show();
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
                .setItems(items, (dialog, which) -> showManualEntryDialog(sessions.get(which), DateUtils.startOfDaySeconds(sessions.get(which).startTime)))
                .setPositiveButton("Закрыть", null)
                .show();
    }

    private void showDayDialog(Profile profile, long dayStartSeconds) {
        ArrayList<TimeSession> sessions = db.getSessionsForDay(profile.id, dayStartSeconds);
        long totalSeconds = 0L;
        for (TimeSession session : sessions) {
            totalSeconds += session.workedSeconds;
        }

        String title = DateUtils.formatDate(dayStartSeconds) + " · " + profile.name;
        String message = "Итого за день: " + formatHours(NativeBridge.secondsToHours(totalSeconds)) + " ч";

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setPositiveButton("Добавить", (dialog, which) -> showManualEntryDialog(null, dayStartSeconds))
                .setNegativeButton("Закрыть", null);

        if (sessions.isEmpty()) {
            builder.setMessage(message + "\n\nЗаписей нет. Нажмите Добавить, чтобы внести время на эту дату.");
        } else {
            String[] items = new String[sessions.size()];
            for (int i = 0; i < sessions.size(); i++) {
                items[i] = shortSessionLine(sessions.get(i));
            }
            builder.setMessage(message + "\n\nНажмите на запись, чтобы отредактировать.");
            builder.setItems(items, (dialog, which) -> showManualEntryDialog(sessions.get(which), dayStartSeconds));
        }

        builder.show();
    }

    private void showManualEntryDialog(TimeSession sessionToEdit, long preferredDayStartSeconds) {
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
        dateInput.setHint("Выберите дату");
        dateInput.setSingleLine(true);
        dateInput.setFocusable(false);
        dateInput.setInputType(InputType.TYPE_NULL);
        dateInput.setText(editing ? DateUtils.formatDate(sessionToEdit.startTime) : DateUtils.formatDate(preferredDayStartSeconds));
        dateInput.setOnClickListener(v -> showDatePicker(dateInput, false));
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
                if (hours > 24.0) {
                    toast("В одной дате не может быть больше 24 часов");
                    return;
                }

                long workedSeconds = Math.round(hours * 3600.0);
                long excludedSessionId = editing ? sessionToEdit.id : -1L;
                long alreadyOnDay = db.getWorkedSecondsForDayExcludingSession(
                        profile.id,
                        dayStart,
                        excludedSessionId
                );
                if (alreadyOnDay + workedSeconds > 24L * 3600L) {
                    double availableHours = NativeBridge.secondsToHours(24L * 3600L - alreadyOnDay);
                    toast("На эту дату доступно максимум " + formatHours(Math.max(0.0, availableHours)) + " ч");
                    return;
                }

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
            if (sessionsText != null) {
                sessionsText.setText("Записей пока нет.");
            }
            return;
        }

        profileCardText.setText(profile.name + "\n" + formatProfileShort(profile));
        refreshCalendar(profile);
        refreshForecast(profile);
        if (sessionsText != null) {
            sessionsText.setText(db.getLastSessionsText(profile.id, 8));
        }
    }

    private void refreshCalendar(Profile profile) {
        if (calendarModeButton != null) {
            calendarModeButton.setText(calendarMonthMode ? "Месяц" : "Неделя");
        }

        if (calendarMonthMode) {
            refreshMonthCalendar(profile);
        } else {
            refreshWeekCalendar(profile);
        }
    }

    private void refreshMonthCalendar(Profile profile) {
        int year = visibleYear;
        int month = visibleMonth;
        int daysInMonth = DateUtils.daysInMonth(year, month);
        int firstWeekday = DateUtils.firstWeekdayMondayBased(year, month);
        HashMap<Long, Long> workedByDay = db.getWorkedSecondsByDayRange(
                profile.id,
                DateUtils.monthStartSeconds(year, month),
                DateUtils.nextMonthStartSeconds(year, month)
        );

        calendarTitleText.setText("\nКалендарь · " + String.format(Locale.getDefault(), "%02d.%d", month, year));
        calendarGrid.removeAllViews();
        calendarGrid.setColumnCount(7);

        addWeekdayHeaders();

        for (int i = 1; i < firstWeekday; i++) {
            calendarGrid.addView(calendarEmptyCell());
        }

        for (int day = 1; day <= daysInMonth; day++) {
            long dayStart = DateUtils.dayStartSeconds(year, month, day);
            calendarGrid.addView(calendarDayCell(profile, dayStart, day, workedByDay.get(dayStart)));
        }
    }

    private void refreshWeekCalendar(Profile profile) {
        long from = visibleWeekStartSeconds;
        long to = from + 7L * 24L * 60L * 60L;
        HashMap<Long, Long> workedByDay = db.getWorkedSecondsByDayRange(profile.id, from, to);

        calendarTitleText.setText("\nКалендарь · неделя " + DateUtils.formatDate(from) + " — " + DateUtils.formatDate(to - 1L));
        calendarGrid.removeAllViews();
        calendarGrid.setColumnCount(7);

        addWeekdayHeaders();

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(from * 1000L);
        for (int i = 0; i < 7; i++) {
            long dayStart = DateUtils.startOfDaySeconds(calendar.getTimeInMillis() / 1000L);
            int dayNumber = calendar.get(Calendar.DAY_OF_MONTH);
            calendarGrid.addView(calendarDayCell(profile, dayStart, dayNumber, workedByDay.get(dayStart)));
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private void addWeekdayHeaders() {
        String[] weekDays = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
        for (String dayName : weekDays) {
            calendarGrid.addView(calendarCell(dayName, true));
        }
    }


    private TextView calendarEmptyCell() {
        TextView cell = calendarCell("", false);
        cell.setBackground(emptyDayBackground());
        cell.setOnTouchListener(this::handleCalendarSwipeTouch);
        return cell;
    }

    private TextView calendarDayCell(Profile profile, long dayStart, int dayNumber, Long seconds) {
        boolean hasWork = seconds != null && seconds > 0L;
        boolean isDeadline = profile.hasDeadlineGoal()
                && DateUtils.startOfDaySeconds(profile.deadlineSeconds) == dayStart;
        boolean isToday = DateUtils.todayStartSeconds() == dayStart;

        String text = String.valueOf(dayNumber);
        if (isToday) {
            text += "\nсегодня";
        }
        if (hasWork) {
            text += "\n" + formatHours(NativeBridge.secondsToHours(seconds)) + " ч";
        }
        if (isDeadline) {
            text += "\n⚑ дедлайн";
        }

        TextView cell = calendarCell(text, false);
        cell.setOnTouchListener(this::handleCalendarSwipeTouch);
        cell.setOnClickListener(v -> {
            if (ignoreNextCalendarClick) {
                ignoreNextCalendarClick = false;
                return;
            }
            showDayDialog(profile, dayStart);
        });
        if (hasWork || isDeadline || isToday) {
            cell.setTypeface(Typeface.DEFAULT_BOLD);
        }
        if (isDeadline) {
            cell.setBackground(deadlineDayBackground());
        } else if (hasWork) {
            cell.setBackground(dayWithWorkBackground());
        } else if (isToday) {
            cell.setBackground(todayBackground());
        } else {
            cell.setBackground(emptyDayBackground());
        }
        return cell;
    }

    private boolean handleCalendarSwipeTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                calendarTouchStartX = event.getRawX();
                calendarTouchStartY = event.getRawY();
                calendarTouchStartTime = System.currentTimeMillis();
                return view == calendarGrid;
            case MotionEvent.ACTION_UP:
                float deltaX = event.getRawX() - calendarTouchStartX;
                float deltaY = event.getRawY() - calendarTouchStartY;
                long duration = System.currentTimeMillis() - calendarTouchStartTime;

                if (Math.abs(deltaX) >= dp(70)
                        && Math.abs(deltaX) > Math.abs(deltaY) * 1.4f
                        && duration < 900L) {
                    ignoreNextCalendarClick = true;
                    moveCalendar(deltaX < 0 ? 1 : -1);
                    return true;
                }
                return view == calendarGrid;
            default:
                return false;
        }
    }

    private void moveCalendar(int direction) {
        if (calendarMonthMode) {
            Calendar calendar = Calendar.getInstance();
            calendar.clear();
            calendar.set(Calendar.YEAR, visibleYear);
            calendar.set(Calendar.MONTH, visibleMonth - 1);
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            calendar.add(Calendar.MONTH, direction);
            visibleYear = calendar.get(Calendar.YEAR);
            visibleMonth = calendar.get(Calendar.MONTH) + 1;
        } else {
            visibleWeekStartSeconds += direction * 7L * 24L * 60L * 60L;
        }
        refreshEverything();
    }

    private void resetCalendarToToday() {
        visibleYear = DateUtils.currentYear();
        visibleMonth = DateUtils.currentMonth();
        visibleWeekStartSeconds = DateUtils.weekStartSeconds();
        refreshEverything();
    }

    private void toggleCalendarMode() {
        calendarMonthMode = !calendarMonthMode;
        if (calendarMonthMode) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(visibleWeekStartSeconds * 1000L);
            visibleYear = calendar.get(Calendar.YEAR);
            visibleMonth = calendar.get(Calendar.MONTH) + 1;
        } else {
            Calendar calendar = Calendar.getInstance();
            calendar.clear();
            calendar.set(Calendar.YEAR, visibleYear);
            calendar.set(Calendar.MONTH, visibleMonth - 1);
            calendar.set(Calendar.DAY_OF_MONTH, Math.min(Calendar.getInstance().get(Calendar.DAY_OF_MONTH), DateUtils.daysInMonth(visibleYear, visibleMonth)));
            visibleWeekStartSeconds = DateUtils.weekStartSecondsFor(calendar.getTimeInMillis() / 1000L);
        }
        refreshEverything();
    }

    private TextView calendarCell(String text, boolean header) {
        TextView cell = new TextView(this);
        cell.setText(text);
        cell.setTextSize(header ? 13 : 12);
        cell.setGravity(Gravity.CENTER);
        cell.setMinHeight(dp(52));
        cell.setTextColor(Color.rgb(70, 70, 70));
        cell.setPadding(dp(2), dp(4), dp(2), dp(4));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = getResources().getDisplayMetrics().widthPixels / 7 - dp(7);
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.setMargins(dp(2), dp(3), dp(2), dp(3));
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
        boolean workSchedule = profile.useWorkSchedule && profile.hasRegularGoal();
        int daysInPeriod;
        int daysPassed;
        int daysLeftAfterToday;
        int daysForWork;
        double targetHours;
        double expectedHours;

        if (workSchedule) {
            daysInPeriod = DateUtils.countWorkdaysInRange(from, toExclusive, profile.workDaysMask);
            daysPassed = DateUtils.elapsedWorkdaysSmart(from, now, toExclusive, todayHasWork, profile.workDaysMask);
            daysForWork = DateUtils.availableWorkdaysSmart(now, toExclusive, todayHasWork, profile.workDaysMask);
            daysLeftAfterToday = Math.max(0, daysInPeriod - daysPassed);
            targetHours = daysInPeriod * profile.workHoursPerDay;
            expectedHours = daysPassed * profile.workHoursPerDay;
        } else {
            daysInPeriod = DateUtils.periodDays(from, toExclusive);
            daysPassed = DateUtils.elapsedPeriodDaysSmart(from, now, toExclusive, todayHasWork);
            daysLeftAfterToday = DateUtils.daysLeftAfterTodaySmart(from, now, toExclusive, todayHasWork);
            daysForWork = DateUtils.daysAvailableForWorkSmart(now, toExclusive, todayHasWork);
            targetHours = profile.targetHours;
            expectedHours = NativeBridge.expectedHours(targetHours, daysInPeriod, daysPassed);
        }

        double currentBalance = NativeBridge.balance(workedHours, expectedHours);
        double finalBalance = NativeBridge.balance(workedHours, targetHours);
        double remainingHours = Math.max(0.0, targetHours - workedHours);
        double requiredDaily = NativeBridge.requiredDailyHours(remainingHours, daysForWork);
        double requiredWeekly = requiredDaily * Math.max(1, workSchedule ? DateUtils.countWorkdaysInRange(DateUtils.weekStartSeconds(), DateUtils.nextWeekStartSeconds(), profile.workDaysMask) : 7);

        String main;
        if (remainingHours <= 0.0001) {
            main = "Цель закрыта. Запас: " + formatHours(Math.max(0.0, workedHours - targetHours)) + " ч.  ▾";
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
                        "График: %s\n" +
                        "Расчётные дни: %d из %d\n" +
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
                formatHours(targetHours),
                workSchedule ? "Пн–Пт по " + formatHours(profile.workHoursPerDay) + " ч" : "календарные дни",
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
        if (profile.useWorkSchedule && profile.hasRegularGoal()) {
            return "Пн–Пт · " + formatHours(profile.workHoursPerDay) + " ч/день · " + periodTitle(profile);
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


    private GradientDrawable calendarAreaBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(250, 250, 250));
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), Color.rgb(230, 230, 230));
        return drawable;
    }

    private GradientDrawable emptyDayBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(255, 255, 255));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), Color.rgb(235, 235, 235));
        return drawable;
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

    private GradientDrawable deadlineDayBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(248, 238, 222));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), Color.rgb(190, 145, 80));
        return drawable;
    }

    private GradientDrawable todayBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.rgb(238, 242, 248));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), Color.rgb(150, 165, 190));
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
