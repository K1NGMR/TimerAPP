package com.kingmr.timerapp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.progressindicator.CircularProgressIndicator;

public class MainActivity extends AppCompatActivity implements TimerService.TimerListener {
    private static final int PERMISSION_REQUEST_CODE = 123;

    private TextView mTextTimeLeft;
    private TextView mTextTimerState;
    private CircularProgressIndicator mTimerProgress;
    
    private EditText mEditHours;
    private EditText mEditMinutes;
    private EditText mEditSeconds;
    
    private LinearLayout mPickerContainer;
    private LinearLayout mPresetsContainer;
    
    private Button mBtnPrimaryAction;
    private Button mBtnReset;
    private Button mBtnRestart;
    
    private TimerService mService;
    private boolean mBound = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            TimerService.LocalBinder binder = (TimerService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            TimerService.setListener(MainActivity.this);
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
        
        mBtnPrimaryAction = findViewById(R.id.btnPrimaryAction);
        mBtnReset = findViewById(R.id.btnReset);
        mBtnRestart = findViewById(R.id.btnRestart);

        // Add formatter logic to input fields (auto pad with 0s)
        setupInputPadding(mEditHours);
        setupInputPadding(mEditMinutes);
        setupInputPadding(mEditSeconds);

        // Setup actions
        mBtnPrimaryAction.setOnClickListener(v -> handlePrimaryAction());
        mBtnReset.setOnClickListener(v -> handleReset());
        mBtnRestart.setOnClickListener(v -> handleRestart());

        // Setup presets
        setupPresets();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to TimerService
        Intent intent = new Intent(this, TimerService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
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
            
            mBtnReset.setVisibility(View.VISIBLE);
            mBtnRestart.setVisibility(View.VISIBLE);
            mBtnPrimaryAction.setText("STOP");
            
            mTextTimerState.setText(running ? "RUNNING" : "PAUSED");
            updateCountdownText(timeLeft);
            
            if (initial > 0) {
                int progress = (int) (timeLeft * 100 / initial);
                mTimerProgress.setProgress(progress);
            }
        } else {
            // Timer stopped / completed
            mPickerContainer.setVisibility(View.VISIBLE);
            mPresetsContainer.setVisibility(View.VISIBLE);
            
            mBtnReset.setVisibility(View.GONE);
            mBtnRestart.setVisibility(View.GONE);
            mBtnPrimaryAction.setText("START");
            
            mTextTimerState.setText("READY");
            
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
            // It is stopped. Primary action button represents "START"
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
                // Instantly update layout countdown preview text when user changes digits
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
}
