package com.hacha.rokidr08wake;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Boot-survivable auto-arm.
 *
 * On this non-rooted glasses ROM the app is put back into the "stopped" state on every boot, so
 * BOOT_COMPLETED / AlarmManager / JobScheduler are all gated off and never delivered (see
 * {@link BootReceiver}). An AccessibilityService is the one component the system binds directly
 * at boot -- from the {@code enabled_accessibility_services} secure setting -- so it starts even
 * from a stopped package (the bind also clears the stopped state as a side effect). The coexisting
 * R08-Access-Bridge relies on the very same mechanism on this ROM.
 *
 * This service does no accessibility work; it exists only so {@link #onServiceConnected()} fires
 * at boot and (re)arms r08waked. Enable it once in Settings > Accessibility; it then survives
 * every reboot with no manual step.
 */
public final class ArmAccessibilityService extends AccessibilityService {
    @Override
    protected void onServiceConnected() {
        Log.i(SelfArm.TAG, "accessibility connected -> arming");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> SelfArm.armLoop(app, "accessibility"), "r08-arm-a11y").start();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }
}
