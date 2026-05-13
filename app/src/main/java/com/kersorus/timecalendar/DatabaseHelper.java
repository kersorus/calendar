package com.kersorus.timecalendar;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "time_calendar.db";
    private static final int DB_VERSION = 4;

    public DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createProfilesTable(db);
        createSessionsTable(db);
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
        if (oldVersion < 3) {
            createProfilesTable(db);
        }
        if (oldVersion < 4) {
            try {
                db.execSQL("ALTER TABLE sessions ADD COLUMN comment TEXT NOT NULL DEFAULT ''");
            } catch (Exception ignored) {
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
                        "worked_seconds INTEGER NOT NULL, " +
                        "comment TEXT NOT NULL DEFAULT ''" +
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

    public long addSession(
            long profileId,
            String profile,
            long startTime,
            long endTime,
            long pausedSeconds,
            long workedSeconds
    ) {
        return addSession(profileId, profile, startTime, endTime, pausedSeconds, workedSeconds, "");
    }

    public long addSession(
            long profileId,
            String profile,
            long startTime,
            long endTime,
            long pausedSeconds,
            long workedSeconds,
            String comment
    ) {
        ContentValues values = new ContentValues();
        values.put("profile_id", profileId);
        values.put("profile", profile);
        values.put("start_time", startTime);
        values.put("end_time", endTime);
        values.put("paused_seconds", pausedSeconds);
        values.put("worked_seconds", workedSeconds);
        values.put("comment", comment == null ? "" : comment.trim());

        SQLiteDatabase db = getWritableDatabase();
        return db.insert("sessions", null, values);
    }

    public void updateSession(
            long sessionId,
            long startTime,
            long endTime,
            long workedSeconds,
            String comment
    ) {
        ContentValues values = new ContentValues();
        values.put("start_time", startTime);
        values.put("end_time", endTime);
        values.put("paused_seconds", 0L);
        values.put("worked_seconds", workedSeconds);
        values.put("comment", comment == null ? "" : comment.trim());

        SQLiteDatabase db = getWritableDatabase();
        db.update("sessions", values, "id = ?", new String[]{String.valueOf(sessionId)});
    }

    public void deleteSession(long sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("sessions", "id = ?", new String[]{String.valueOf(sessionId)});
    }

    public TimeSession getSessionById(long sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, profile_id, profile, start_time, end_time, paused_seconds, worked_seconds, comment " +
                        "FROM sessions WHERE id = ? LIMIT 1",
                new String[]{String.valueOf(sessionId)}
        );

        TimeSession session = null;
        if (cursor.moveToFirst()) {
            session = readSession(cursor);
        }
        cursor.close();
        return session;
    }

    public ArrayList<TimeSession> getSessions(long profileId, int limit) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, profile_id, profile, start_time, end_time, paused_seconds, worked_seconds, comment " +
                        "FROM sessions WHERE profile_id = ? ORDER BY start_time DESC LIMIT ?",
                new String[]{String.valueOf(profileId), String.valueOf(limit)}
        );

        ArrayList<TimeSession> sessions = new ArrayList<>();
        while (cursor.moveToNext()) {
            sessions.add(readSession(cursor));
        }
        cursor.close();
        return sessions;
    }

    private TimeSession readSession(Cursor cursor) {
        return new TimeSession(
                cursor.getLong(0),
                cursor.getLong(1),
                cursor.getString(2),
                cursor.getLong(3),
                cursor.getLong(4),
                cursor.getLong(5),
                cursor.getLong(6),
                cursor.getString(7)
        );
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


    public ArrayList<TimeSession> getSessionsForDay(long profileId, long dayStartSeconds) {
        long dayEndSeconds = dayStartSeconds + 24L * 60L * 60L;
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, profile_id, profile, start_time, end_time, paused_seconds, worked_seconds, comment " +
                        "FROM sessions WHERE profile_id = ? AND start_time >= ? AND start_time < ? " +
                        "ORDER BY start_time ASC",
                new String[]{
                        String.valueOf(profileId),
                        String.valueOf(dayStartSeconds),
                        String.valueOf(dayEndSeconds)
                }
        );

        ArrayList<TimeSession> sessions = new ArrayList<>();
        while (cursor.moveToNext()) {
            sessions.add(readSession(cursor));
        }
        cursor.close();
        return sessions;
    }

    public long getWorkedSecondsForDayExcludingSession(
            long profileId,
            long dayStartSeconds,
            long excludedSessionId
    ) {
        long dayEndSeconds = dayStartSeconds + 24L * 60L * 60L;
        SQLiteDatabase db = getReadableDatabase();

        Cursor cursor = db.rawQuery(
                "SELECT COALESCE(SUM(worked_seconds), 0) FROM sessions " +
                        "WHERE profile_id = ? AND start_time >= ? AND start_time < ? AND id != ?",
                new String[]{
                        String.valueOf(profileId),
                        String.valueOf(dayStartSeconds),
                        String.valueOf(dayEndSeconds),
                        String.valueOf(excludedSessionId)
                }
        );

        long result = 0L;
        if (cursor.moveToFirst()) {
            result = cursor.getLong(0);
        }
        cursor.close();
        return result;
    }

    public boolean hasWorkOnDay(long profileId, long dayStartSeconds) {
        long dayEndSeconds = dayStartSeconds + 24L * 60L * 60L;
        return getWorkedSecondsForRange(profileId, dayStartSeconds, dayEndSeconds) > 0L;
    }

    public HashMap<Long, Long> getWorkedSecondsByDayRange(
            long profileId,
            long fromSeconds,
            long toExclusiveSeconds
    ) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT start_time, worked_seconds FROM sessions " +
                        "WHERE profile_id = ? AND start_time >= ? AND start_time < ?",
                new String[]{
                        String.valueOf(profileId),
                        String.valueOf(fromSeconds),
                        String.valueOf(toExclusiveSeconds)
                }
        );

        HashMap<Long, Long> result = new HashMap<>();

        while (cursor.moveToNext()) {
            long start = cursor.getLong(0);
            long workedSeconds = cursor.getLong(1);
            long dayStart = DateUtils.startOfDaySeconds(start);

            Long previous = result.get(dayStart);
            result.put(dayStart, previous == null ? workedSeconds : previous + workedSeconds);
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
        ArrayList<TimeSession> sessions = getSessions(profileId, limit);
        if (sessions.isEmpty()) {
            return "Сессий по этому профилю пока нет.";
        }

        StringBuilder builder = new StringBuilder();
        for (TimeSession session : sessions) {
            builder.append(formatSessionLine(session)).append("\n\n");
        }
        return builder.toString();
    }

    public static String formatSessionLine(TimeSession session) {
        String line = DateUtils.formatDateTime(session.startTime) + " — " +
                DateUtils.formatDateTime(session.endTime) + "\n" +
                String.format(Locale.getDefault(), "%.2f ч", NativeBridge.secondsToHours(session.workedSeconds));
        if (session.comment != null && session.comment.trim().length() > 0) {
            line += " · " + session.comment.trim();
        }
        return line;
    }
}
