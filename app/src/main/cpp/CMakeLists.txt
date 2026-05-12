cmake_minimum_required(VERSION 3.22.1)

project(timecalendar)

add_library(
        timecore
        SHARED
        time_core.c
        native_bridge.c
)

find_library(
        log-lib
        log
)

target_link_libraries(
        timecore
        ${log-lib}
)
