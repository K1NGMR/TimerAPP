package com.kingmr.timerapp;

public class TimerModel {
    private String id;
    private long initialDuration;
    private long remainingDuration;
    private boolean running;
    private String label;

    public TimerModel(String id, long duration, String label) {
        this.id = id;
        this.initialDuration = duration;
        this.remainingDuration = duration;
        this.running = false;
        this.label = (label == null || label.trim().isEmpty()) ? "Timer" : label;
    }

    public String getId() {
        return id;
    }

    public long getInitialDuration() {
        return initialDuration;
    }

    public long getRemainingDuration() {
        return remainingDuration;
    }

    public void setRemainingDuration(long remainingDuration) {
        this.remainingDuration = remainingDuration;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
