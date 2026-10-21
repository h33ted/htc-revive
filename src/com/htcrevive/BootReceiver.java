package com.htcrevive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Prefs.enabled(context)) {
            context.startService(new Intent(context, ReviveService.class));
        }
    }
}
