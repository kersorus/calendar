package com.kersorus.timecalendar;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public final class DateUtils {
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

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    public static long todayStartSeconds() {
        Calendar calendar = Calendar.getInstance();
        moveToDayStart(calendar);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long startOfDaySeconds(long seconds) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(seconds * 1000L);
        moveToDayStart(calendar);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long monthStartSeconds(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        moveToDayStart(calendar);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long nextMonthStartSeconds(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        moveToDayStart(calendar);
        calendar.add(Calendar.MONTH, 1);
        return calendar.getTimeInMillis() / 1000L;
    }

    public static int daysInMonth(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        return calendar.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    public static int firstWeekdayMondayBased(int year, int month) {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        int day = calendar.get(Calendar.DAY_OF_WEEK); // Sunday = 1
        return day == Calendar.SUNDAY ? 7 : day - 1; // Monday = 1
    }

    public static long weekStartSeconds() {
        Calendar calendar = Calendar.getInstance();
        moveToDayStart(calendar);
        int day = calendar.get(Calendar.DAY_OF_WEEK);
        int mondayBased = day == Calendar.SUNDAY ? 7 : day - 1;
        calendar.add(Calendar.DAY_OF_MONTH, -(mondayBased - 1));
        return calendar.getTimeInMillis() / 1000L;
    }

    public static long nextWeekStartSeconds() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(weekStartSeconds() * 1000L);
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

    public static int periodDays(long startSeconds, long endExclusiveSeconds) {
        if (endExclusiveSeconds <= startSeconds) {
            return 1;
        }
        long start = startOfDaySeconds(startSeconds);
        long end = startOfDaySeconds(endExclusiveSeconds);
        return Math.max(1, daysBetweenStarts(start, end));
    }

    public static int elapsedPeriodDaysIncludingToday(
            long startSeconds,
            long nowSeconds,
            long endExclusiveSeconds
    ) {
        if (nowSeconds < startSeconds) {
            return 0;
        }
        int total = periodDays(startSeconds, endExclusiveSeconds);
        long start = startOfDaySeconds(startSeconds);
        long today = startOfDaySeconds(nowSeconds);
        int elapsed = daysBetweenStarts(start, today) + 1;
        if (elapsed < 0) {
            return 0;
        }
        return Math.min(total, elapsed);
    }

    public static int daysLeftAfterToday(long startSeconds, long nowSeconds, long endExclusiveSeconds) {
        int total = periodDays(startSeconds, endExclusiveSeconds);
        int elapsed = elapsedPeriodDaysIncludingToday(startSeconds, nowSeconds, endExclusiveSeconds);
        return Math.max(0, total - elapsed);
    }

    public static int daysAvailableForWorkIncludingToday(long nowSeconds, long endExclusiveSeconds) {
        if (nowSeconds >= endExclusiveSeconds) {
            return 0;
        }
        long today = startOfDaySeconds(nowSeconds);
        long end = startOfDaySeconds(endExclusiveSeconds);
        return Math.max(1, daysBetweenStarts(today, end));
    }

    private static int daysBetweenStarts(long startDaySeconds, long endDaySeconds) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(startDaySeconds * 1000L);
        moveToDayStart(start);

        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(endDaySeconds * 1000L);
        moveToDayStart(end);

        int days = 0;
        while (start.before(end)) {
            start.add(Calendar.DAY_OF_MONTH, 1);
            days++;
            if (days > 50000) {
                return days;
            }
        }
        return days;
    }

    private static void moveToDayStart(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }
}
