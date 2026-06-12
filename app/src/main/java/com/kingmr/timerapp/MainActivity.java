package com.kingmr.timerapp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;

public class MainActivity extends AppCompatActivity implements TimerService.TimerListener {
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 124;
    private static final int AUDIO_PICKER_REQ_CODE = 125;

    private TextView mTextTimeLeft;
    private TextView mTextTimerState;
    private CircularProgressIndicator mTimerProgress;
    
    private EditText mEditHours;
    private EditText mEditMinutes;
    private EditText mEditSeconds;
    
    private LinearLayout mPickerContainer;
    private LinearLayout mPresetsContainer;
    private LinearLayout mSoundContainer;
    
    private Button mBtnPrimaryAction;
    private Button mBtnReset;
    private Button mBtnRestart;
    
    private TextView mTextSoundName;
    private Button mBtnSelectSound;

    private TimerService mService;
    private boolean mBound = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            TimerService.LocalBinder binder = (TimerService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            TimerService.setListener(MainActivity.this);
            mService.setAppInForeground(true); // Tell service app is active in foreground
            updateUiState();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
            mService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Request notification permissions for Android 13+
        requestNotificationPermission();

        // Initialize UI Elements
        mTextTimeLeft = findViewById(R.id.textTimeLeft);
        mTextTimerState = findViewById(R.id.textTimerState);
        mTimerProgress = findViewById(R.id.timerProgress);
        
        mEditHours = findViewById(R.id.editHours);
        mEditMinutes = findViewById(R.id.editMinutes);
        mEditSeconds = findViewById(R.id.editSeconds);
        
        mPickerContainer = findViewById(R.id.pickerContainer);
        mPresetsContainer = findViewById(R.id.presetsContainer);
        mSoundContainer = findViewById(R.id.soundContainer);
        
        mBtnPrimaryAction = findViewById(R.id.btnPrimaryAction);
        mBtnReset = findViewById(R.id.btnReset);
        mBtnRestart = findViewById(R.id.btnRestart);
        
        mTextSoundName = findViewById(R.id.textSoundName);
        mBtnSelectSound = findViewById(R.id.btnSelectSound);

        // Add formatter logic to input fields (auto pad with 0s)
        setupInputPadding(mEditHours);
        setupInputPadding(mEditMinutes);
        setupInputPadding(mEditSeconds);

        // Setup actions
        mBtnPrimaryAction.setOnClickListener(v -> handlePrimaryAction());
        mBtnReset.setOnClickListener(v -> handleReset());
        mBtnRestart.setOnClickListener(v -> handleRestart());

        mBtnSelectSound.setOnClickListener(v -> openAudioPicker());

        // Setup presets
        setupPresets();

        // Display current alarm sound name
        loadSavedSoundName();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to TimerService
        Intent intent = new Intent(this, TimerService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBound && mService != null) {
            mService.setAppInForeground(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mBound && mService != null) {
            mService.setAppInForeground(false); // Service handles showing overlay if timer is active
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mBound) {
            TimerService.setListener(null);
            unbindService(mConnection);
            mBound = false;
        }
    }

    @Override
    public void onTick(long millisLeft, boolean isFinished) {
        runOnUiThread(() -> {
            updateCountdownText(millisLeft);
            if (mBound && mService != null) {
                long initial = mService.getInitialTimeInMillis();
                if (initial > 0) {
                    int progress = (int) (millisLeft * 100 / initial);
                    mTimerProgress.setProgress(progress);
                }
            }
            if (isFinished) {
                mTextTimerState.setText("FINISHED!");
            }
        });
    }

    @Override
    public void onStatusChanged(boolean running) {
        runOnUiThread(this::updateUiState);
    }

    private void updateUiState() {
        if (!mBound || mService == null) return;

        boolean running = mService.isTimerRunning();
        long timeLeft = mService.getTimeLeftInMillis();
        long initial = mService.getInitialTimeInMillis();

        if (running || timeLeft > 0) {
            // Timer active or paused
            mPickerContainer.setVisibility(View.GONE);
            mPresetsContainer.setVisibility(View.GONE);
            mSoundContainer.setVisibility(View.GONE);
            
            mBtnReset.setVisibility(View.VISIBLE);
            mBtnRestart.setVisibility(View.VISIBLE);
            mBtnPrimaryAction.setText("STOP");
            
            mTextTimerState.setText(running ? "RUNNING" : "PAUSED");
            
            if (running) {
                startPulseAnimation();
            } else {
                stopPulseAnimation();
            }
            
            updateCountdownText(timeLeft);
            
            if (initial > 0) {
                int progress = (int) (timeLeft * 100 / initial);
                mTimerProgress.setProgress(progress);
            }
        } else {
            // Timer stopped / completed
            mPickerContainer.setVisibility(View.VISIBLE);
            mPresetsContainer.setVisibility(View.VISIBLE);
            mSoundContainer.setVisibility(View.VISIBLE);
            
            mBtnReset.setVisibility(View.GONE);
            mBtnRestart.setVisibility(View.GONE);
            mBtnPrimaryAction.setText("START");
            
            mTextTimerState.setText("READY");
            
            stopPulseAnimation();
            
            // Read from inputs to show initial display
            long currentSelected = getDurationFromInputs();
            updateCountdownText(currentSelected);
            mTimerProgress.setProgress(100);
        }
    }

    private void handlePrimaryAction() {
        if (!mBound || mService == null) return;

        if (mService.isTimerRunning() || mService.getTimeLeftInMillis() > 0) {
            // It is running or active. Primary action button represents "STOP"
            mService.resetTimer();
            updateUiState();
        } else {
            // Check display over other apps permission
            if (!checkOverlayPermission()) {
                requestOverlayPermission();
                return;
            }

            long duration = getDurationFromInputs();
            if (duration <= 0) {
                Toast.makeText(this, "Please select a duration greater than 0", Toast.LENGTH_SHORT).show();
                return;
            }

            Intent serviceIntent = new Intent(this, TimerService.class);
            serviceIntent.putExtra("duration", duration);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            // Temporarily update UI while binding matches
            mTextTimerState.setText("RUNNING");
            mPickerContainer.setVisibility(View.GONE);
            mPresetsContainer.setVisibility(View.GONE);
            mSoundContainer.setVisibility(View.GONE);
            mBtnReset.setVisibility(View.VISIBLE);
            mBtnRestart.setVisibility(View.VISIBLE);
            mBtnPrimaryAction.setText("STOP");
        }
    }

    private void handleReset() {
        if (mBound && mService != null) {
            mService.resetTimer();
            updateUiState();
        }
    }

    private void handleRestart() {
        if (mBound && mService != null) {
            mService.restartTimer();
            updateUiState();
        }
    }

    private void updateCountdownText(long millis) {
        int seconds = (int) (millis / 1000) % 60 ;
        int minutes = (int) ((millis / (1000*60)) % 60);
        int hours   = (int) ((millis / (1000*60*60)) % 24);

        String timeLeft;
        if (hours > 0) {
            timeLeft = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            timeLeft = String.format("%02d:%02d", minutes, seconds);
        }
        mTextTimeLeft.setText(timeLeft);
    }

    private long getDurationFromInputs() {
        try {
            int h = Integer.parseInt(mEditHours.getText().toString());
            int m = Integer.parseInt(mEditMinutes.getText().toString());
            int s = Integer.parseInt(mEditSeconds.getText().toString());
            return (h * 3600L + m * 60L + s) * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void setupInputPadding(final EditText editText) {
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String text = editText.getText().toString();
                if (text.isEmpty()) {
                    editText.setText("00");
                } else if (text.length() == 1) {
                    editText.setText("0" + text);
                }
            }
        });
        
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!mBound || !mService.isTimerRunning()) {
                    long duration = getDurationFromInputs();
                    updateCountdownText(duration);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void setupPresets() {
        findViewById(R.id.btnPreset1).setOnClickListener(v -> setPreset(0, 1, 0));
        findViewById(R.id.btnPreset5).setOnClickListener(v -> setPreset(0, 5, 0));
        findViewById(R.id.btnPreset10).setOnClickListener(v -> setPreset(0, 10, 0));
        findViewById(R.id.btnPreset15).setOnClickListener(v -> setPreset(0, 15, 0));
        findViewById(R.id.btnPreset30).setOnClickListener(v -> setPreset(0, 30, 0));
        findViewById(R.id.btnPreset60).setOnClickListener(v -> setPreset(1, 0, 0));
    }

    private void setPreset(int h, int m, int s) {
        mEditHours.setText(String.format("%02d", h));
        mEditMinutes.setText(String.format("%02d", m));
        mEditSeconds.setText(String.format("%02d", s));
        long duration = (h * 3600L + m * 60L + s) * 1000L;
        updateCountdownText(duration);
        mTimerProgress.setProgress(100);
    }

    // --- Audio Sound Picker ---
    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        startActivityForResult(intent, AUDIO_PICKER_REQ_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == AUDIO_PICKER_REQ_CODE && resultCode == RESULT_OK && data != null) {
            Uri audioUri = data.getData();
            if (audioUri != null) {
                // Grant persistable permission so background service can read it later
                try {
                    getContentResolver().takePersistableUriPermission(
                            audioUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (Exception e) {
                    Toast.makeText(this, "Could not persist file permission. Sound may default when app is closed.", Toast.LENGTH_LONG).show();
                }

                String name = getFileName(audioUri);
                
                SharedPreferences.Editor editor = getSharedPreferences("TimerPrefs", MODE_PRIVATE).edit();
                editor.putString("custom_sound", audioUri.toString());
                editor.putString("custom_sound_name", name);
                editor.apply();
                
                mTextSoundName.setText(name);
                Toast.makeText(this, "Selected: " + name, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (checkOverlayPermission()) {
                Toast.makeText(this, "Permission granted! Start timer again.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Overlay permission is required to show widget in background.", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadSavedSoundName() {
        SharedPreferences prefs = getSharedPreferences("TimerPrefs", MODE_PRIVATE);
        String name = prefs.getString("custom_sound_name", "Default Ringtone");
        mTextSoundName.setText(name);
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) {
                        result = cursor.getString(idx);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    // --- Overlay permissions ---
    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, "Enable 'Display over other apps' to use floating widget", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE);
        }
    }

    // --- Notification permissions ---
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permission required to show timer in background", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- Pulse Animations ---
    private android.view.animation.Animation mPulseAnimation;

    private void startPulseAnimation() {
        if (mPulseAnimation == null) {
            mPulseAnimation = new android.view.animation.ScaleAnimation(
                    1.0f, 1.05f, // scale from X to X
                    1.0f, 1.05f, // scale from Y to Y
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
            );
            mPulseAnimation.setDuration(800);
            mPulseAnimation.setRepeatMode(android.view.animation.Animation.REVERSE);
            mPulseAnimation.setRepeatCount(android.view.animation.Animation.INFINITE);
        }
        if (mTextTimeLeft.getAnimation() == null) {
            mTextTimeLeft.startAnimation(mPulseAnimation);
        }
    }

    private void stopPulseAnimation() {
        mTextTimeLeft.clearAnimation();
    }
}
