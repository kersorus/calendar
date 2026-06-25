package com.kersorus.timecalendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class WarehouseDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "warehouse_pay.db";
    private static final int DB_VERSION = 1;

    public WarehouseDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE shifts (" +
                "date_text TEXT PRIMARY KEY, " +
                "is_workday INTEGER NOT NULL DEFAULT 0, " +
                "cancel_count INTEGER NOT NULL DEFAULT 0, " +
                "accept_count INTEGER NOT NULL DEFAULT 0, " +
                "return_count INTEGER NOT NULL DEFAULT 0, " +
                "issue_count INTEGER NOT NULL DEFAULT 0, " +
                "repack_count INTEGER NOT NULL DEFAULT 0" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS shifts");
        onCreate(db);
    }

    public Shift getShift(String dateText) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT date_text, is_workday, cancel_count, accept_count, return_count, issue_count, repack_count " +
                        "FROM shifts WHERE date_text = ?",
                new String[]{dateText}
        );
        Shift shift = null;
        if (c.moveToFirst()) {
            shift = fromCursor(c);
        }
        c.close();
        return shift;
    }

    public void setWorkday(String dateText, boolean workday) {
        Shift shift = getShift(dateText);
        if (shift == null) {
            shift = new Shift(dateText);
        }
        shift.isWorkday = workday;
        saveShift(shift);
    }

    public void saveShift(Shift shift) {
        ContentValues v = new ContentValues();
        v.put("date_text", shift.dateText);
        v.put("is_workday", shift.isWorkday ? 1 : 0);
        v.put("cancel_count", shift.cancelCount);
        v.put("accept_count", shift.acceptCount);
        v.put("return_count", shift.returnCount);
        v.put("issue_count", shift.issueCount);
        v.put("repack_count", shift.repackCount);
        getWritableDatabase().insertWithOnConflict("shifts", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<Shift> getShiftsBetween(String from, String to) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT date_text, is_workday, cancel_count, accept_count, return_count, issue_count, repack_count " +
                        "FROM shifts WHERE date_text >= ? AND date_text <= ? ORDER BY date_text",
                new String[]{from, to}
        );
        List<Shift> result = new ArrayList<>();
        while (c.moveToNext()) {
            result.add(fromCursor(c));
        }
        c.close();
        return result;
    }

    private Shift fromCursor(Cursor c) {
        Shift s = new Shift(c.getString(0));
        s.isWorkday = c.getInt(1) == 1;
        s.cancelCount = c.getInt(2);
        s.acceptCount = c.getInt(3);
        s.returnCount = c.getInt(4);
        s.issueCount = c.getInt(5);
        s.repackCount = c.getInt(6);
        return s;
    }

    public static class Shift {
        public final String dateText;
        public boolean isWorkday;
        public int cancelCount;
        public int acceptCount;
        public int returnCount;
        public int issueCount;
        public int repackCount;

        public Shift(String dateText) {
            this.dateText = dateText;
        }

        public int totalPicks() {
            return cancelCount + acceptCount + returnCount + issueCount + repackCount;
        }

        public double picksGross() {
            return 6.1 * (
                    cancelCount * 0.6 +
                            acceptCount * 0.8 +
                            returnCount * 0.9 +
                            issueCount * 1.1 +
                            repackCount * 1.3
            );
        }

        public double shiftGross() {
            return totalPicks() > 0 ? 10.75 * 147.0 : 0.0;
        }

        public double gross() {
            return picksGross() + shiftGross();
        }

        public double net() {
            return gross() * 0.87;
        }
    }
}
