package com.kersorus.timecalendar;

public class TimeSession {
    public long id;
    public long profileId;
    public String profileName;
    public long startTime;
    public long endTime;
    public long pausedSeconds;
    public long workedSeconds;
    public String comment;

    public TimeSession(
            long id,
            long profileId,
            String profileName,
            long startTime,
            long endTime,
            long pausedSeconds,
            long workedSeconds,
            String comment
    ) {
        this.id = id;
        this.profileId = profileId;
        this.profileName = profileName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.pausedSeconds = pausedSeconds;
        this.workedSeconds = workedSeconds;
        this.comment = comment == null ? "" : comment;
    }
}
