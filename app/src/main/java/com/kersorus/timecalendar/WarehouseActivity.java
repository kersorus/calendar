package com.kersorus.timecalendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class WarehouseActivity extends Activity {
    private static final String PREFS = "warehouse_settings";

    private static final String KEY_PATTERN = "pattern";
    private static final String KEY_ANCHOR = "anchor";
    private static final String KEY_BACKGROUND_URI = "background_uri";
    private static final String KEY_BASE_PICK_PRICE = "base_pick_price";
    private static final String KEY_SHIFT_HOURS = "shift_hours";
    private static final String KEY_HOURLY_RATE = "hourly_rate";
    private static final String KEY_TAX_PERCENT = "tax_percent";

    private static final int MODE_NORMAL = 0;
    private static final int MODE_ADD_SHIFT = 1;
    private static final int MODE_REMOVE_SHIFT = 2;

    private static final int REQUEST_BACKGROUND = 2001;
    private static final int REQUEST_BACKUP = 2002;
    private static final int REQUEST_RESTORE = 2003;

    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HUMAN_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("ru"));

    private static final int COLOR_BLUE = Color.rgb(66, 112, 168);
    private static final int COLOR_ORANGE = Color.rgb(205, 105, 55);
    private static final int COLOR_GREEN_PERIOD = Color.rgb(76, 140, 84);
    private static final int COLOR_PURPLE = Color.rgb(127, 86, 150);
    private static final int COLOR_GOOD = Color.rgb(27, 125, 64);

    private SharedPreferences prefs;
    private WarehouseDatabaseHelper db;

    private YearMonth visibleMonth = YearMonth.now();
    private int editMode = MODE_NORMAL;

    private FrameLayout outerRoot;
    private ImageView backgroundImage;
    private LinearLayout contentRoot;
    private TextView titleText;
    private TextView modeText;
    private GridLayout calendarGrid;
    private LinearLayout paymentsContainer;

    private float touchDownX;
    private float touchDownY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(248, 248, 248));
        window.setNavigationBarColor(Color.rgb(248, 248, 248));
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        db = new WarehouseDatabaseHelper(this);

        buildUi();
        loadBackground();
        refreshAll();
    }

    private void buildUi() {
        outerRoot = new FrameLayout(this);
        outerRoot.setFitsSystemWindows(true);

        backgroundImage = new ImageView(this);
        backgroundImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backgroundImage.setVisibility(View.GONE);
        outerRoot.addView(backgroundImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        contentRoot.setPadding(dp(10), dp(8), dp(10), dp(8));
        contentRoot.setBackgroundColor(Color.argb(224, 250, 250, 250));
        outerRoot.addView(contentRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        contentRoot.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        ));

        TextView appTitle = new TextView(this);
        appTitle.setText("Зарплата");
        appTitle.setTextSize(24);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(appTitle, new LinearLayout.LayoutParams(0, -1, 1));

        Button menu = compactButton("☰");
        menu.setContentDescription("Меню");
        menu.setOnClickListener(this::showMainMenu);
        top.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(42)));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        contentRoot.addView(navigation, new LinearLayout.LayoutParams(-1, dp(42)));

        Button previous = compactButton("‹");
        previous.setOnClickListener(v -> moveMonth(-1));
        navigation.addView(previous, new LinearLayout.LayoutParams(dp(44), -1));

        titleText = new TextView(this);
        titleText.setGravity(Gravity.CENTER);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setTextSize(18);
        titleText.setOnClickListener(v -> pickCalendarMonth());
        navigation.addView(titleText, new LinearLayout.LayoutParams(0, -1, 1));

        Button today = compactButton("●");
        today.setContentDescription("Сегодня");
        today.setOnClickListener(v -> {
            visibleMonth = YearMonth.now();
            refreshAll();
        });
        navigation.addView(today, new LinearLayout.LayoutParams(dp(44), -1));

        Button next = compactButton("›");
        next.setOnClickListener(v -> moveMonth(1));
        navigation.addView(next, new LinearLayout.LayoutParams(dp(44), -1));

        Button schedule = new Button(this);
        schedule.setText("График");
        schedule.setAllCaps(false);
        schedule.setOnClickListener(this::showScheduleMenu);
        contentRoot.addView(schedule, new LinearLayout.LayoutParams(-1, dp(44)));

        modeText = new TextView(this);
        modeText.setGravity(Gravity.CENTER);
        modeText.setTextSize(13);
        contentRoot.addView(modeText, new LinearLayout.LayoutParams(-1, dp(24)));

        calendarGrid = new GridLayout(this);
        calendarGrid.setColumnCount(7);
        calendarGrid.setPadding(0, 0, 0, 0);
        calendarGrid.setOnTouchListener((view, event) -> handleCalendarTouch(event));
        contentRoot.addView(calendarGrid, new LinearLayout.LayoutParams(-1, 0, 1));

        paymentsContainer = new LinearLayout(this);
        paymentsContainer.setOrientation(LinearLayout.HORIZONTAL);
        paymentsContainer.setPadding(0, dp(4), 0, 0);
        contentRoot.addView(paymentsContainer, new LinearLayout.LayoutParams(-1, dp(112)));

        setContentView(outerRoot);
    }

    private Button compactButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(20);
        button.setAllCaps(false);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private void showMainMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Настройки");
        popup.getMenu().add("Все выплаты");
        popup.getMenu().add("Сохранить резервную копию");
        popup.getMenu().add("Восстановить резервную копию");

        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Настройки".equals(title)) {
                showSettings();
            } else if ("Все выплаты".equals(title)) {
                showPaymentsList();
            } else if ("Сохранить резервную копию".equals(title)) {
                createBackup();
            } else if ("Восстановить резервную копию".equals(title)) {
                restoreBackup();
            }
            return true;
        });
        popup.show();
    }

    private void showSettings() {
        String[] options = {
                "Фоновая фотография",
                "Убрать фон",
                "Параметры расчёта"
        };

        new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        chooseBackground();
                    } else if (which == 1) {
                        prefs.edit().remove(KEY_BACKGROUND_URI).apply();
                        loadBackground();
                    } else {
                        showCalculationSettings();
                    }
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void showCalculationSettings() {
        LinearLayout box = verticalDialogBox();

        EditText basePrice = decimalInput(
                getBasePickPrice(),
                "Базовая стоимость пика, ₽"
        );
        EditText shiftHours = decimalInput(
                getShiftHours(),
                "Часов в смене"
        );
        EditText hourlyRate = decimalInput(
                getHourlyRate(),
                "Ставка в час, ₽"
        );
        EditText tax = decimalInput(
                getTaxPercent(),
                "Налог, %"
        );

        box.addView(labeledInput("Базовая стоимость пика", basePrice));
        box.addView(labeledInput("Оплачиваемые часы смены", shiftHours));
        box.addView(labeledInput("Почасовая ставка", hourlyRate));
        box.addView(labeledInput("Налог", tax));

        new AlertDialog.Builder(this)
                .setTitle("Параметры расчёта")
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    prefs.edit()
                            .putFloat(KEY_BASE_PICK_PRICE, (float) parseDouble(basePrice, 6.1))
                            .putFloat(KEY_SHIFT_HOURS, (float) parseDouble(shiftHours, 10.75))
                            .putFloat(KEY_HOURLY_RATE, (float) parseDouble(hourlyRate, 147.0))
                            .putFloat(KEY_TAX_PERCENT, (float) parseDouble(tax, 13.0))
                            .apply();
                    refreshAll();
                })
                .show();
    }

    private LinearLayout labeledInput(String label, EditText input) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(4), 0, dp(4));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(13);
        container.addView(text);
        container.addView(input);
        return container;
    }

    private void chooseBackground() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_BACKGROUND);
    }

    private void loadBackground() {
        String uriText = prefs.getString(KEY_BACKGROUND_URI, "");
        if (uriText == null || uriText.isEmpty()) {
            backgroundImage.setImageDrawable(null);
            backgroundImage.setVisibility(View.GONE);
            contentRoot.setBackgroundColor(Color.rgb(250, 250, 250));
            return;
        }

        try {
            backgroundImage.setImageURI(Uri.parse(uriText));
            backgroundImage.setVisibility(View.VISIBLE);
            contentRoot.setBackgroundColor(Color.argb(218, 250, 250, 250));
        } catch (Exception error) {
            prefs.edit().remove(KEY_BACKGROUND_URI).apply();
            backgroundImage.setVisibility(View.GONE);
            contentRoot.setBackgroundColor(Color.rgb(250, 250, 250));
        }
    }

    private void createBackup() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "salary-backup.db");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_BACKUP);
    }

    private void restoreBackup() {
        new AlertDialog.Builder(this)
                .setTitle("Восстановление")
                .setMessage("Текущие данные будут заменены выбранной резервной копией.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Продолжить", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQUEST_RESTORE);
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();

        if (requestCode == REQUEST_BACKGROUND) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Exception ignored) {
            }
            prefs.edit().putString(KEY_BACKGROUND_URI, uri.toString()).apply();
            loadBackground();
            return;
        }

        if (requestCode == REQUEST_BACKUP) {
            try {
                db.close();
                File source = getDatabasePath(WarehouseDatabaseHelper.DB_NAME);
                copyFileToUri(source, uri);
                db = new WarehouseDatabaseHelper(this);
                Toast.makeText(this, "Резервная копия сохранена", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                db = new WarehouseDatabaseHelper(this);
                showError("Не удалось сохранить резервную копию", error);
            }
            return;
        }

        if (requestCode == REQUEST_RESTORE) {
            try {
                db.close();
                File destination = getDatabasePath(WarehouseDatabaseHelper.DB_NAME);
                copyUriToFile(uri, destination);
                db = new WarehouseDatabaseHelper(this);
                refreshAll();
                Toast.makeText(this, "Данные восстановлены", Toast.LENGTH_SHORT).show();
            } catch (Exception error) {
                db = new WarehouseDatabaseHelper(this);
                showError("Не удалось восстановить данные", error);
            }
        }
    }

    private void copyFileToUri(File source, Uri destination) throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
            if (output == null) {
                throw new IllegalStateException("Не удалось открыть файл назначения");
            }
            copy(input, output);
        }
    }

    private void copyUriToFile(Uri source, File destination) throws Exception {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Не удалось создать каталог базы");
        }

        File temporary = new File(destination.getAbsolutePath() + ".restore");
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(temporary)) {
            if (input == null) {
                throw new IllegalStateException("Не удалось открыть резервную копию");
            }
            copy(input, output);
        }

        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("Не удалось заменить текущую базу");
        }
        if (!temporary.renameTo(destination)) {
            throw new IllegalStateException("Не удалось применить резервную копию");
        }
    }

    private void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        output.flush();
    }

    private void showError(String title, Exception error) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(error.getMessage() == null ? error.toString() : error.getMessage())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showScheduleMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add("Выбрать график");
        popup.getMenu().add("Добавить смену");
        popup.getMenu().add("Убрать смену");

        popup.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if ("Выбрать график".equals(title)) {
                choosePattern();
            } else if ("Добавить смену".equals(title)) {
                editMode = MODE_ADD_SHIFT;
                updateModeText();
            } else if ("Убрать смену".equals(title)) {
                editMode = MODE_REMOVE_SHIFT;
                updateModeText();
            }
            return true;
        });
        popup.show();
    }

    private void choosePattern() {
        String[] patterns = {"2/2", "3/3", "5/2"};

        new AlertDialog.Builder(this)
                .setTitle("Выберите график")
                .setItems(patterns, (dialog, which) -> pickAnchorDate(patterns[which]))
                .show();
    }

    private void pickAnchorDate(String pattern) {
        LocalDate now = LocalDate.now();
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (DatePicker view, int year, int month, int day) -> {
                    LocalDate anchor = LocalDate.of(year, month + 1, day);
                    prefs.edit()
                            .putString(KEY_PATTERN, pattern)
                            .putString(KEY_ANCHOR, anchor.format(KEY_FORMAT))
                            .apply();
                    editMode = MODE_NORMAL;
                    refreshAll();
                },
                now.getYear(),
                now.getMonthValue() - 1,
                now.getDayOfMonth()
        );
        dialog.setTitle("Дата любого рабочего дня");
        dialog.show();
    }

    private void pickCalendarMonth() {
        LocalDate date = visibleMonth.atDay(1);
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, day) -> {
                    visibleMonth = YearMonth.of(year, month + 1);
                    refreshAll();
                },
                date.getYear(),
                date.getMonthValue() - 1,
                1
        );
        dialog.setTitle("Перейти к месяцу");
        dialog.show();
    }

    private boolean handleCalendarTouch(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchDownX = event.getX();
            touchDownY = event.getY();
            return true;
        }

        if (event.getAction() == MotionEvent.ACTION_UP) {
            float deltaX = event.getX() - touchDownX;
            float deltaY = event.getY() - touchDownY;

            if (Math.abs(deltaX) > dp(55) && Math.abs(deltaX) > Math.abs(deltaY)) {
                moveMonth(deltaX < 0 ? 1 : -1);
                return true;
            }
        }
        return true;
    }

    private void moveMonth(int delta) {
        visibleMonth = visibleMonth.plusMonths(delta);
        refreshAll();
    }

    private void refreshAll() {
        String monthName = visibleMonth.getMonth()
                .getDisplayName(TextStyle.FULL_STANDALONE, new Locale("ru"));
        titleText.setText(capitalize(monthName) + " " + visibleMonth.getYear());
        updateModeText();
        renderCalendar();
        renderPaymentsSummary();
    }

    private void updateModeText() {
        if (editMode == MODE_ADD_SHIFT) {
            modeText.setText("Нажмите дату, чтобы добавить смену");
            modeText.setTextColor(COLOR_GREEN_PERIOD);
        } else if (editMode == MODE_REMOVE_SHIFT) {
            modeText.setText("Нажмите дату, чтобы убрать смену");
            modeText.setTextColor(Color.rgb(175, 45, 45));
        } else {
            String pattern = prefs.getString(KEY_PATTERN, "");
            if (pattern == null || pattern.isEmpty()) {
                modeText.setText("График не выбран");
            } else {
                modeText.setText("График " + pattern);
            }
            modeText.setTextColor(Color.DKGRAY);
        }
    }

    private void renderCalendar() {
        calendarGrid.removeAllViews();

        String[] week = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
        for (String name : week) {
            TextView header = new TextView(this);
            header.setText(name);
            header.setGravity(Gravity.CENTER);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setTextSize(12);
            calendarGrid.addView(header, cellParams());
        }

        LocalDate first = visibleMonth.atDay(1);
        int offset = first.getDayOfWeek().getValue() - 1;
        int daysInMonth = visibleMonth.lengthOfMonth();

        for (int index = 0; index < 42; index++) {
            int day = index - offset + 1;
            TextView cell = new TextView(this);
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(11);
            cell.setPadding(dp(1), dp(1), dp(1), dp(1));

            if (day < 1 || day > daysInMonth) {
                cell.setText("");
                cell.setBackgroundColor(Color.TRANSPARENT);
                calendarGrid.addView(cell, cellParams());
                continue;
            }

            LocalDate date = visibleMonth.atDay(day);
            String key = date.format(KEY_FORMAT);
            WarehouseDatabaseHelper.Shift shift = db.getShift(key);
            double net = shift == null ? 0.0 : netForShift(shift);
            boolean workday = isEffectiveWorkday(date, shift);

            cell.setText(day + (net > 0.0 ? "\n" + Math.round(net) + "₽" : ""));
            cell.setTextColor(Color.WHITE);

            int color = workday ? colorForPayPeriod(date) : Color.rgb(218, 218, 218);
            if (!workday) {
                cell.setTextColor(Color.rgb(55, 55, 55));
            }

            if (net >= 5000.0) {
                color = COLOR_GOOD;
                cell.setTypeface(Typeface.DEFAULT_BOLD);
                cell.setTextColor(Color.WHITE);
            }

            GradientDrawable background = roundedBackground(color, dp(1), Color.WHITE, dp(5));
            if (date.equals(LocalDate.now())) {
                background.setStroke(dp(2), Color.rgb(25, 25, 25));
                cell.setTypeface(Typeface.DEFAULT_BOLD);
            }
            cell.setBackground(background);
            cell.setOnClickListener(v -> onDateClick(date));

            calendarGrid.addView(cell, cellParams());
        }
    }

    private GridLayout.LayoutParams cellParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = 0;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(1), dp(1), dp(1), dp(1));
        return params;
    }

    private boolean isEffectiveWorkday(
            LocalDate date,
            WarehouseDatabaseHelper.Shift shift
    ) {
        if (shift != null) {
            if (shift.workdayOverride == 1) {
                return true;
            }
            if (shift.workdayOverride == 0) {
                return false;
            }
            if (shift.totalPicks() > 0) {
                return true;
            }
        }
        return isScheduledWorkday(date);
    }

    private boolean isScheduledWorkday(LocalDate date) {
        String pattern = prefs.getString(KEY_PATTERN, "");
        String anchorText = prefs.getString(KEY_ANCHOR, "");

        if (pattern == null || pattern.isEmpty()
                || anchorText == null || anchorText.isEmpty()) {
            return false;
        }

        LocalDate anchor;
        try {
            anchor = LocalDate.parse(anchorText, KEY_FORMAT);
        } catch (Exception error) {
            return false;
        }

        long difference = ChronoUnit.DAYS.between(anchor, date);

        if ("5/2".equals(pattern)) {
            long cycle = Math.floorMod(difference, 7);
            return cycle < 5;
        }

        int work;
        int rest;
        if ("2/2".equals(pattern)) {
            work = 2;
            rest = 2;
        } else if ("3/3".equals(pattern)) {
            work = 3;
            rest = 3;
        } else {
            return false;
        }

        long cycle = Math.floorMod(difference, work + rest);
        return cycle < work;
    }

    private void onDateClick(LocalDate date) {
        String key = date.format(KEY_FORMAT);

        if (editMode == MODE_ADD_SHIFT) {
            db.setWorkdayOverride(key, 1);
            editMode = MODE_NORMAL;
            refreshAll();
            return;
        }

        if (editMode == MODE_REMOVE_SHIFT) {
            db.setWorkdayOverride(key, 0);
            editMode = MODE_NORMAL;
            refreshAll();
            return;
        }

        WarehouseDatabaseHelper.Shift shift = db.getShift(key);
        if (!isEffectiveWorkday(date, shift)) {
            new AlertDialog.Builder(this)
                    .setTitle(date.format(HUMAN_FORMAT))
                    .setMessage("Сначала добавьте смену через меню «График».")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        if (shift == null) {
            shift = db.getOrCreateShift(key);
            shift.isWorkday = true;
        }

        showShiftDialog(shift, date);
    }

    private void showShiftDialog(
            WarehouseDatabaseHelper.Shift shift,
            LocalDate date
    ) {
        LinearLayout box = verticalDialogBox();

        EditText cancel = numberInput(shift.cancelCount, "Отмены — коэффициент 0.6");
        EditText accept = numberInput(shift.acceptCount, "Приёмка — коэффициент 0.8");
        EditText returns = numberInput(shift.returnCount, "Возвраты — коэффициент 0.9");
        EditText issue = numberInput(shift.issueCount, "Выдача — коэффициент 1.1");
        EditText rejects = numberInput(shift.rejectCount, "Отказы — коэффициент 1.1");
        EditText payments = numberInput(shift.paymentCount, "Оплаты — коэффициент 1.1");
        EditText repack = numberInput(shift.repackCount, "Переупаковка — коэффициент 1.3");

        box.addView(cancel);
        box.addView(accept);
        box.addView(returns);
        box.addView(issue);
        box.addView(rejects);
        box.addView(payments);
        box.addView(repack);

        double currentNet = netForShift(shift);
        TextView current = new TextView(this);
        current.setPadding(0, dp(8), 0, 0);
        current.setTypeface(Typeface.DEFAULT_BOLD);
        current.setText("Сейчас за смену: " + Math.round(currentNet) + " ₽ чистыми");
        box.addView(current);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(date.format(HUMAN_FORMAT))
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    shift.cancelCount = parseInt(cancel);
                    shift.acceptCount = parseInt(accept);
                    shift.returnCount = parseInt(returns);
                    shift.issueCount = parseInt(issue);
                    shift.rejectCount = parseInt(rejects);
                    shift.paymentCount = parseInt(payments);
                    shift.repackCount = parseInt(repack);
                    shift.isWorkday = true;

                    db.saveShift(shift);
                    dialog.dismiss();
                    refreshAll();
                })
        );

        dialog.show();
    }

    private LinearLayout verticalDialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);
        return box;
    }

    private EditText numberInput(int value, String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value == 0 ? "" : String.valueOf(value));
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        return input;
    }

    private EditText decimalInput(double value, String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(String.valueOf(value));
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        return input;
    }

    private int parseInt(EditText input) {
        try {
            String value = input.getText().toString().trim();
            return value.isEmpty() ? 0 : Math.max(0, Integer.parseInt(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double parseDouble(EditText input, double fallback) {
        try {
            String value = input.getText().toString().trim().replace(',', '.');
            return value.isEmpty() ? fallback : Math.max(0.0, Double.parseDouble(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void renderPaymentsSummary() {
        paymentsContainer.removeAllViews();

        List<PayPeriod> periods = upcomingPayPeriods(2);
        for (int i = 0; i < periods.size(); i++) {
            PayPeriod period = periods.get(i);
            TextView card = paymentCard(
                    i == 0 ? "Ближайшая выплата" : "Следующая выплата",
                    period
            );

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1
            );
            if (i == 0) {
                params.setMarginEnd(dp(4));
            } else {
                params.setMarginStart(dp(4));
            }
            paymentsContainer.addView(card, params);
        }
    }

    private TextView paymentCard(String heading, PayPeriod period) {
        long total = Math.round(netForPeriod(period.from, period.to));

        TextView card = new TextView(this);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(7), dp(5), dp(7), dp(5));
        card.setTextSize(13);
        card.setTextColor(Color.rgb(25, 25, 25));
        card.setText(
                heading
                        + "\n"
                        + period.payDate.format(HUMAN_FORMAT)
                        + "\n"
                        + total
                        + " ₽"
                        + "\nза "
                        + period.shortPeriod()
        );
        card.setBackground(
                roundedBackground(
                        Color.argb(55, Color.red(period.color),
                                Color.green(period.color), Color.blue(period.color)),
                        dp(3),
                        period.color,
                        dp(10)
                )
        );
        card.setOnClickListener(v -> showPaymentsList());
        return card;
    }

    private List<PayPeriod> upcomingPayPeriods(int count) {
        LocalDate today = LocalDate.now();
        List<PayPeriod> all = buildPayPeriods(
                YearMonth.from(today).minusMonths(2),
                YearMonth.from(today).plusMonths(5)
        );
        all.sort(Comparator.comparing(period -> period.payDate));

        List<PayPeriod> result = new ArrayList<>();
        for (PayPeriod period : all) {
            if (!period.payDate.isBefore(today)) {
                result.add(period);
                if (result.size() == count) {
                    break;
                }
            }
        }
        return result;
    }

    private void showPaymentsList() {
        YearMonth current = YearMonth.now();
        List<PayPeriod> all = buildPayPeriods(
                current.minusMonths(12),
                current.plusMonths(12)
        );
        all.sort(Comparator.comparing(period -> period.payDate));

        StringBuilder text = new StringBuilder();
        for (PayPeriod period : all) {
            if (period.payDate.isAfter(LocalDate.now().plusMonths(8))) {
                continue;
            }

            text.append(period.payDate.format(HUMAN_FORMAT))
                    .append("\n")
                    .append(period.from.format(DateTimeFormatter.ofPattern("d MMM")))
                    .append(" — ")
                    .append(period.to.format(DateTimeFormatter.ofPattern("d MMM yyyy")))
                    .append("\nК выплате: ")
                    .append(Math.round(netForPeriod(period.from, period.to)))
                    .append(" ₽\n\n");
        }

        new AlertDialog.Builder(this)
                .setTitle("Выплаты")
                .setMessage(text.length() == 0 ? "Выплат пока нет." : text.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private List<PayPeriod> buildPayPeriods(YearMonth from, YearMonth to) {
        List<PayPeriod> result = new ArrayList<>();
        YearMonth month = from;

        while (!month.isAfter(to)) {
            LocalDate firstFrom = month.atDay(1);
            LocalDate firstTo = month.atDay(15);
            LocalDate firstPay = month.atDay(25);
            result.add(new PayPeriod(
                    firstPay,
                    firstFrom,
                    firstTo,
                    colorForPayPeriod(firstFrom)
            ));

            LocalDate secondFrom = month.atDay(16);
            LocalDate secondTo = month.atEndOfMonth();
            LocalDate secondPay = month.plusMonths(1).atDay(10);
            result.add(new PayPeriod(
                    secondPay,
                    secondFrom,
                    secondTo,
                    colorForPayPeriod(secondFrom)
            ));

            month = month.plusMonths(1);
        }

        return result;
    }

    private double netForPeriod(LocalDate from, LocalDate to) {
        double total = 0.0;
        for (WarehouseDatabaseHelper.Shift shift :
                db.getShiftsBetween(from.format(KEY_FORMAT), to.format(KEY_FORMAT))) {
            total += netForShift(shift);
        }
        return total;
    }

    private double netForShift(WarehouseDatabaseHelper.Shift shift) {
        return shift.net(
                getBasePickPrice(),
                getShiftHours(),
                getHourlyRate(),
                getTaxPercent()
        );
    }

    private int colorForPayPeriod(LocalDate date) {
        boolean firstHalf = date.getDayOfMonth() <= 15;
        boolean evenMonth = date.getMonthValue() % 2 == 0;

        if (evenMonth) {
            return firstHalf ? COLOR_BLUE : COLOR_ORANGE;
        }
        return firstHalf ? COLOR_GREEN_PERIOD : COLOR_PURPLE;
    }

    private GradientDrawable roundedBackground(
            int fillColor,
            int strokeWidth,
            int strokeColor,
            int radius
    ) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private double getBasePickPrice() {
        return prefs.getFloat(KEY_BASE_PICK_PRICE, 6.1f);
    }

    private double getShiftHours() {
        return prefs.getFloat(KEY_SHIFT_HOURS, 10.75f);
    }

    private double getHourlyRate() {
        return prefs.getFloat(KEY_HOURLY_RATE, 147.0f);
    }

    private double getTaxPercent() {
        return prefs.getFloat(KEY_TAX_PERCENT, 13.0f);
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.substring(0, 1).toUpperCase(new Locale("ru"))
                + value.substring(1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class PayPeriod {
        final LocalDate payDate;
        final LocalDate from;
        final LocalDate to;
        final int color;

        PayPeriod(LocalDate payDate, LocalDate from, LocalDate to, int color) {
            this.payDate = payDate;
            this.from = from;
            this.to = to;
            this.color = color;
        }

        String shortPeriod() {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM", new Locale("ru"));
            return from.format(formatter) + "–" + to.format(formatter);
        }
    }
}
