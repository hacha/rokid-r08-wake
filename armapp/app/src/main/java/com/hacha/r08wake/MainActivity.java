package com.hacha.r08wake;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Minimal UI. Opening the app (a) un-stops it so BOOT_COMPLETED is delivered on future boots,
 * and (b) auto-arms once. A button re-arms on demand.
 */
public final class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("R08 Wake — self-arm");
        title.setTextSize(20);

        Button arm = new Button(this);
        arm.setText("Arm now");
        arm.setOnClickListener(v -> runArm());

        status = new TextView(this);
        status.setTextSize(13);
        status.setText("Tap Arm to (re)launch r08waked via loopback ADB (127.0.0.1:5555).");

        ScrollView sv = new ScrollView(this);
        sv.addView(status);

        root.addView(title);
        root.addView(arm);
        root.addView(sv);
        setContentView(root);

        // auto-arm on launch
        runArm();
    }

    private void runArm() {
        status.setText("Arming…");
        new Thread(() -> {
            String result = SelfArm.arm(getApplicationContext());
            runOnUiThread(() -> status.setText(result));
        }).start();
    }
}
