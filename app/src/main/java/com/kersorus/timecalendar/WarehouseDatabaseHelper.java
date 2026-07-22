package com.kersorus.timecalendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class WarehouseDatabaseHelper extends SQLiteOpenHelper {
    public static final String DB_NAME = "warehouse_pay.db";
    private static final int DB_VERSION = 2;

    public WarehouseDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE shifts (" +
                        "date_text TEXT PRIMARY KEY, " +
                        "is_workday INTEGER NOT NULL DEFAULT 0, " +
                        "workday_override INTEGER NOT NULL DEFAULT -1, " +
                        "cancel_count INTEGER NOT NULL DEFAULT 0, " +
                        "accept_count INTEGER NOT NULL DEFAULT 0, " +
                        "return_count INTEGER NOT NULL DEFAULT 0, " +
                        "issue_count INTEGER NOT NULL DEFAULT 0, " +
                        "reject_count INTEGER NOT NULL DEFAULT 0, " +
                        "payment_count INTEGER NOT NULL DEFAULT 0, " +
                        "repack_count INTEGER NOT NULL DEFAULT 0" +
                        ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            addColumnIfMissing(db,
                    "ALTER TABLE shifts ADD COLUMN workday_override INTEGER NOT NULL DEFAULT -1");
            addColumnIfMissing(db,
                    "ALTER TABLE shifts ADD COLUMN reject_count INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db,
                    "ALTER TABLE shifts ADD COLUMN payment_count INTEGER NOT NULL DEFAULT 0");
        }
    }

    private void addColumnIfMissing(SQLiteDatabase db, String sql) {
        try {
            db.execSQL(sql);
        } catch (Exception ignored) {
            // Колонка уже существует.
        }
    }

    public Shift getShift(String dateText) {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT date_text, is_workday, workday_override, " +
                        "cancel_count, accept_count, return_count, issue_count, " +
                        "reject_count, payment_count, repack_count " +
                        "FROM shifts WHERE date_text = ?",
                new String[]{dateText}
        );

        Shift result = null;
        if (cursor.moveToFirst()) {
            result = fromCursor(cursor);
        }
        cursor.close();
        return result;
    }

    public Shift getOrCreateShift(String dateText) {
        Shift shift = getShift(dateText);
        return shift == null ? new Shift(dateText) : shift;
    }

    public void setWorkdayOverride(String dateText, int overrideValue) {
        Shift shift = getOrCreateShift(dateText);
        shift.workdayOverride = overrideValue;
        shift.isWorkday = overrideValue == 1;
        saveShift(shift);
    }

    public void saveShift(Shift shift) {
        ContentValues values = new ContentValues();
        values.put("date_text", shift.dateText);
        values.put("is_workday", shift.isWorkday ? 1 : 0);
        values.put("workday_override", shift.workdayOverride);
        values.put("cancel_count", shift.cancelCount);
        values.put("accept_count", shift.acceptCount);
        values.put("return_count", shift.returnCount);
        values.put("issue_count", shift.issueCount);
        values.put("reject_count", shift.rejectCount);
        values.put("payment_count", shift.paymentCount);
        values.put("repack_count", shift.repackCount);

        getWritableDatabase().insertWithOnConflict(
                "shifts",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    public List<Shift> getShiftsBetween(String from, String to) {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT date_text, is_workday, workday_override, " +
                        "cancel_count, accept_count, return_count, issue_count, " +
                        "reject_count, payment_count, repack_count " +
                        "FROM shifts WHERE date_text >= ? AND date_text <= ? ORDER BY date_text",
                new String[]{from, to}
        );

        List<Shift> result = new ArrayList<>();
        while (cursor.moveToNext()) {
            result.add(fromCursor(cursor));
        }
        cursor.close();
        return result;
    }

    private Shift fromCursor(Cursor cursor) {
        Shift shift = new Shift(cursor.getString(0));
        shift.isWorkday = cursor.getInt(1) == 1;
        shift.workdayOverride = cursor.getInt(2);
        shift.cancelCount = cursor.getInt(3);
        shift.acceptCount = cursor.getInt(4);
        shift.returnCount = cursor.getInt(5);
        shift.issueCount = cursor.getInt(6);
        shift.rejectCount = cursor.getInt(7);
        shift.paymentCount = cursor.getInt(8);
        shift.repackCount = cursor.getInt(9);
        return shift;
    }

    public static class Shift {
        public final String dateText;
        public boolean isWorkday;
        public int workdayOverride = -1;

        public int cancelCount;
        public int acceptCount;
        public int returnCount;
        public int issueCount;
        public int rejectCount;
        public int paymentCount;
        public int repackCount;

        public Shift(String dateText) {
            this.dateText = dateText;
        }

        public int totalPicks() {
            return cancelCount
                    + acceptCount
                    + returnCount
                    + issueCount
                    + rejectCount
                    + paymentCount
                    + repackCount;
        }

        public double picksGross(double basePickPrice) {
            return basePickPrice * (
                    cancelCount * 0.6
                            + acceptCount * 0.8
                            + returnCount * 0.9
                            + issueCount * 1.1
                            + rejectCount * 1.1
                            + paymentCount * 1.1
                            + repackCount * 1.3
            );
        }

        public double shiftGross(double shiftHours, double hourlyRate) {
            return totalPicks() > 0 ? shiftHours * hourlyRate : 0.0;
        }

        public double gross(double basePickPrice, double shiftHours, double hourlyRate) {
            return picksGross(basePickPrice) + shiftGross(shiftHours, hourlyRate);
        }

        public double net(
                double basePickPrice,
                double shiftHours,
                double hourlyRate,
                double taxPercent
        ) {
            double taxMultiplier = 1.0 - Math.max(0.0, Math.min(100.0, taxPercent)) / 100.0;
            return gross(basePickPrice, shiftHours, hourlyRate) * taxMultiplier;
        }
    }
}
