#include <jni.h>
#include "time_core.h"

JNIEXPORT jdouble JNICALL
Java_com_kersorus_timecalendar_NativeBridge_secondsToHours(
        JNIEnv *env,
        jclass clazz,
        jlong seconds
) {
    (void) env;
    (void) clazz;
    return tc_seconds_to_hours((int64_t) seconds);
}

JNIEXPORT jdouble JNICALL
Java_com_kersorus_timecalendar_NativeBridge_expectedHours(
        JNIEnv *env,
        jclass clazz,
        jdouble targetHours,
        jint daysInPeriod,
        jint daysPassed
) {
    (void) env;
    (void) clazz;
    return tc_expected_hours(targetHours, daysInPeriod, daysPassed);
}

JNIEXPORT jdouble JNICALL
Java_com_kersorus_timecalendar_NativeBridge_balance(
        JNIEnv *env,
        jclass clazz,
        jdouble workedHours,
        jdouble expectedHours
) {
    (void) env;
    (void) clazz;
    return tc_balance(workedHours, expectedHours);
}

JNIEXPORT jdouble JNICALL
Java_com_kersorus_timecalendar_NativeBridge_requiredDailyHours(
        JNIEnv *env,
        jclass clazz,
        jdouble remainingHours,
        jint daysLeft
) {
    (void) env;
    (void) clazz;
    return tc_required_daily_hours(remainingHours, daysLeft);
}
