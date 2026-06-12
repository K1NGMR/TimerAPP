package com.kingmr.timerapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class TimerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action != null) {
            Intent serviceIntent = new Intent(context, TimerService.class);
            serviceIntent.setAction(action);
            // Starting the service with the action allows the service to handle the button click.
            context.startService(serviceIntent);
        }
    }
}
