package com.hacha.rokidr08wake;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.flyfishxu.kadb.Kadb;
import com.flyfishxu.kadb.cert.KadbCert;
import com.flyfishxu.kadb.cert.KadbCertPolicy;
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore;
import com.flyfishxu.kadb.shell.AdbShellResponse;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;

/**
 * Self-arm: connect to the glasses' OWN adbd over loopback (127.0.0.1:5555, made to listen
 * at boot via persist.adb.tcp.port) using a bundled, already-trusted adb key, then deploy and
 * launch the r08waked daemon as the shell user. This is the only way to (re)start a shell-uid
 * process after a reboot on a non-rooted build without an external host (no companion / no USB).
 */
final class SelfArm {
    static final String TAG = "RokidR08WakeArm";

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    private static final String REMOTE = "/data/local/tmp/r08waked";

    private static boolean certConfigured;

    /** Connect over loopback and (re)deploy + launch r08waked. Returns a status string. */
    static synchronized String arm(Context ctx) {
        Kadb kadb = null;
        try {
            configureCert(ctx);
            String enc = Base64.encodeToString(readAsset(ctx, "r08waked"), Base64.NO_WRAP);

            kadb = new Kadb(HOST, PORT, 5000, 15000);
            // sanity probe (also forces the connection + key auth)
            String probe = kadb.shell("echo r08").getOutput().trim();
            if (!probe.endsWith("r08")) {
                return "arm FAILED: adb probe mismatch (" + probe + ")";
            }
            // deploy (idempotent) + launch as shell uid
            kadb.shell("printf '%s' '" + enc + "' | base64 -d > " + REMOTE);
            kadb.shell("chmod 755 " + REMOTE);
            kadb.shell("pkill r08waked >/dev/null 2>&1 || true");
            kadb.shell("setsid " + REMOTE + " >/data/local/tmp/r08waked.log 2>&1 </dev/null &");

            AdbShellResponse ps = kadb.shell("ps -A | grep r08waked | grep -v grep");
            String running = ps.getOutput().trim();
            String msg = running.isEmpty()
                    ? "armed (launch sent; daemon not yet visible)"
                    : "armed OK: " + firstLine(running);
            Log.i(TAG, msg);
            return msg;
        } catch (Throwable t) {
            Log.w(TAG, "arm failed", t);
            return "arm FAILED: " + t.getMessage();
        } finally {
            if (kadb != null) {
                try { kadb.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static synchronized void configureCert(Context ctx) throws Exception {
        if (certConfigured) return;
        File dir = new File(ctx.getFilesDir(), "kadb");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create kadb dir");
        }
        File pk = new File(dir, "adbkey.pem");
        if (!pk.isFile()) {
            byte[] key = readAsset(ctx, "adbkey.pem");
            try (FileOutputStream o = new FileOutputStream(pk)) { o.write(key); }
        }
        KadbCert.INSTANCE.configure(
                new OkioFilePrivateKeyStore(
                        okio.Path.Companion.get(pk.getAbsolutePath()),
                        okio.FileSystem.SYSTEM),
                new KadbCertPolicy(),
                Collections.emptyList());
        certConfigured = true;
    }

    private static byte[] readAsset(Context ctx, String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private SelfArm() { }
}
