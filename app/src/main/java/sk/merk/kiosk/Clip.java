package sk.merk.kiosk;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Puts a text into the TV's clipboard, so the installer can paste it into another app (for example
 * TV Bro's home page field) in one go instead of typing it letter by letter.
 * Only the computer's ADB shell can send it (the receiver needs android.permission.DUMP):
 *   adb shell am broadcast -n sk.merk.kiosk/.Clip --es text 'https://...'
 */
public class Clip extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String text = intent.getStringExtra("text");
        if (text == null) return;
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("kiosk", text));
            Log.i(MainActivity.TAG, "clipboard set (" + text.length() + " characters)");
            setResultData("ok");
        }
    }
}
