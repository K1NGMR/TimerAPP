package com.kingmr.timerapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.List;

public class TimerAdapter extends RecyclerView.Adapter<TimerAdapter.TimerViewHolder> {
    private final List<TimerModel> mTimerList;
    private final TimerService mService;
    private final Context mContext;

    public TimerAdapter(Context context, List<TimerModel> timerList, TimerService service) {
        this.mContext = context;
        this.mTimerList = timerList;
        this.mService = service;
    }

    @NonNull
    @Override
    public TimerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_timer, parent, false);
        return new TimerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TimerViewHolder holder, int position) {
        TimerModel timer = mTimerList.get(position);
        holder.txtLabel.setText(timer.getLabel());

        // Format and set countdown text
        long millis = timer.getRemainingDuration();
        int seconds = (int) (millis / 1000) % 60;
        int minutes = (int) ((millis / (1000 * 60)) % 60);
        int hours = (int) ((millis / (1000 * 60 * 60)) % 24);
        String timeStr;
        if (hours > 0) {
            timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            timeStr = String.format("%02d:%02d", minutes, seconds);
        }
        holder.txtTime.setText(timeStr);

        // Progress Calculation
        long initial = timer.getInitialDuration();
        int progress = (initial > 0) ? (int) (millis * 100 / initial) : 100;
        holder.progressIndicator.setProgress(progress);

        // Adjust Indicator color based on selected theme
        applyThemeColor(holder.progressIndicator);

        // Toggle button states
        if (timer.isRunning()) {
            holder.btnToggle.setImageResource(android.R.drawable.ic_media_pause);
            holder.statusIcon.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            holder.btnToggle.setImageResource(android.R.drawable.ic_media_play);
            holder.statusIcon.setImageResource(android.R.drawable.ic_media_play);
        }

        // Action events
        holder.btnToggle.setOnClickListener(v -> {
            if (mService != null) {
                mService.toggleTimer(timer.getId());
            }
        });

        holder.btnRestart.setOnClickListener(v -> {
            if (mService != null) {
                mService.restartTimer(timer.getId());
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (mService != null) {
                mService.removeTimer(timer.getId());
            }
        });
    }

    private void applyThemeColor(CircularProgressIndicator progressIndicator) {
        SharedPreferences prefs = mContext.getSharedPreferences("TimerPrefs", Context.MODE_PRIVATE);
        String theme = prefs.getString("selected_theme", "sunset");
        int color;
        switch (theme) {
            case "cyberpunk":
                color = Color.parseColor("#8A2387");
                break;
            case "ocean":
                color = Color.parseColor("#00E5FF");
                break;
            case "forest":
                color = Color.parseColor("#10B981");
                break;
            case "sunset":
            default:
                color = Color.parseColor("#E94057");
                break;
        }
        progressIndicator.setIndicatorColor(color);
    }

    @Override
    public int getItemCount() {
        return mTimerList.size();
    }

    public static class TimerViewHolder extends RecyclerView.ViewHolder {
        TextView txtLabel;
        TextView txtTime;
        CircularProgressIndicator progressIndicator;
        ImageView statusIcon;
        ImageView btnToggle;
        ImageView btnRestart;
        ImageView btnDelete;

        public TimerViewHolder(@NonNull View itemView) {
            super(itemView);
            txtLabel = itemView.findViewById(R.id.itemLabel);
            txtTime = itemView.findViewById(R.id.itemTimeText);
            progressIndicator = itemView.findViewById(R.id.itemProgress);
            statusIcon = itemView.findViewById(R.id.itemStatusIcon);
            btnToggle = itemView.findViewById(R.id.itemBtnToggle);
            btnRestart = itemView.findViewById(R.id.itemBtnRestart);
            btnDelete = itemView.findViewById(R.id.itemBtnDelete);
        }
    }
}
