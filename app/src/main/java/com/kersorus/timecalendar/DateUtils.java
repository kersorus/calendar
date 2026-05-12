package com.kersorus.timecalendar;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class DateUtils {
    private static final long DAY_SECONDS = 24L * 60L * 60L;

    private DateUtils() {
    }

    public static int currentYear() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.YEAR);
    }

    public static int currentMonth() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.MONTH) + 1;
    }

    public static int currentDayOfMonth() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.DAY_OF_MONTH);
    }

    public static int daysInCurrentMonth() {
        Calendar calendar = Calendar.getInstance();
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    public static long todayStartSeconds() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long monthStartSeconds(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long nextMonthStartSeconds(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.MONTH, 1);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long weekStartSeconds() {
        Calendar calendar = Calendar.getInstance();
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long nextWeekStartSeconds() {
        Calendar calendar = Calendar.getInstance();
        calendar.setFirstDayOfWeek(Calendar.MONDAY);
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_MONTH, 7);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long parseDeadlineEndSeconds(String yyyyMmDd) throws ParseException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setLenient(false);
        Date date = format.parse(yyyyMmDd);
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static String formatDeadline(long deadlineSeconds) {
        if (deadlineSeconds <= 0L) {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        return format.format(new Date(deadlineSeconds * 1000L));
    }

    public static int inclusiveDays(long startSeconds, long endSeconds) {
        if (endSeconds <= startSeconds) {
            return 1;
        }
        long startDay = startSeconds / DAY_SECONDS;
        long endDay = endSeconds / DAY_SECONDS;
        long days = endDay - startDay + 1L;
        if (days < 1L) {
            return 1;
        }
        if (days > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) days;
    }

    public static int elapsedDaysInclusive(long startSeconds, long nowSeconds, long endSeconds) {
        if (nowSeconds < startSeconds) {
            return 0;
        }
        long limitedNow = Math.min(nowSeconds, endSeconds);
        return inclusiveDays(startSeconds, limitedNow);
    }

    public static int daysLeftIncludingToday(long nowSeconds, long endSeconds) {
        if (nowSeconds > endSeconds) {
            return 0;
        }
        return inclusiveDays(todayStartSeconds(), endSeconds);
    }
}
