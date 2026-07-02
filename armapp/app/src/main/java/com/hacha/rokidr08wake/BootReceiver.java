package com.hacha.rokidr08wake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * On boot, (re)arm r08waked via loopback ADB. Kept as a belt-and-suspenders path: on this glasses
 * ROM the app is put back into the "stopped" state on every boot, so BOOT_COMPLETED is usually
 * NOT delivered here -- {@link ArmAccessibilityService} is the reliable boot-time trigger. This
 * still fires on ROMs/boots where the broadcast does arrive.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Log.i(SelfArm.TAG, "BOOT_COMPLETED -> arming");
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                SelfArm.armLoop(app, "boot");
            } finally {
                pending.finish();
            }
        }, "r08-arm-boot").start();
    }
}
