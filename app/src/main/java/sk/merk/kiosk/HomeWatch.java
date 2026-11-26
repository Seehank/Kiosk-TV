package sk.merk.kiosk;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * This TCL firmware always opens Google's TV home at power on, whatever home is chosen, and does
 * not deliver the boot broadcast to new apps. This service only notices the TV's own home screen
 * coming forward (it does not read the screen) and puts the kiosk back on top.
 * Android starts it at every boot once it is switched on in Settings, Accessibility.
 */
public class HomeWatch extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(MainActivity.TAG, "home watch on");
        bringKiosk();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        // Switched off for a while (Back x5 in the kiosk): let the person work in the TV's own menus.
        if (System.currentTimeMillis() < getSharedPreferences("kiosk", MODE_PRIVATE).getLong("watchOffUntil", 0)) return;
        // res/xml/home_watch.xml lists only the TV's own home screens, so any event is one of them.
        Log.i(MainActivity.TAG, "TV home came forward (" + pkg + "), back to the kiosk");
        bringKiosk();
    }

    private void bringKiosk() {
        if (System.currentTimeMillis() < getSharedPreferences("kiosk", MODE_PRIVATE).getLong("watchOffUntil", 0)) return;
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(MainActivity.TAG, "could not start kiosk", e);
        }
    }

    @Override
    public void onInterrupt() { }
}
