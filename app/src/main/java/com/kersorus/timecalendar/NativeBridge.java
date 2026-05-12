package com.kersorus.timecalendar;

public final class NativeBridge {
    static {
        System.loadLibrary("timecore");
    }

    private NativeBridge() {
    }

    public static native double secondsToHours(long seconds);

    public static native double expectedHours(
            double targetHours,
            int daysInPeriod,
            int daysPassed
    );

    public static native double balance(
            double workedHours,
            double expectedHours
    );
}
