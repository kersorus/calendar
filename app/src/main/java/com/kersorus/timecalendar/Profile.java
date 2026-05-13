package com.kersorus.timecalendar;

public class Profile {
    public static final String PERIOD_WEEK = "week";
    public static final String PERIOD_MONTH = "month";
    public static final String PERIOD_DEADLINE = "deadline";
    public static final String PERIOD_NONE = "none";

    public static final int WORK_DAYS_MON_FRI = 31; // bits: Mon Tue Wed Thu Fri

    public long id;
    public String name;
    public double targetHours;
    public String periodType;
    public long deadlineSeconds;
    public long createdAtSeconds;
    public boolean useWorkSchedule;
    public int workDaysMask;
    public double workHoursPerDay;

    public Profile(
            long id,
            String name,
            double targetHours,
            String periodType,
            long deadlineSeconds,
            long createdAtSeconds,
            boolean useWorkSchedule,
            int workDaysMask,
            double workHoursPerDay
    ) {
        this.id = id;
        this.name = name;
        this.targetHours = targetHours;
        this.periodType = periodType;
        this.deadlineSeconds = deadlineSeconds;
        this.createdAtSeconds = createdAtSeconds;
        this.useWorkSchedule = useWorkSchedule;
        this.workDaysMask = workDaysMask <= 0 ? WORK_DAYS_MON_FRI : workDaysMask;
        this.workHoursPerDay = workHoursPerDay <= 0.0 ? 8.0 : workHoursPerDay;
    }

    public boolean hasRegularGoal() {
        return PERIOD_WEEK.equals(periodType) || PERIOD_MONTH.equals(periodType);
    }

    public boolean hasDeadlineGoal() {
        return PERIOD_DEADLINE.equals(periodType);
    }

    public boolean hasAnyGoal() {
        return !PERIOD_NONE.equals(periodType) && (targetHours > 0.0 || useWorkSchedule);
    }

    @Override
    public String toString() {
        return name;
    }
}
