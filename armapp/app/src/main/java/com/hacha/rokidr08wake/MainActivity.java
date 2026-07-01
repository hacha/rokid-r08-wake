package com.hacha.rokidr08wake;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Minimal glasses UI. Opening the app (a) un-stops it so BOOT_COMPLETED is delivered on future
 * boots, and (b) auto-arms once. A line-framed "Arm" control re-arms on demand.
 *
 * Glasses display rules applied: solid black == see-through (no coloured background), the whole
 * UI is confined to the top 70% (the region visible when the glasses are worn), and the layout is
 * composed only of black + white lines + text (no filled widget chrome).
 */
public final class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Solid black window == see-through on the glasses (also covers the launch transition).
        getWindow().setBackgroundDrawableResource(android.R.color.black);

        final float d = getResources().getDisplayMetrics().density;
        final int pad = (int) (16 * d);

        // Root fills the whole screen; only the top 70% carries content, bottom 30% stays empty.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        // ---- top 70%: the visible area ----
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("R08 WAKE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setLetterSpacing(0.08f);

        View rule = new View(this);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (1.5f * d));
        ruleLp.topMargin = (int) (8 * d);
        ruleLp.bottomMargin = (int) (16 * d);
        rule.setLayoutParams(ruleLp);
        rule.setBackgroundColor(Color.WHITE);

        TextView arm = new TextView(this);
        arm.setText("Arm now");
        arm.setTextSize(16);
        arm.setGravity(Gravity.CENTER);
        arm.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), (int) (10 * d));
        arm.setClickable(true);
        arm.setFocusable(true);
        arm.setDefaultFocusHighlightEnabled(false); // no green DPAD glow on the black view
        arm.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyButtonStyle(arm, false, d);
        arm.setOnFocusChangeListener((v, hasFocus) -> applyButtonStyle((TextView) v, hasFocus, d));
        arm.setOnClickListener(v -> runArm());

        status = new TextView(this);
        status.setTextColor(0xFFCCCCCC);
        status.setTextSize(15);
        status.setLineSpacing(0, 1.15f);
        status.setText("Tap Arm to (re)launch r08waked via loopback ADB (127.0.0.1:5555).");

        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); // fills the rest of the top-70% band
        svLp.topMargin = (int) (16 * d);
        sv.setLayoutParams(svLp);
        sv.addView(status);

        content.addView(title);
        content.addView(rule);
        content.addView(arm);
        content.addView(sv);

        // 70 / 30 vertical split: content on top, empty (see-through) band at the bottom.
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 70f));
        root.addView(new View(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 30f));

        setContentView(root);

        // auto-arm on launch
        runArm();
    }

    /** Line-framed control: black fill + white border by default, inverts to white when focused. */
    private static void applyButtonStyle(TextView btn, boolean focused, float d) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(3 * d);
        bg.setStroke((int) (1.5f * d), Color.WHITE);
        bg.setColor(focused ? Color.WHITE : Color.BLACK);
        btn.setBackground(bg);
        btn.setTextColor(focused ? Color.BLACK : Color.WHITE);
    }

    private void runArm() {
        status.setText("Arming…");
        new Thread(() -> {
            String result = SelfArm.arm(getApplicationContext());
            runOnUiThread(() -> status.setText(result));
        }).start();
    }
}
