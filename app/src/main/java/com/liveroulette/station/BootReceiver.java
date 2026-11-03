package com.liveroulette.station;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the station app after the device boots (older Android; newer Android uses the Home-app setting). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent start = new Intent(context, MainActivity.class);
            start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(start);
        } catch (Exception ignored) {
        }
    }
}
