package com.htcrevive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-applies the redirects at boot (stock S-ON kernels may drop /system edits). */
public class LegacyBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (LegacyService.enabled(ctx)) ctx.startService(new Intent(ctx, LegacyService.class));
    }
}
