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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TimerService extends Service {
    private static final String TAG = "TimerService";
    private static final String CHANNEL_ID = "TimerServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_STOP_ALARM = "com.kingmr.timerapp.ACTION_STOP_ALARM";

    private final List<TimerModel> mTimers = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsTicking = false;

    private NotificationManager mNotificationManager;
    private MediaPlayer mMediaPlayer;
    private Vibrator mVibrator;

    // Fade-in & Auto-silence handlers
    private final Handler mVolumeHandler = new Handler(Looper.getMainLooper());
    private Runnable mVolumeFadeRunnable;
    private float mCurrentVolume = 0.1f;
    private final Handler mSilenceHandler = new Handler(Looper.getMainLooper());

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

    public interface MultiTimerListener {
        void onTimersUpdated();
    }

    private static MultiTimerListener sListener;

    public static void setListener(MultiTimerListener listener) {
        sListener = listener;
    }

    private final Runnable mTickRunnable = new Runnable() {
        @Override
        public void run() {
            boolean activeRunning = false;
            synchronized (mTimers) {
                for (TimerModel timer : mTimers) {
                    if (timer.isRunning()) {
                        long left = timer.getRemainingDuration() - 100;
                        if (left <= 0) {
                            timer.setRemainingDuration(0);
                            timer.setRunning(false);
                            // Trigger Alarm
                            playAlarm(timer.getLabel());
                        } else {
                            timer.setRemainingDuration(left);
                            activeRunning = true;
                        }
                    }
                }
            }

            // Update UI listeners
            if (sListener != null) {
                sListener.onTimersUpdated();
            }

            // Update floating window
            updateFloatingWindowList();

            if (activeRunning) {
                mHandler.postDelayed(this, 100);
            } else {
                mIsTicking = false;
                // If there are still active timers but none running, update notification
                updateNotificationTray();
            }
        }
    };

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
            if (ACTION_STOP_ALARM.equals(action)) {
                stopAlarm();
                updateNotificationTray();
            }
        }
        return START_NOT_STICKY;
    }

    public List<TimerModel> getTimers() {
        return mTimers;
    }

    public void addTimer(long duration, String label) {
        synchronized (mTimers) {
            TimerModel timer = new TimerModel(UUID.randomUUID().toString(), duration, label);
            mTimers.add(timer);
        }
        startTickingIfNeeded();
        updateNotificationTray();
        if (sListener != null) {
            sListener.onTimersUpdated();
        }
    }

    public void removeTimer(String id) {
        synchronized (mTimers) {
            for (int i = 0; i < mTimers.size(); i++) {
                if (mTimers.get(i).getId().equals(id)) {
                    mTimers.remove(i);
                    break;
                }
            }
        }
        updateNotificationTray();
        checkFloatingWindowLifecycle();
        if (sListener != null) {
            sListener.onTimersUpdated();
        }
    }

    public void toggleTimer(String id) {
        synchronized (mTimers) {
            for (TimerModel timer : mTimers) {
                if (timer.getId().equals(id)) {
                    if (timer.getRemainingDuration() <= 0) {
                        timer.setRemainingDuration(timer.getInitialDuration());
                    }
                    timer.setRunning(!timer.isRunning());
                    break;
                }
            }
        }
        startTickingIfNeeded();
        updateNotificationTray();
        if (sListener != null) {
            sListener.onTimersUpdated();
        }
    }

    public void restartTimer(String id) {
        synchronized (mTimers) {
            for (TimerModel timer : mTimers) {
                if (timer.getId().equals(id)) {
                    timer.setRemainingDuration(timer.getInitialDuration());
                    timer.setRunning(true);
                    break;
                }
            }
        }
        startTickingIfNeeded();
        updateNotificationTray();
        if (sListener != null) {
            sListener.onTimersUpdated();
        }
    }

    private void startTickingIfNeeded() {
        if (!mIsTicking) {
            boolean hasRunning = false;
            synchronized (mTimers) {
                for (TimerModel t : mTimers) {
                    if (t.isRunning()) {
                        hasRunning = true;
                        break;
                    }
                }
            }
            if (hasRunning) {
                mIsTicking = true;
                mHandler.post(mTickRunnable);
                
                // Start FGS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification());
                }
                
                checkFloatingWindowLifecycle();
            }
        }
    }

    // App Foreground / Background Lifecycle
    public void setAppInForeground(boolean foreground) {
        mAppInForeground = foreground;
        checkFloatingWindowLifecycle();
    }

    private void checkFloatingWindowLifecycle() {
        boolean hasRunning = false;
        synchronized (mTimers) {
            for (TimerModel t : mTimers) {
                if (t.isRunning()) {
                    hasRunning = true;
                    break;
                }
            }
        }
        if (!mAppInForeground && hasRunning) {
            showFloatingWindow();
        } else {
            removeFloatingWindow();
        }
    }

    // --- Floating Window Overlay ---
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

        // Setup Drag handling
        View dragHandle = mFloatingView.findViewById(R.id.dragHandle);
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
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
                    case MotionEvent.ACTION_MOVE:
                        mFloatingParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        mFloatingParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            if (mFloatingView != null) {
                                mWindowManager.updateViewLayout(mFloatingView, mFloatingParams);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error updating overlay", e);
                        }
                        return true;
                }
                return false;
            }
        });

        // Setup Close & Restart behavior in layout. For multi-timer overlay:
        // floatBtnStop stops/resets the first running timer.
        // floatBtnRestart restarts the first running timer.
        mFloatingView.findViewById(R.id.floatBtnRestart).setOnClickListener(v -> {
            synchronized (mTimers) {
                for (TimerModel t : mTimers) {
                    if (t.isRunning()) {
                        restartTimer(t.getId());
                        break;
                    }
                }
            }
        });
        mFloatingView.findViewById(R.id.floatBtnStop).setOnClickListener(v -> {
            synchronized (mTimers) {
                for (TimerModel t : mTimers) {
                    if (t.isRunning()) {
                        removeTimer(t.getId());
                        break;
                    }
                }
            }
        });

        try {
            mWindowManager.addView(mFloatingView, mFloatingParams);
            updateFloatingWindowList();
        } catch (Exception e) {
            Log.e(TAG, "Failed to create floating overlay", e);
        }
    }

    private void removeFloatingWindow() {
        if (mFloatingView != null) {
            try {
                mWindowManager.removeView(mFloatingView);
            } catch (Exception e) {
                Log.e(TAG, "Failed to remove floating overlay", e);
            }
            mFloatingView = null;
        }
    }

    private void updateFloatingWindowList() {
        if (mFloatingView == null) return;
        TextView txtTime = mFloatingView.findViewById(R.id.floatTimeText);
        if (txtTime != null) {
            // Find running timer with minimum time remaining to focus display
            TimerModel focus = null;
            long minTime = Long.MAX_VALUE;
            synchronized (mTimers) {
                for (TimerModel t : mTimers) {
                    if (t.isRunning() && t.getRemainingDuration() < minTime) {
                        minTime = t.getRemainingDuration();
                        focus = t;
                    }
                }
            }
            if (focus != null) {
                long millis = focus.getRemainingDuration();
                int seconds = (int) (millis / 1000) % 60;
                int minutes = (int) ((millis / (1000 * 60)) % 60);
                int hours = (int) ((millis / (1000 * 60 * 60)) % 24);
                String labelStr = focus.getLabel();
                if (labelStr.length() > 8) labelStr = labelStr.substring(0, 6) + "..";
                
                String timeStr;
                if (hours > 0) {
                    timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds);
                } else {
                    timeStr = String.format("%02d:%02d", minutes, seconds);
                }
                txtTime.setText(labelStr + ": " + timeStr);
            } else {
                txtTime.setText("No active timers");
            }
        }
    }

    // --- Dynamic Notification Tray Management ---
    private void updateNotificationTray() {
        boolean hasActive = false;
        synchronized (mTimers) {
            for (TimerModel t : mTimers) {
                if (t.isRunning() || t.getRemainingDuration() > 0) {
                    hasActive = true;
                    break;
                }
            }
        }
        if (hasActive) {
            mNotificationManager.notify(NOTIFICATION_ID, buildNotification());
        } else {
            stopForeground(true);
        }
    }

    private Notification buildNotification() {
        // Find timer with closest finish time to bind the chronometer
        TimerModel focus = null;
        long minTime = Long.MAX_VALUE;
        int activeCount = 0;
        synchronized (mTimers) {
            for (TimerModel t : mTimers) {
                if (t.isRunning() || t.getRemainingDuration() > 0) {
                    activeCount++;
                    if (t.isRunning() && t.getRemainingDuration() < minTime) {
                        minTime = t.getRemainingDuration();
                        focus = t;
                    }
                }
            }
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, TimerService.class).setAction(ACTION_STOP_ALARM);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 3, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setColor(0xE94057)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (focus != null) {
            builder.setContentTitle("Timer Active: " + focus.getLabel())
                   .setContentText("Total active timers: " + activeCount)
                   .setUsesChronometer(true)
                   .setChronometerCountDown(true)
                   .setWhen(System.currentTimeMillis() + focus.getRemainingDuration())
                   .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Alerts", stopPendingIntent);
        } else {
            builder.setContentTitle("Aura Timer")
                   .setContentText(activeCount + " active timers configured.")
                   .setUsesChronometer(false);
        }

        return builder.build();
    }

    private void updateNotificationFinished(String label) {
        mNotificationManager.notify(NOTIFICATION_ID, buildNotificationFinished(label));
    }

    private Notification buildNotificationFinished(String label) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, TimerService.class).setAction(ACTION_STOP_ALARM);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 4, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Timer Finished!")
                .setContentText("Your countdown '" + label + "' has ended.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss Alarm", stopPendingIntent)
                .setColor(0xEF4444)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
    }

    // --- Audio Alarm Looping, Volume Fade-in, and Auto-silence ---
    private void playAlarm(String label) {
        try {
            stopAlarm();

            // Update finished notification tray
            updateNotificationFinished(label);

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

            mMediaPlayer.setLooping(true);
            
            // Set initial low volume (10%)
            mCurrentVolume = 0.1f;
            mMediaPlayer.setVolume(mCurrentVolume, mCurrentVolume);
            
            mMediaPlayer.prepare();
            mMediaPlayer.start();

            // Trigger Volume Fade-In: increase volume by 10% every second
            mVolumeFadeRunnable = new Runnable() {
                @Override
                public void run() {
                    if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                        mCurrentVolume += 0.1f;
                        if (mCurrentVolume > 1.0f) mCurrentVolume = 1.0f;
                        mMediaPlayer.setVolume(mCurrentVolume, mCurrentVolume);
                        if (mCurrentVolume < 1.0f) {
                            mVolumeHandler.postDelayed(this, 1000);
                        }
                    }
                }
            };
            mVolumeHandler.postDelayed(mVolumeFadeRunnable, 1000);

            // Trigger Looping vibration
            if (mVibrator != null && mVibrator.hasVibrator()) {
                long[] pattern = {0, 800, 800, 800, 800};
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    mVibrator.vibrate(pattern, 0);
                }
            }

            // Auto-Silence: automatically silence after 120 seconds (2 minutes)
            mSilenceHandler.postDelayed(this::stopAlarm, 120000);

        } catch (Exception e) {
            Log.e(TAG, "Error playing looped custom alarm", e);
        }
    }

    public void stopAlarm() {
        try {
            mVolumeHandler.removeCallbacks(mVolumeFadeRunnable);
            mSilenceHandler.removeCallbacksAndMessages(null);

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
            Log.e(TAG, "Error stopping alarm resources", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Timer Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Shows notification controls for running background timers.");
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
        stopAlarm();
        mHandler.removeCallbacks(mTickRunnable);
    }
}
