package com.hacha.r08wake;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * On boot, (re)arm r08waked via loopback ADB. Retries a few times because adbd / the ring BLE
 * may not be ready the instant BOOT_COMPLETED fires.
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
                for (int attempt = 1; attempt <= 4; attempt++) {
                    String result = SelfArm.arm(app);
                    if (result.startsWith("armed")) {
                        Log.i(SelfArm.TAG, "boot arm ok (attempt " + attempt + ")");
                        break;
                    }
                    Log.w(SelfArm.TAG, "boot arm attempt " + attempt + " -> " + result);
                    try { Thread.sleep(2500L); } catch (InterruptedException e) { break; }
                }
            } finally {
                pending.finish();
            }
        }).start();
    }
}
