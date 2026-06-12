package com.kingmr.timerapp;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements TimerService.MultiTimerListener {
    private static final int PERMISSION_REQUEST_CODE = 123;
    private static final int OVERLAY_PERMISSION_REQ_CODE = 124;
    private static final int AUDIO_PICKER_REQ_CODE = 125;

    private EditText mEditTimerLabel;
    private EditText mEditHours;
    private EditText mEditMinutes;
    private EditText mEditSeconds;

    private LinearLayout mPresetsContainer;
    private EditText mEditPresetName;
    private Button mBtnSavePreset;
    private Button mBtnCreateTimer;

    private TextView mTextSoundName;
    private Button mBtnSelectSound;

    private RecyclerView mRecyclerTimers;
    private TimerAdapter mAdapter;

    private TimerService mService;
    private boolean mBound = false;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            TimerService.LocalBinder binder = (TimerService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
            
            TimerService.setListener(MainActivity.this);
            mService.setAppInForeground(true);

            setupRecyclerView();
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

        // Permissions
        requestNotificationPermission();

        // Inputs
        mEditTimerLabel = findViewById(R.id.editTimerLabel);
        mEditHours = findViewById(R.id.editHours);
        mEditMinutes = findViewById(R.id.editMinutes);
        mEditSeconds = findViewById(R.id.editSeconds);

        mPresetsContainer = findViewById(R.id.presetsContainer);
        mEditPresetName = findViewById(R.id.editPresetName);
        mBtnSavePreset = findViewById(R.id.btnSavePreset);
        mBtnCreateTimer = findViewById(R.id.btnCreateTimer);

        mTextSoundName = findViewById(R.id.textSoundName);
        mBtnSelectSound = findViewById(R.id.btnSelectSound);

        mRecyclerTimers = findViewById(R.id.recyclerTimers);
        mRecyclerTimers.setLayoutManager(new LinearLayoutManager(this));

        // Format paddings
        setupInputPadding(mEditHours);
        setupInputPadding(mEditMinutes);
        setupInputPadding(mEditSeconds);

        // Actions
        mBtnCreateTimer.setOnClickListener(v -> handleCreateTimer());
        mBtnSavePreset.setOnClickListener(v -> handleSavePreset());
        mBtnSelectSound.setOnClickListener(v -> openAudioPicker());

        // Setup Themes click handlers
        setupThemeSelectors();

        // Setup Presets UI
        renderPresets();

        // Audio Name display
        loadSavedSoundName();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, TimerService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBound && mService != null) {
            mService.setAppInForeground(true);
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mBound && mService != null) {
            mService.setAppInForeground(false);
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
    public void onTimersUpdated() {
        runOnUiThread(() -> {
            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
        });
    }

    private void setupRecyclerView() {
        if (mService != null) {
            mAdapter = new TimerAdapter(this, mService.getTimers(), mService);
            mRecyclerTimers.setAdapter(mAdapter);
        }
    }

    private void handleCreateTimer() {
        if (!mBound || mService == null) return;

        // Check overlay permission
        if (!checkOverlayPermission()) {
            requestOverlayPermission();
            return;
        }

        long duration = getDurationFromInputs();
        if (duration <= 0) {
            Toast.makeText(this, "Please select a duration greater than 0", Toast.LENGTH_SHORT).show();
            return;
        }

        String label = mEditTimerLabel.getText().toString().trim();
        if (label.isEmpty()) {
            label = "Timer";
        }

        mService.addTimer(duration, label);
        mEditTimerLabel.setText("");
        Toast.makeText(this, "Timer added: " + label, Toast.LENGTH_SHORT).show();
    }

    // --- Themes ---
    private void setupThemeSelectors() {
        findViewById(R.id.themeSunset).setOnClickListener(v -> saveTheme("sunset"));
        findViewById(R.id.themeCyberpunk).setOnClickListener(v -> saveTheme("cyberpunk"));
        findViewById(R.id.themeOcean).setOnClickListener(v -> saveTheme("ocean"));
        findViewById(R.id.themeForest).setOnClickListener(v -> saveTheme("forest"));
    }

    private void saveTheme(String theme) {
        SharedPreferences.Editor editor = getSharedPreferences("TimerPrefs", MODE_PRIVATE).edit();
        editor.putString("selected_theme", theme);
        editor.apply();
        Toast.makeText(this, "Theme changed: " + theme, Toast.LENGTH_SHORT).show();
        
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged(); // Updates indicator colors instantly!
        }
    }

    // --- Custom Presets ---
    private void handleSavePreset() {
        String name = mEditPresetName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter preset name", Toast.LENGTH_SHORT).show();
            return;
        }
        long duration = getDurationFromInputs();
        if (duration <= 0) {
            Toast.makeText(this, "Select a valid duration first", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("TimerPrefs", MODE_PRIVATE);
        Set<String> set = prefs.getStringSet("custom_presets_set", new HashSet<>());
        Set<String> newSet = new HashSet<>(set);
        newSet.add(name + ":" + duration);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet("custom_presets_set", newSet);
        editor.apply();

        mEditPresetName.setText("");
        renderPresets();
        Toast.makeText(this, "Preset saved: " + name, Toast.LENGTH_SHORT).show();
    }

    private void renderPresets() {
        mPresetsContainer.removeAllViews();

        // Standard presets
        addPresetChip("1 Min", 60000);
        addPresetChip("5 Min", 300000);
        addPresetChip("10 Min", 600000);
        addPresetChip("30 Min", 1800000);
        addPresetChip("1 Hour", 3600000);

        // Load custom presets
        SharedPreferences prefs = getSharedPreferences("TimerPrefs", MODE_PRIVATE);
        Set<String> set = prefs.getStringSet("custom_presets_set", null);
        if (set != null) {
            for (String item : set) {
                String[] split = item.split(":");
                if (split.length == 2) {
                    try {
                        String name = split[0];
                        long duration = Long.parseLong(split[1]);
                        addPresetChip(name, duration);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void addPresetChip(String name, long duration) {
        View view = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, mPresetsContainer, false);
        TextView textView = view.findViewById(android.R.id.text1);
        textView.setText(name);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(12);
        textView.setPadding(32, 16, 32, 16);
        textView.setBackgroundResource(R.drawable.preset_chip);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 16, 0);
        textView.setLayoutParams(params);

        textView.setOnClickListener(v -> {
            int h = (int) (duration / 3600000);
            int m = (int) ((duration % 3600000) / 60000);
            int s = (int) ((duration % 60000) / 1000);
            mEditHours.setText(String.format("%02d", h));
            mEditMinutes.setText(String.format("%02d", m));
            mEditSeconds.setText(String.format("%02d", s));
            mEditTimerLabel.setText(name);
        });

        mPresetsContainer.addView(textView);
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
                try {
                    getContentResolver().takePersistableUriPermission(
                            audioUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );
                } catch (Exception e) {
                    Toast.makeText(this, "Could not persist permissions.", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "Overlay permission is required to show widgets.", Toast.LENGTH_LONG).show();
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

    // --- Overlay drawing permissions ---
    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(this, "Enable 'Display over other apps' to use floating widgets", Toast.LENGTH_LONG).show();
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
}
