package com.kingmr.timerapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.util.Log;

import android.content.pm.ServiceInfo;
import androidx.core.app.NotificationCompat;

public class TimerService extends Service {
    private static final String TAG = "TimerService";
    private static final String CHANNEL_ID = "TimerServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP = "com.kingmr.timerapp.ACTION_STOP";
    public static final String ACTION_RESTART = "com.kingmr.timerapp.ACTION_RESTART";

    private long mTimeLeftInMillis;
    private long mInitialTimeInMillis;
    private boolean mTimerRunning;
    private CountDownTimer mCountDownTimer;
    private NotificationManager mNotificationManager;
    private Ringtone mRingtone;
    private Vibrator mVibrator;

    private final IBinder mBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        TimerService getService() {
            return TimerService.this;
        }
    }

    // Static listener interface for real-time UI updates
    public interface TimerListener {
        void onTick(long millisLeft, boolean isFinished);
        void onStatusChanged(boolean running);
    }

    private static TimerListener sListener;

    public static void setListener(TimerListener listener) {
        sListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopTimer();
                stopForeground(true);
                stopSelf();
            } else if (ACTION_RESTART.equals(action)) {
                restartTimer();
            } else {
                // Initial start
                long duration = intent.getLongExtra("duration", 0);
                if (duration > 0 && !mTimerRunning) {
                    mInitialTimeInMillis = duration;
                    mTimeLeftInMillis = duration;
                    startTimer();
                }
            }
        }
        return START_NOT_STICKY;
    }

    public void startTimer() {
        if (mTimerRunning) return;

        // Stop any playing alarms
        stopAlarm();

        mTimerRunning = true;
        mCountDownTimer = new CountDownTimer(mTimeLeftInMillis, 100) {
            @Override
            public void onTick(long millisUntilFinished) {
                mTimeLeftInMillis = millisUntilFinished;
                updateNotification();
                if (sListener != null) {
                    sListener.onTick(mTimeLeftInMillis, false);
                }
            }

            @Override
            public void onFinish() {
                mTimeLeftInMillis = 0;
                mTimerRunning = false;
                updateNotificationFinished();
                playAlarm();
                if (sListener != null) {
                    sListener.onTick(0, true);
                    sListener.onStatusChanged(false);
                }
            }
        }.start();

        // Start Foreground immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification());
        }

        if (sListener != null) {
            sListener.onStatusChanged(true);
        }
    }

    public void stopTimer() {
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        }
        mTimerRunning = false;
        stopAlarm();
        if (sListener != null) {
            sListener.onStatusChanged(false);
        }
    }

    public void restartTimer() {
        stopTimer();
        mTimeLeftInMillis = mInitialTimeInMillis;
        startTimer();
    }

    public void resetTimer() {
        stopTimer();
        mTimeLeftInMillis = 0;
        mInitialTimeInMillis = 0;
        stopForeground(true);
        stopSelf();
        if (sListener != null) {
            sListener.onTick(0, false);
            sListener.onStatusChanged(false);
        }
    }

    public long getTimeLeftInMillis() {
        return mTimeLeftInMillis;
    }

    public long getInitialTimeInMillis() {
        return mInitialTimeInMillis;
    }

    public boolean isTimerRunning() {
        return mTimerRunning;
    }

    private void playAlarm() {
        try {
            Uri alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alert == null) {
                alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }
            mRingtone = RingtoneManager.getRingtone(getApplicationContext(), alert);
            if (mRingtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes aa = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    mRingtone.setAudioAttributes(aa);
                }
                mRingtone.play();
            }

            // Vibrate
            if (mVibrator != null && mVibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    long[] pattern = {0, 500, 500, 500, 500};
                    mVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    long[] pattern = {0, 500, 500, 500, 500};
                    mVibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing alarm", e);
        }
    }

    public void stopAlarm() {
        if (mRingtone != null && mRingtone.isPlaying()) {
            mRingtone.stop();
        }
        if (mVibrator != null) {
            mVibrator.cancel();
        }
    }

    private void updateNotification() {
        mNotificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void updateNotificationFinished() {
        mNotificationManager.notify(NOTIFICATION_ID, buildNotificationFinished());
    }

    private Notification buildNotification() {
        int seconds = (int) (mTimeLeftInMillis / 1000) % 60 ;
        int minutes = (int) ((mTimeLeftInMillis / (1000*60)) % 60);
        int hours   = (int) ((mTimeLeftInMillis / (1000*60*60)) % 24);

        String timeLeft;
        if (hours > 0) {
            timeLeft = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            timeLeft = String.format("%02d:%02d", minutes, seconds);
        }

        // Open MainActivity on tap
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Action intents for buttons
        Intent stopIntent = new Intent(this, TimerService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent restartIntent = new Intent(this, TimerService.class).setAction(ACTION_RESTART);
        PendingIntent restartPendingIntent = PendingIntent.getService(
                this, 2, restartIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Timer Active")
                .setContentText("Time left: " + timeLeft)
                .setSmallIcon(android.R.drawable.ic_media_play) // Standard system play icon for fallback, or use a custom one
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .addAction(android.R.drawable.ic_menu_rotate, "Restart", restartPendingIntent)
                .setColor(0xE94057) // Matches our app primary theme color
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private Notification buildNotificationFinished() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, TimerService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Timer Finished!")
                .setContentText("Your countdown has ended.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", stopPendingIntent)
                .setColor(0xEF4444)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Timer Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Shows notification control for running background timer.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTimer();
    }
}
