package com.kersorus.timecalendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Date;
import java.util.Locale;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "time_calendar.db";
    private static final int DB_VERSION = 2;

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createProfilesTable(db);
        createSessionsTable(db);
        insertDefaultProfile(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createProfilesTable(db);
            long defaultProfileId = insertDefaultProfile(db);
            try {
                db.execSQL("ALTER TABLE sessions ADD COLUMN profile_id INTEGER DEFAULT " + defaultProfileId);
            } catch (Exception ignored) {
                createSessionsTable(db);
            }
        }
    }

    private void createProfilesTable(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS profiles (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL UNIQUE, " +
                        "target_hours REAL NOT NULL, " +
                        "period_type TEXT NOT NULL, " +
                        "deadline_seconds INTEGER NOT NULL DEFAULT 0, " +
                        "created_at_seconds INTEGER NOT NULL" +
                        ")"
        );
    }

    private void createSessionsTable(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "profile_id INTEGER NOT NULL DEFAULT 1, " +
                        "profile TEXT NOT NULL, " +
                        "start_time INTEGER NOT NULL, " +
                        "end_time INTEGER NOT NULL, " +
                        "paused_seconds INTEGER NOT NULL, " +
                        "worked_seconds INTEGER NOT NULL" +
                        ")"
        );
    }

    private long insertDefaultProfile(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery(
                "SELECT id FROM profiles WHERE name = ? LIMIT 1",
                new String[]{"Работа"}
        );

        if (cursor.moveToFirst()) {
            long existingId = cursor.getLong(0);
            cursor.close();
            return existingId;
        }
        cursor.close();

        ContentValues values = new ContentValues();
        values.put("name", "Работа");
        values.put("target_hours", 30.0);
        values.put("period_type", Profile.PERIOD_MONTH);
        values.put("deadline_seconds", 0L);
        values.put("created_at_seconds", DateUtils.todayStartSeconds());

        return db.insert("profiles", null, values);
    }

    public ArrayList<Profile> getProfiles() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, name, target_hours, period_type, deadline_seconds, created_at_seconds " +
                        "FROM profiles ORDER BY id ASC",
                null
        );

        ArrayList<Profile> profiles = new ArrayList<>();
        while (cursor.moveToNext()) {
            profiles.add(new Profile(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getDouble(2),
                    cursor.getString(3),
                    cursor.getLong(4),
                    cursor.getLong(5)
            ));
        }
        cursor.close();

        if (profiles.isEmpty()) {
            SQLiteDatabase writeDb = getWritableDatabase();
            insertDefaultProfile(writeDb);
            return getProfiles();
        }

        return profiles;
    }

    public Profile getProfileById(long profileId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, name, target_hours, period_type, deadline_seconds, created_at_seconds " +
                        "FROM profiles WHERE id = ? LIMIT 1",
                new String[]{String.valueOf(profileId)}
        );

        Profile profile = null;
        if (cursor.moveToFirst()) {
            profile = new Profile(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getDouble(2),
                    cursor.getString(3),
                    cursor.getLong(4),
                    cursor.getLong(5)
            );
        }
        cursor.close();
        return profile;
    }

    public long saveProfile(
            long id,
            String name,
            double targetHours,
            String periodType,
            long deadlineSeconds
    ) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("target_hours", targetHours);
        values.put("period_type", periodType);
        values.put("deadline_seconds", deadlineSeconds);

        if (id > 0L) {
            db.update("profiles", values, "id = ?", new String[]{String.valueOf(id)});
            return id;
        }

        values.put("created_at_seconds", DateUtils.todayStartSeconds());
        long insertedId = db.insert("profiles", null, values);
        if (insertedId < 0L) {
            Cursor cursor = db.rawQuery(
                    "SELECT id FROM profiles WHERE name = ? LIMIT 1",
                    new String[]{name}
            );
            if (cursor.moveToFirst()) {
                insertedId = cursor.getLong(0);
                db.update("profiles", values, "id = ?", new String[]{String.valueOf(insertedId)});
            }
            cursor.close();
        }
        return insertedId;
    }

    public void addSession(
            long profileId,
            String profile,
            long startTime,
            long endTime,
            long pausedSeconds,
            long workedSeconds
    ) {
        ContentValues values = new ContentValues();
        values.put("profile_id", profileId);
        values.put("profile", profile);
        values.put("start_time", startTime);
        values.put("end_time", endTime);
        values.put("paused_seconds", pausedSeconds);
        values.put("worked_seconds", workedSeconds);

        SQLiteDatabase db = getWritableDatabase();
        db.insert("sessions", null, values);
    }

    public long getWorkedSecondsForRange(long profileId, long from, long to) {
        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT COALESCE(SUM(worked_seconds), 0) FROM sessions " +
                        "WHERE profile_id = ? AND start_time >= ? AND start_time < ?",
                new String[]{
                        String.valueOf(profileId),
                        String.valueOf(from),
                        String.valueOf(to)
                }
        );

        long result = 0;
        if (cursor.moveToFirst()) {
            result = cursor.getLong(0);
        }
        cursor.close();

        return result;
    }


    public HashMap<Integer, Long> getWorkedSecondsByDay(long profileId, int year, int month) {
        long from = DateUtils.monthStartSeconds(year, month);
        long to = DateUtils.nextMonthStartSeconds(year, month);

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT start_time, worked_seconds FROM sessions " +
                        "WHERE profile_id = ? AND start_time >= ? AND start_time < ?",
                new String[]{
                        String.valueOf(profileId),
                        String.valueOf(from),
                        String.valueOf(to)
                }
        );

        HashMap<Integer, Long> result = new HashMap<>();
        Calendar calendar = Calendar.getInstance();

        while (cursor.moveToNext()) {
            long start = cursor.getLong(0);
            long workedSeconds = cursor.getLong(1);
            calendar.setTimeInMillis(start * 1000L);
            int day = calendar.get(Calendar.DAY_OF_MONTH);

            Long previous = result.get(day);
            result.put(day, previous == null ? workedSeconds : previous + workedSeconds);
        }

        cursor.close();
        return result;
    }

    public String getLastSessionsText(long profileId, int limit) {
        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT start_time, end_time, worked_seconds FROM sessions " +
                        "WHERE profile_id = ? ORDER BY start_time DESC LIMIT ?",
                new String[]{String.valueOf(profileId), String.valueOf(limit)}
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
                    .append("\n")
                    .append(String.format(Locale.getDefault(), "%.2f ч", hours))
                    .append("\n\n");
        }

        cursor.close();

        if (builder.length() == 0) {
            return "Сессий по этому профилю пока нет.";
        }

        return builder.toString();
    }
}
