#include "time_core.h"

double tc_seconds_to_hours(int64_t seconds) {
    if (seconds < 0) {
        seconds = 0;
    }
    return ((double) seconds) / 3600.0;
}

double tc_expected_hours(double target_hours, int days_in_period, int days_passed) {
    if (target_hours < 0.0 || days_in_period <= 0 || days_passed <= 0) {
        return 0.0;
    }

    if (days_passed > days_in_period) {
        days_passed = days_in_period;
    }

    return target_hours * ((double) days_passed / (double) days_in_period);
}

double tc_balance(double worked_hours, double expected_hours) {
    return worked_hours - expected_hours;
}
