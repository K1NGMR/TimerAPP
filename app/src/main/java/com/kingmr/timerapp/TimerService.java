package com.kingmr.timerapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

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
    private MediaPlayer mMediaPlayer;
    private Vibrator mVibrator;

    // Floating Window Variables
    private WindowManager mWindowManager;
    private View mFloatingView;
    private WindowManager.LayoutParams mFloatingParams;
    private boolean mAppInForeground = true;

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
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                resetTimer();
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
                updateFloatingWindowText();
                if (sListener != null) {
                    sListener.onTick(mTimeLeftInMillis, false);
                }
            }

            @Override
            public void onFinish() {
                mTimeLeftInMillis = 0;
                mTimerRunning = false;
                removeFloatingWindow();
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

        // Handle background UI transition
        checkFloatingWindowLifecycle();

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
        removeFloatingWindow();
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

    // App Foreground / Background State management
    public void setAppInForeground(boolean foreground) {
        mAppInForeground = foreground;
        checkFloatingWindowLifecycle();
    }

    private void checkFloatingWindowLifecycle() {
        if (!mAppInForeground && mTimerRunning) {
            showFloatingWindow();
        } else {
            removeFloatingWindow();
        }
    }

    // --- Floating Window Implementation ---
    private void showFloatingWindow() {
        if (mFloatingView != null) return;
        if (!Settings.canDrawOverlays(this)) return;

        LayoutInflater inflater = LayoutInflater.from(this);
        mFloatingView = inflater.inflate(R.layout.floating_timer, null);

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        mFloatingParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        mFloatingParams.gravity = Gravity.TOP | Gravity.START;
        mFloatingParams.x = 150;
        mFloatingParams.y = 150;

        // Add actions in floating window
        ImageView btnRestart = mFloatingView.findViewById(R.id.floatBtnRestart);
        ImageView btnStop = mFloatingView.findViewById(R.id.floatBtnStop);

        btnRestart.setOnClickListener(v -> restartTimer());
        btnStop.setOnClickListener(v -> resetTimer());

        // Setup Drag handling on the entire root layout container
        View floatingRoot = mFloatingView.findViewById(R.id.floatingRoot);
        floatingRoot.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mFloatingParams.x;
                        initialY = mFloatingParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_UP:
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        mFloatingParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        mFloatingParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            if (mFloatingView != null) {
                                mWindowManager.updateViewLayout(mFloatingView, mFloatingParams);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating overlay layout", e);
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            mWindowManager.addView(mFloatingView, mFloatingParams);
            updateFloatingWindowText();
        } catch (Exception e) {
            Log.e(TAG, "Failed to add floating overlay window", e);
        }
    }

    private void removeFloatingWindow() {
        if (mFloatingView != null) {
            try {
                mWindowManager.removeView(mFloatingView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove floating overlay window", e);
            }
            mFloatingView = null;
        }
    }

    private void updateFloatingWindowText() {
        if (mFloatingView == null) return;
        TextView txtTime = mFloatingView.findViewById(R.id.floatTimeText);
        if (txtTime != null) {
            int seconds = (int) (mTimeLeftInMillis / 1000) % 60;
            int minutes = (int) ((mTimeLeftInMillis / (1000 * 60)) % 60);
            int hours = (int) ((mTimeLeftInMillis / (1000 * 60 * 60)) % 24);
            String display;
            if (hours > 0) {
                display = String.format("%02d:%02d:%02d", hours, minutes, seconds);
            } else {
                display = String.format("%02d:%02d", minutes, seconds);
            }
            txtTime.setText(display);
        }
    }

    private void updateNotificationFinished() {
        mNotificationManager.notify(NOTIFICATION_ID, buildNotificationFinished());
    }

    // --- Audio alarm & Vibration ---
    private void playAlarm() {
        try {
            stopAlarm();

            // Load custom Uri from preferences, or fallback to default alarm
            Uri alertUri = null;
            String customUriStr = getSharedPreferences("TimerPrefs", MODE_PRIVATE).getString("custom_sound", null);
            if (customUriStr != null) {
                try {
                    alertUri = Uri.parse(customUriStr);
                } catch (Exception ignored) {}
            }
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            }
            if (alertUri == null) {
                alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }

            mMediaPlayer = new MediaPlayer();
            mMediaPlayer.setDataSource(this, alertUri);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mMediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
            } else {
                mMediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            }

            mMediaPlayer.setLooping(true); // Loop alarm repeatedly until user actions
            mMediaPlayer.prepare();
            mMediaPlayer.start();

            // Vibration loops concurrently
            if (mVibrator != null && mVibrator.hasVibrator()) {
                long[] pattern = {0, 800, 800, 800, 800};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    mVibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing looping custom alarm sound", e);
        }
    }

    public void stopAlarm() {
        try {
            if (mMediaPlayer != null) {
                if (mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                }
                mMediaPlayer.release();
                mMediaPlayer = null;
            }
            if (mVibrator != null) {
                mVibrator.cancel();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping alarm/vibrate resources", e);
        }
    }

    private Notification buildNotification() {
        // Tap intent back to app
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Direct Service PendingIntents for Stop/Restart
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
                .setContentText("Aura Timer runs in background.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                // Use Native Chronometer ticking
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(System.currentTimeMillis() + mTimeLeftInMillis)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .addAction(android.R.drawable.ic_menu_rotate, "Restart", restartPendingIntent)
                .setColor(0xE94057)
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
