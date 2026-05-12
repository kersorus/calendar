package com.kersoruss.timecalendar;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "time_calendar.db";
    private static final int DB_VERSION = 1;

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE sessions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "profile TEXT NOT NULL, " +
                        "start_time INTEGER NOT NULL, " +
                        "end_time INTEGER NOT NULL, " +
                        "paused_seconds INTEGER NOT NULL, " +
                        "worked_seconds INTEGER NOT NULL" +
                        ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS sessions");
        onCreate(db);
    }

    public void addSession(
            String profile,
            long startTime,
            long endTime,
            long pausedSeconds,
            long workedSeconds
    ) {
        ContentValues values = new ContentValues();
        values.put("profile", profile);
        values.put("start_time", startTime);
        values.put("end_time", endTime);
        values.put("paused_seconds", pausedSeconds);
        values.put("worked_seconds", workedSeconds);

        SQLiteDatabase db = getWritableDatabase();
        db.insert("sessions", null, values);
    }

    public long getWorkedSecondsForMonth(int year, int month) {
        long from = DateUtils.monthStartSeconds(year, month);
        long to = DateUtils.nextMonthStartSeconds(year, month);

        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT COALESCE(SUM(worked_seconds), 0) FROM sessions " +
                        "WHERE start_time >= ? AND start_time < ?",
                new String[]{String.valueOf(from), String.valueOf(to)}
        );

        long result = 0;
        if (cursor.moveToFirst()) {
            result = cursor.getLong(0);
        }
        cursor.close();

        return result;
    }

    public String getLastSessionsText(int limit) {
        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT start_time, end_time, worked_seconds FROM sessions " +
                        "ORDER BY start_time DESC LIMIT ?",
                new String[]{String.valueOf(limit)}
        );

        StringBuilder builder = new StringBuilder();
        SimpleDateFormat dateFormat =
                new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());

        while (cursor.moveToNext()) {
            long start = cursor.getLong(0);
            long end = cursor.getLong(1);
            long workedSeconds = cursor.getLong(2);

            String startText = dateFormat.format(new Date(start * 1000L));
            String endText = dateFormat.format(new Date(end * 1000L));
            double hours = NativeBridge.secondsToHours(workedSeconds);

            builder
                    .append(startText)
                    .append(" — ")
                    .append(endText)
                    .append("\\n")
                    .append(String.format(Locale.getDefault(), "%.2f ч", hours))
                    .append("\\n\\n");
        }

        cursor.close();

        if (builder.length() == 0) {
            return "Сессий пока нет.";
        }

        return builder.toString();
    }
}
