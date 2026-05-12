package com.kersorus.timecalendar;

public class Profile {
    public static final String PERIOD_WEEK = "week";
    public static final String PERIOD_MONTH = "month";
    public static final String PERIOD_DEADLINE = "deadline";

    public long id;
    public String name;
    public double targetHours;
    public String periodType;
    public long deadlineSeconds;
    public long createdAtSeconds;

    public Profile(
            long id,
            String name,
            double targetHours,
            String periodType,
            long deadlineSeconds,
            long createdAtSeconds
    ) {
        this.id = id;
        this.name = name;
        this.targetHours = targetHours;
        this.periodType = periodType;
        this.deadlineSeconds = deadlineSeconds;
        this.createdAtSeconds = createdAtSeconds;
    }

    @Override
    public String toString() {
        return name;
    }
}
