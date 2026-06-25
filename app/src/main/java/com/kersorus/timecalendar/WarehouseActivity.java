package com.kersorus.timecalendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.text.InputType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class WarehouseActivity extends Activity {
    private static final String PREFS = "warehouse_settings";
    private static final String KEY_PATTERN = "pattern";
    private static final String KEY_ANCHOR = "anchor";

    private static final int MODE_NORMAL = 0;
    private static final int MODE_ADD_SHIFT = 1;
    private static final int MODE_REMOVE_SHIFT = 2;

    private final SimpleDateFormat keyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat titleFormat = new SimpleDateFormat("LLLL yyyy", new Locale("ru"));
    private final SimpleDateFormat humanFormat = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());

    private WarehouseDatabaseHelper db;
    private SharedPreferences prefs;

    private LinearLayout root;
    private TextView titleText;
    private TextView modeText;
    private GridLayout calendarGrid;
    private TextView paymentsText;

    private Calendar visibleMonth;
    private int editMode = MODE_NORMAL;
    private float touchStartX;
    private float touchStartY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new WarehouseDatabaseHelper(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        visibleMonth = Calendar.getInstance();
        visibleMonth.set(Calendar.DAY_OF_MONTH, 1);
        buildUi();
        refreshAll();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(30), dp(14), dp(8));
        root.setBackgroundColor(Color.rgb(250, 250, 250));
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(46)));

        TextView appTitle = new TextView(this);
        appTitle.setText("Склад Зарплата");
        appTitle.setTextSize(24);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(appTitle, new LinearLayout.LayoutParams(0, -1, 1));

        Button menuButton = compactButton("☰");
        menuButton.setOnClickListener(this::showMainMenu);
        header.addView(menuButton, new LinearLayout.LayoutParams(dp(52), dp(42)));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(46)));

        Button prev = compactButton("‹");
        prev.setOnClickListener(v -> moveMonth(-1));
        nav.addView(prev, new LinearLayout.LayoutParams(dp(44), dp(40)));

        titleText = new TextView(this);
        titleText.setGravity(Gravity.CENTER);
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.setTextSize(18);
        titleText.setOnClickListener(v -> pickCalendarMonth());
        nav.addView(titleText, new LinearLayout.LayoutParams(0, -1, 1));

        Button today = compactButton("●");
        today.setOnClickListener(v -> goToday());
        nav.addView(today, new LinearLayout.LayoutParams(dp(44), dp(40)));

        Button next = compactButton("›");
        next.setOnClickListener(v -> moveMonth(1));
        nav.addView(next, new LinearLayout.LayoutParams(dp(44), dp(40)));

        Button schedule = new Button(this);
        schedule.setText("График");
        schedule.setAllCaps(false);
        schedule.setOnClickListener(this::showScheduleMenu);
        root.addView(schedule, new LinearLayout.LayoutParams(-1, dp(44)));

        modeText = new TextView(this);
        modeText.setGravity(Gravity.CENTER);
        modeText.setTextSize(14);
        root.addView(modeText, new LinearLayout.LayoutParams(-1, dp(26)));

        calendarGrid = new GridLayout(this);
        calendarGrid.setColumnCount(7);
        calendarGrid.setPadding(0, 0, 0, 0);
        calendarGrid.setOnTouchListener((v, event) -> handleCalendarTouch(event));
        root.addView(calendarGrid, new LinearLayout.LayoutParams(-1, 0, 1));

        paymentsText = new TextView(this);
        paymentsText.setTextSize(14);
        paymentsText.setPadding(dp(8), dp(6), dp(8), dp(6));
        paymentsText.setBackgroundColor(Color.rgb(238, 238, 238));
        paymentsText.setOnClickListener(v -> showPaymentsList());
        root.addView(paymentsText, new LinearLayout.LayoutParams(-1, dp(104)));
    }

    private Button compactButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private boolean handleCalendarTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                if (Math.abs(dx) > dp(70) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    moveMonth(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            default:
                return true;
        }
    }

    private void showMainMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Все выплаты");
        menu.getMenu().add("Настройки");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Все выплаты")) {
                showPaymentsList();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Настройки")
                        .setMessage("Пока пусто.")
                        .setPositiveButton("OK", null)
                        .show();
            }
            return true;
        });
        menu.show();
    }

    private void showScheduleMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Выбрать график");
        menu.getMenu().add("Добавить смену");
        menu.getMenu().add("Убрать смену");
        menu.setOnMenuItemClickListener(item -> {
            String title = item.getTitle().toString();
            if (title.equals("Выбрать график")) {
                chooseSchedulePattern();
            } else if (title.equals("Добавить смену")) {
                editMode = MODE_ADD_SHIFT;
                refreshAll();
            } else if (title.equals("Убрать смену")) {
                editMode = MODE_REMOVE_SHIFT;
                refreshAll();
            }
            return true;
        });
        menu.show();
    }

    private void chooseSchedulePattern() {
        String[] patterns = new String[]{"2/2", "3/3", "5/2"};
        new AlertDialog.Builder(this)
                .setTitle("Выберите график")
                .setItems(patterns, (dialog, which) -> pickAnchorDate(patterns[which]))
                .show();
    }

    private void pickAnchorDate(String pattern) {
        Calendar now = Calendar.getInstance();
        DatePickerDialog d = new DatePickerDialog(
                this,
                (DatePicker view, int year, int month, int dayOfMonth) -> {
                    Calendar c = Calendar.getInstance();
                    c.set(year, month, dayOfMonth, 0, 0, 0);
                    c.set(Calendar.MILLISECOND, 0);
                    prefs.edit()
                            .putString(KEY_PATTERN, pattern)
                            .putString(KEY_ANCHOR, keyFormat.format(c.getTime()))
                            .apply();
                    applyScheduleForVisibleMonth();
                    refreshAll();
                },
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH)
        );
        d.setTitle("Дата любого рабочего дня");
        d.show();
    }

    private void pickCalendarMonth() {
        DatePickerDialog d = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    visibleMonth.set(Calendar.YEAR, year);
                    visibleMonth.set(Calendar.MONTH, month);
                    visibleMonth.set(Calendar.DAY_OF_MONTH, 1);
                    refreshAll();
                },
                visibleMonth.get(Calendar.YEAR),
                visibleMonth.get(Calendar.MONTH),
                visibleMonth.get(Calendar.DAY_OF_MONTH)
        );
        d.setTitle("Перейти к месяцу");
        d.show();
    }

    private void applyScheduleForVisibleMonth() {
        String pattern = prefs.getString(KEY_PATTERN, "");
        String anchorText = prefs.getString(KEY_ANCHOR, "");
        if (pattern.length() == 0 || anchorText.length() == 0) {
            return;
        }
        Calendar start = (Calendar) visibleMonth.clone();
        start.set(Calendar.DAY_OF_MONTH, 1);
        Calendar end = (Calendar) start.clone();
        end.set(Calendar.DAY_OF_MONTH, end.getActualMaximum(Calendar.DAY_OF_MONTH));
        Calendar cursor = (Calendar) start.clone();
        while (!cursor.after(end)) {
            if (isScheduledWorkday(cursor, pattern, anchorText)) {
                db.setWorkday(keyFormat.format(cursor.getTime()), true);
            }
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private boolean isScheduledWorkday(Calendar date, String pattern, String anchorText) {
        try {
            Calendar anchor = Calendar.getInstance();
            anchor.setTime(keyFormat.parse(anchorText));
            long days = daysBetween(anchor, date);
            if (pattern.equals("5/2")) {
                int dow = date.get(Calendar.DAY_OF_WEEK);
                return dow != Calendar.SATURDAY && dow != Calendar.SUNDAY;
            }
            int work = pattern.equals("3/3") ? 3 : 2;
            int rest = pattern.equals("3/3") ? 3 : 2;
            int cycle = work + rest;
            long mod = ((days % cycle) + cycle) % cycle;
            return mod < work;
        } catch (Exception e) {
            return false;
        }
    }

    private long daysBetween(Calendar a, Calendar b) {
        Calendar ca = (Calendar) a.clone();
        Calendar cb = (Calendar) b.clone();
        ca.set(Calendar.HOUR_OF_DAY, 0); ca.set(Calendar.MINUTE, 0); ca.set(Calendar.SECOND, 0); ca.set(Calendar.MILLISECOND, 0);
        cb.set(Calendar.HOUR_OF_DAY, 0); cb.set(Calendar.MINUTE, 0); cb.set(Calendar.SECOND, 0); cb.set(Calendar.MILLISECOND, 0);
        return (cb.getTimeInMillis() - ca.getTimeInMillis()) / 86400000L;
    }

    private void refreshAll() {
        titleText.setText(capitalize(titleFormat.format(visibleMonth.getTime())));
        if (editMode == MODE_ADD_SHIFT) {
            modeText.setText("Режим: нажмите дату, чтобы добавить смену");
            calendarGrid.setBackgroundColor(Color.rgb(120, 170, 220));
            calendarGrid.setPadding(dp(2), dp(2), dp(2), dp(2));
        } else if (editMode == MODE_REMOVE_SHIFT) {
            modeText.setText("Режим: нажмите дату, чтобы убрать смену");
            calendarGrid.setBackgroundColor(Color.rgb(220, 150, 150));
            calendarGrid.setPadding(dp(2), dp(2), dp(2), dp(2));
        } else {
            modeText.setText("Нажмите дату смены, чтобы внести пики");
            calendarGrid.setBackgroundColor(Color.TRANSPARENT);
            calendarGrid.setPadding(0, 0, 0, 0);
        }
        renderCalendar();
        renderPaymentsSummary();
    }

    private void renderCalendar() {
        calendarGrid.removeAllViews();
        String[] week = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
        for (String w : week) {
            TextView tv = cellText(w, true);
            calendarGrid.addView(tv, cellParams());
        }

        Calendar first = (Calendar) visibleMonth.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int firstDay = first.get(Calendar.DAY_OF_WEEK);
        int offset = firstDay == Calendar.SUNDAY ? 6 : firstDay - Calendar.MONDAY;
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        int totalCells = 42;

        for (int i = 0; i < totalCells; i++) {
            int day = i - offset + 1;
            TextView cell = new TextView(this);
            cell.setGravity(Gravity.CENTER);
            cell.setTextSize(11);
            cell.setPadding(1, 1, 1, 1);
            cell.setBackgroundColor(Color.rgb(245, 245, 245));

            if (day >= 1 && day <= daysInMonth) {
                Calendar date = (Calendar) visibleMonth.clone();
                date.set(Calendar.DAY_OF_MONTH, day);
                String key = keyFormat.format(date.getTime());
                WarehouseDatabaseHelper.Shift shift = db.getShift(key);
                boolean isWorkday = shift != null && shift.isWorkday;
                double net = shift == null ? 0.0 : shift.net();
                cell.setText(day + (net > 0.0 ? "\n" + Math.round(net) + "₽" : ""));
                cell.setTextColor(Color.rgb(40, 40, 40));
                if (isWorkday) {
                    cell.setBackgroundColor(colorForPayPeriod(date));
                }
                if (net >= 5000.0) {
                    cell.setBackgroundColor(Color.rgb(180, 230, 185));
                    cell.setTypeface(Typeface.DEFAULT_BOLD);
                }
                Calendar today = Calendar.getInstance();
                if (sameDay(today, date)) {
                    cell.setTypeface(Typeface.DEFAULT_BOLD);
                    cell.setText("•" + cell.getText());
                }
                cell.setOnClickListener(v -> onDateClick(key, date));
            } else {
                cell.setText("");
                cell.setBackgroundColor(Color.TRANSPARENT);
            }
            calendarGrid.addView(cell, cellParams());
        }
    }

    private TextView cellText(String text, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(12);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private GridLayout.LayoutParams cellParams() {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams();
        p.width = 0;
        p.height = 0;
        p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        p.setMargins(1, 1, 1, 1);
        return p;
    }

    private int colorForPayPeriod(Calendar date) {
        int month = date.get(Calendar.MONTH);
        int day = date.get(Calendar.DAY_OF_MONTH);
        boolean firstHalf = day <= 15;
        if (month % 2 == 0) {
            return firstHalf ? Color.rgb(225, 235, 255) : Color.rgb(255, 235, 215);
        } else {
            return firstHalf ? Color.rgb(235, 250, 225) : Color.rgb(245, 225, 255);
        }
    }

    private void onDateClick(String key, Calendar date) {
        if (editMode == MODE_ADD_SHIFT) {
            db.setWorkday(key, true);
            editMode = MODE_NORMAL;
            refreshAll();
            return;
        }
        if (editMode == MODE_REMOVE_SHIFT) {
            db.setWorkday(key, false);
            editMode = MODE_NORMAL;
            refreshAll();
            return;
        }
        WarehouseDatabaseHelper.Shift shift = db.getShift(key);
        if (shift == null || !shift.isWorkday) {
            new AlertDialog.Builder(this)
                    .setTitle(humanFormat.format(date.getTime()))
                    .setMessage("Сначала добавьте смену через меню «График».")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        showShiftDialog(shift, date);
    }

    private void showShiftDialog(WarehouseDatabaseHelper.Shift shift, Calendar date) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);

        EditText cancel = numberInput(shift.cancelCount, "Отмены, вес 0.6");
        EditText accept = numberInput(shift.acceptCount, "Приемка, вес 0.8");
        EditText returns = numberInput(shift.returnCount, "Возвраты, вес 0.9");
        EditText issue = numberInput(shift.issueCount, "Выдача / отказы / оплата, вес 1.1");
        EditText repack = numberInput(shift.repackCount, "Переупаковка, вес 1.3");
        box.addView(cancel);
        box.addView(accept);
        box.addView(returns);
        box.addView(issue);
        box.addView(repack);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(humanFormat.format(date.getTime()))
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            shift.cancelCount = parseInt(cancel);
            shift.acceptCount = parseInt(accept);
            shift.returnCount = parseInt(returns);
            shift.issueCount = parseInt(issue);
            shift.repackCount = parseInt(repack);
            db.saveShift(shift);
            dialog.dismiss();
            refreshAll();
        }));
        dialog.show();
    }

    private EditText numberInput(int value, String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == 0 ? "" : String.valueOf(value));
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private int parseInt(EditText e) {
        try {
            String t = e.getText().toString().trim();
            if (t.length() == 0) return 0;
            return Math.max(0, Integer.parseInt(t));
        } catch (Exception ex) {
            return 0;
        }
    }

    private void renderPaymentsSummary() {
        List<PayPeriod> list = buildPayPeriodsAroundToday();
        StringBuilder b = new StringBuilder();
        b.append("Выплаты\n");
        for (PayPeriod p : list) {
            double total = netForPeriod(p.fromKey, p.toKey);
            b.append(p.label).append(" — ").append(Math.round(total)).append("₽\n");
        }
        b.append("Нажмите, чтобы открыть полный список.");
        paymentsText.setText(b.toString());
    }

    private void showPaymentsList() {
        Calendar start = Calendar.getInstance();
        start.add(Calendar.MONTH, -6);
        Calendar end = Calendar.getInstance();
        end.add(Calendar.MONTH, 6);
        List<PayPeriod> all = buildPayPeriods(start, end);
        StringBuilder b = new StringBuilder();
        for (PayPeriod p : all) {
            b.append(p.label)
                    .append("\nПериод: ")
                    .append(p.fromHuman)
                    .append(" — ")
                    .append(p.toHuman)
                    .append("\nК выплате: ")
                    .append(Math.round(netForPeriod(p.fromKey, p.toKey)))
                    .append("₽\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Все выплаты")
                .setMessage(b.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private List<PayPeriod> buildPayPeriodsAroundToday() {
        List<PayPeriod> all = buildPayPeriods(addMonths(Calendar.getInstance(), -3), addMonths(Calendar.getInstance(), 3));
        long now = System.currentTimeMillis();
        int nearest = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).payDate.getTimeInMillis() >= now) {
                nearest = i;
                break;
            }
        }
        List<PayPeriod> result = new ArrayList<>();
        if (nearest > 0) result.add(all.get(nearest - 1));
        result.add(all.get(nearest));
        if (nearest + 1 < all.size()) result.add(all.get(nearest + 1));
        return result;
    }

    private List<PayPeriod> buildPayPeriods(Calendar from, Calendar to) {
        List<PayPeriod> result = new ArrayList<>();
        Calendar c = (Calendar) from.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        while (!c.after(to)) {
            Calendar prev = (Calendar) c.clone();
            prev.add(Calendar.MONTH, -1);
            int prevLast = prev.getActualMaximum(Calendar.DAY_OF_MONTH);
            Calendar pay10 = (Calendar) c.clone(); pay10.set(Calendar.DAY_OF_MONTH, 10);
            Calendar pay25 = (Calendar) c.clone(); pay25.set(Calendar.DAY_OF_MONTH, 25);
            result.add(new PayPeriod(pay10, prev, 1, 15));
            result.add(new PayPeriod(pay25, prev, 16, prevLast));
            c.add(Calendar.MONTH, 1);
        }
        return result;
    }

    private double netForPeriod(String fromKey, String toKey) {
        double total = 0.0;
        for (WarehouseDatabaseHelper.Shift s : db.getShiftsBetween(fromKey, toKey)) {
            total += s.net();
        }
        return total;
    }

    private class PayPeriod {
        final Calendar payDate;
        final String fromKey;
        final String toKey;
        final String fromHuman;
        final String toHuman;
        final String label;

        PayPeriod(Calendar payDate, Calendar workMonth, int fromDay, int toDay) {
            this.payDate = payDate;
            Calendar from = (Calendar) workMonth.clone(); from.set(Calendar.DAY_OF_MONTH, fromDay);
            Calendar to = (Calendar) workMonth.clone(); to.set(Calendar.DAY_OF_MONTH, toDay);
            fromKey = keyFormat.format(from.getTime());
            toKey = keyFormat.format(to.getTime());
            fromHuman = humanFormat.format(from.getTime());
            toHuman = humanFormat.format(to.getTime());
            label = humanFormat.format(payDate.getTime());
        }
    }

    private void moveMonth(int delta) {
        visibleMonth.add(Calendar.MONTH, delta);
        visibleMonth.set(Calendar.DAY_OF_MONTH, 1);
        applyScheduleForVisibleMonth();
        refreshAll();
    }

    private void goToday() {
        visibleMonth = Calendar.getInstance();
        visibleMonth.set(Calendar.DAY_OF_MONTH, 1);
        applyScheduleForVisibleMonth();
        refreshAll();
    }

    private Calendar addMonths(Calendar base, int months) {
        Calendar c = (Calendar) base.clone();
        c.add(Calendar.MONTH, months);
        return c;
    }

    private boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private String capitalize(String s) {
        if (s == null || s.length() == 0) return "";
        return s.substring(0, 1).toUpperCase(new Locale("ru")) + s.substring(1);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
