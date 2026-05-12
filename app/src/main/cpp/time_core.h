#ifndef TIME_CORE_H
#define TIME_CORE_H

#include <stdint.h>

double tc_seconds_to_hours(int64_t seconds);
double tc_expected_hours(double target_hours, int days_in_period, int days_passed);
double tc_balance(double worked_hours, double expected_hours);

#endif
