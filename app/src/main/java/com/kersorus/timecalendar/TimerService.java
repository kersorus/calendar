package com.kersorus.timecalendar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;

public class TimerService extends Service {
    private static final String TAG = "TimeCalendar";

    public static final String ACTION_START = "com.kersorus.timecalendar.START";
    public static final String ACTION_PAUSE = "com.kersorus.timecalendar.PAUSE";
    public static final String ACTION_RESUME = "com.kersorus.timecalendar.RESUME";
    public static final String ACTION_STOP = "com.kersorus.timecalendar.STOP";

    public static final String EXTRA_PROFILE = "profile";

    private static final String CHANNEL_ID = "timer_channel";
    private static final int NOTIFICATION_ID = 10;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String profile = "Работа";
    private boolean running = false;
    private boolean paused = false;

    private long startTime = 0L;
    private long pauseStart = 0L;
    private long pausedSeconds = 0L;

    private final Runnable notificationUpdater = new Runnable() {
        @Override
        public void run() {
            if (running) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                manager.notify(NOTIFICATION_ID, buildNotification());
                handler.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TimerService.onCreate");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        Log.d(TAG, "TimerService.onStartCommand action=" + action);

        if (ACTION_START.equals(action)) {
            String incomingProfile = intent.getStringExtra(EXTRA_PROFILE);
            if (incomingProfile != null && incomingProfile.trim().length() > 0) {
                profile = incomingProfile.trim();
            }
            startTimer();
        } else if (ACTION_PAUSE.equals(action)) {
            pauseTimer();
        } else if (ACTION_RESUME.equals(action)) {
            resumeTimer();
        } else if (ACTION_STOP.equals(action)) {
            stopTimer();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        handler.removeCallbacks(notificationUpdater);
        handler.post(notificationUpdater);

        return START_STICKY;
    }

    private void startTimer() {
        if (running) {
            return;
        }

        running = true;
        paused = false;
        pausedSeconds = 0L;
        pauseStart = 0L;
        startTime = nowSeconds();
    }

    private void pauseTimer() {
        if (!running || paused) {
            return;
        }

        paused = true;
        pauseStart = nowSeconds();
    }

    private void resumeTimer() {
        if (!running || !paused) {
            return;
        }

        long now = nowSeconds();
        pausedSeconds += now - pauseStart;
        pauseStart = 0L;
        paused = false;
    }

    private void stopTimer() {
        Log.d(TAG, "TimerService.stopTimer running=" + running + " paused=" + paused);

        if (!running) {
            stopSelf();
            return;
        }

        long endTime = nowSeconds();

        if (paused) {
            pausedSeconds += endTime - pauseStart;
        }

        long workedSeconds = endTime - startTime - pausedSeconds;
        if (workedSeconds < 0L) {
            workedSeconds = 0L;
        }

        DatabaseHelper db = new DatabaseHelper(this);
        db.addSession(
                profile,
                startTime,
                endTime,
                pausedSeconds,
                workedSeconds
        );

        running = false;
        paused = false;
        handler.removeCallbacks(notificationUpdater);

        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification() {
        String title = paused ? "Таймер на паузе" : "Идёт подсчёт времени";
        String timeText = formatDuration(getVisibleWorkedSeconds());

        PendingIntent mainIntent = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent pauseOrResumeIntent = PendingIntent.getService(
                this,
                2,
                new Intent(this, TimerService.class)
                        .setAction(paused ? ACTION_RESUME : ACTION_PAUSE),
                PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent stopIntent = PendingIntent.getService(
                this,
                3,
                new Intent(this, TimerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(this, CHANNEL_ID)
                        : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(title)
                .setContentText(profile + ": " + timeText)
                .setContentIntent(mainIntent)
                .setOngoing(true)
                .addAction(
                        android.R.drawable.ic_media_pause,
                        paused ? "Продолжить" : "Пауза",
                        pauseOrResumeIntent
                )
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Стоп",
                        stopIntent
                )
                .build();
    }

    private long getVisibleWorkedSeconds() {
        if (!running) {
            return 0L;
        }

        long now = nowSeconds();

        if (paused) {
            return pauseStart - startTime - pausedSeconds;
        }

        return now - startTime - pausedSeconds;
    }

    private static String formatDuration(long seconds) {
        if (seconds < 0L) {
            seconds = 0L;
        }

        long h = seconds / 3600L;
        long m = (seconds % 3600L) / 60L;
        long s = seconds % 60L;

        return String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s);
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Учёт времени",
                NotificationManager.IMPORTANCE_LOW
        );

        channel.setDescription("Таймер рабочего времени");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
