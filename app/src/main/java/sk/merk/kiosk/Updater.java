package sk.merk.kiosk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Self update. Reads UPDATE_URL, a small JSON file:
 *   {"versionCode": 3, "versionName": "1.2", "apk": "https://…/merk-kiosk-3.apk", "sha256": "…",
 *    "startUrl": "https://…/landing/"}
 * When versionCode is higher than ours, downloads the APK, checks its SHA-256 and installs it.
 * As device owner Android installs it silently; otherwise the TV shows Android's Install screen.
 * Android only accepts an update signed with the same key as the installed app.
 */
final class Updater {
    static final String TAG = "MerkKiosk";
    static final String UPDATE_URL = BuildConfig.UPDATE_URL;

    private Updater() { }

    /** Runs on a background thread. Returns a short line for the log. */
    static String check(Context ctx, SharedPreferences prefs) {
        try {
            if (UPDATE_URL.isEmpty()) return "no update url";
            JSONObject info = new JSONObject(MainActivity.get(UPDATE_URL));
            // The landing page address can be changed here without a new app version.
            String start = info.optString("startUrl", "");
            if (!start.equals(prefs.getString("startUrl", ""))) prefs.edit().putString("startUrl", start).apply();
            // How often TVs look for page changes (seconds, at least 15). Lets you slow them down
            // from the server if there are many TVs, without a new app version.
            long pollMs = Math.max(15, Math.min(info.optLong("pollSeconds", 15), 600)) * 1000;
            if (pollMs != prefs.getLong("pollMs", 15_000)) prefs.edit().putLong("pollMs", pollMs).apply();
            int remote = info.getInt("versionCode");
            PackageInfo me = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (remote <= me.versionCode) return "up to date (" + me.versionCode + ")";
            File apk = download(ctx, info.getString("apk"));
            String want = info.optString("sha256", "").toLowerCase();
            String got = sha256(apk);
            if (!want.isEmpty() && !want.equals(got)) {
                apk.delete();
                return "sha256 mismatch, not installed";
            }
            install(ctx, apk);
            return "installing " + remote;
        } catch (Exception e) {
            Log.w(TAG, "update check failed", e);
            return "update check failed: " + e.getMessage();
        }
    }

    private static File download(Context ctx, String url) throws Exception {
        File out = new File(ctx.getCacheDir(), "update.apk");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", MainActivity.USER_AGENT_TAG + " (Android TV)");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
        return out;
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void install(Context ctx, File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("kiosk.apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent status = new Intent(ctx, Status.class);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pending.getIntentSender());
        }
    }

    /** Install result. Without device owner Android asks first: show its screen on the TV. */
    public static class Status extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else {
                Log.i(TAG, "install status " + status + " "
                        + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }
}
