package sk.merk.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Device owner hook. Made device owner from a computer with
 *   adb shell dpm set-device-owner sk.merk.kiosk/.Admin
 * it lets the kiosk update itself without anyone pressing Install on the TV.
 */
public class Admin extends DeviceAdminReceiver {

    static boolean isOwner(Context ctx) {
        DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(ctx.getPackageName());
    }

    /**
     * Undo: gives up device owner. Only the computer's ADB shell can send it (the receiver needs
     * android.permission.DUMP, which apps do not get):
     *   adb shell am broadcast -n sk.merk.kiosk/.Admin\$Release
     */
    public static class Release extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                dpm.clearDeviceOwnerApp(context.getPackageName());
                Log.i(Updater.TAG, "device owner released");
            }
            if (dpm != null) {
                try { dpm.removeActiveAdmin(new ComponentName(context, Admin.class)); } catch (Exception ignored) { }
            }
            setResultData("released");
        }
    }
}
