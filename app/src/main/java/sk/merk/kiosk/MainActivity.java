package sk.merk.kiosk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full screen kiosk for the TV.
 *
 * First start: opens the landing page (WordPress), where someone picks the location and screen.
 * The first page opened from the landing page is remembered and shown after every power on.
 * Back pressed 3 times quickly goes back to the landing page to pick again.
 * The page reloads by itself when it is saved in WordPress (its modified time changes).
 */
public class MainActivity extends Activity {

    static final String TAG = "MerkKiosk";
    static final String PICKER = "file:///android_asset/picker.html";   // used while there is no landing page
    static final String OFFLINE = "file:///android_asset/offline.html";
    static final String USER_AGENT_TAG = "MerkKiosk/" + BuildConfig.VERSION_NAME;   // lets the server recognise (and allow) the TVs

    static final long POLL_MS = 15_000;                  // ask WordPress for changes
    static final long OFFLINE_RETRY_MS = 15_000;
    static final long DAILY_RELOAD_MS = 24L * 3600_000;  // fresh load once a day keeps memory low
    static final long UPDATE_FIRST_MS = 60_000;          // first update check after start
    static final long UPDATE_EVERY_MS = 6L * 3600_000;   // then every 6 hours
    static final long POLL_MAX_MS = 300_000;             // polling slows down to every 5 min while the server fails
    static final int  STORM_LIMIT = 20;                  // more than this many page starts ...
    static final long STORM_WINDOW_MS = 30_000;          // ... within this time = something is looping
    static final long STORM_PAUSE_MS = 60_000;           // then stop loading for 1 min
    // (a person switching menus makes a few page starts a minute; the bug made about 900 in 20 s)
    static final int DESIGN_WIDTH = 1920;                // CSS width the pages are laid out at
    static final int HELPER_OFF_PRESSES = 5;              // Back x5 switches the home watch off ...
    static final long HELPER_OFF_WINDOW_MS = 6_000;
    // ... until the kiosk is opened again (onResume switches it back on)
    static final int ESCAPE_PRESSES = 3;                 // Back x3 ...
    static final long ESCAPE_WINDOW_MS = 4_000;          // ... within this time opens the landing page

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private WebView web;
    private SharedPreferences prefs;

    private boolean showingOffline;
    private boolean onLanding;        // landing page (or built-in picker) is on screen
    private String lastModified;      // modified_gmt of the screen page
    private long loadedAt;
    private String finishedUrl;       // last URL handled in onPageFinished
    private long pollBase = POLL_MS;  // polling interval wanted (update.json "pollSeconds")
    private long pollDelay = POLL_MS; // current polling interval, grows on errors
    private long offlineDelay = OFFLINE_RETRY_MS; // doubles while the server keeps failing
    private long errorDelay = 30_000; // delay before retrying after an HTTP 5xx page
    private final java.util.ArrayDeque<Long> pageStarts = new java.util.ArrayDeque<>();
    private long pausedUntil;         // no page loads before this time (circuit breaker)
    private int backPresses;
    private int offPresses;
    private long firstOffPressAt;
    private long firstBackAt;
    private ConnectivityManager.NetworkCallback netCallback;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("kiosk", MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        createWebView();
        watchNetwork();
        showTarget();
        ui.postDelayed(poller, jitter(POLL_MS));
        ui.postDelayed(updateCheck, UPDATE_FIRST_MS + (long) (Math.random() * 120_000));
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.edit().remove("watchOffUntil").apply();   // kiosk in front again: the helper is back on
        ensureHelperEnabled();
        hideSystemUi();
        if (showingOffline && isOnline()) showTarget();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Display density or size changed (wm density ...): the page size is worked out from them,
        // so build the web view again. Rare, so one page load is fine.
        createWebView();
        showTarget();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideSystemUi();   // Home key: stay on the menu
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (netCallback != null) {
            try { connectivity().unregisterNetworkCallback(netCallback); } catch (Exception ignored) { }
        }
        net.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---------------------------------------------------------------- addresses

    private String startUrl() {
        String s = prefs.getString("startUrl", null);           // from update.json
        if (s == null || s.isEmpty()) s = BuildConfig.START_URL;  // built into the app
        return (s == null || s.isEmpty()) ? PICKER : s;
    }

    private static String norm(String url) {
        if (url == null) return "";
        Uri u = Uri.parse(url);
        String path = u.getPath() == null ? "/" : u.getPath();
        if (!path.endsWith("/")) path += "/";
        return (u.getHost() == null ? "" : u.getHost().toLowerCase()) + path;
    }

    private static boolean isWeb(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void createWebView() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setUserAgentString(s.getUserAgentString() + " " + USER_AGENT_TAG);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Kiosk");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("http".equals(scheme) || "https".equals(scheme) || "file".equals(scheme));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                Log.i(TAG, "load " + url + " landing=" + onLanding);
                finishedUrl = null;
                if (isWeb(url) && tooManyLoads()) {
                    view.stopLoading();
                    pauseLoading();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 500) {
                    Log.w(TAG, "server answered " + errorResponse.getStatusCode() + ", retry in " + errorDelay / 1000 + " s");
                    final long wait = errorDelay;
                    errorDelay = Math.min(errorDelay * 2, POLL_MAX_MS);
                    ui.removeCallbacks(retryAfterError);
                    ui.postDelayed(retryAfterError, wait);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Some pages (sliders changing the #hash) report "finished" many times a second.
                if (url != null && url.equals(finishedUrl)) return;
                finishedUrl = url;
                Log.i(TAG, "done " + url);
                if (isWeb(url)) offlineDelay = OFFLINE_RETRY_MS;   // the server answered: start over
                pageFinished(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) ui.post(MainActivity.this::showOffline);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                ui.post(() -> { createWebView(); showTarget(); });
                return true;
            }
        });
        // Menu pages are designed for a 1920 x 1080 screen. This TV's web view would be only about
        // 960 CSS pixels wide (1280 px at density 213), which gives the pages' tablet layout.
        // So the web view is made big enough to be 1920 CSS pixels wide and then scaled down to the
        // screen as a whole picture. The pages themselves are not changed in any way.
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = Math.max(dm.widthPixels, dm.heightPixels);
        int screenH = Math.min(dm.widthPixels, dm.heightPixels);
        int viewW = Math.round(DESIGN_WIDTH * dm.density);
        float scale = Math.min(1f, (float) screenW / viewW);
        int viewH = Math.round(screenH / scale);
        root.addView(web, new FrameLayout.LayoutParams(scale < 1f ? viewW : ViewGroup.LayoutParams.MATCH_PARENT,
                scale < 1f ? viewH : ViewGroup.LayoutParams.MATCH_PARENT));
        web.setPivotX(0);
        web.setPivotY(0);
        web.setScaleX(scale);
        web.setScaleY(scale);
        web.requestFocus();
    }

    /** The chosen screen, or the landing page when nothing is chosen yet, or the offline page. */
    private void showTarget() {
        if (!isOnline()) { showOffline(); return; }
        if (paused()) { if (!showingOffline) pauseLoading(); return; }
        showingOffline = false;
        String screen = prefs.getString("url", null);
        if (screen == null) {
            showLanding();
        } else {
            onLanding = false;
            lastModified = prefs.getString("modified", null);
            loadedAt = SystemClock.elapsedRealtime();
            web.loadUrl(screen);
        }
        web.requestFocus();
    }

    private void showLanding() {
        onLanding = true;
        web.loadUrl(startUrl());
        web.requestFocus();
    }

    /**
     * A page finished loading. When the landing page led to another page of the same site,
     * that page is the chosen screen: remember it.
     */
    private void pageFinished(String url) {
        if (!isWeb(url) || showingOffline) return;
        String start = startUrl();
        if (onLanding) {
            boolean sameSite = isWeb(start)
                    ? Uri.parse(url).getHost() != null && Uri.parse(url).getHost().equalsIgnoreCase(Uri.parse(start).getHost())
                    : Uri.parse(url).getHost() != null && Uri.parse(url).getHost().equalsIgnoreCase(Uri.parse(BuildConfig.BASE).getHost());
            if (!sameSite || norm(url).equals(norm(start))) return;   // still on the landing page
            choose(url);
        }
        // Find the page's WordPress REST address, used to notice when it is saved.
        web.evaluateJavascript(
                "(function(){var l=document.querySelector('link[rel=\"alternate\"][type=\"application/json\"]');"
                        + "return l?l.href:'';})()",
                v -> {
                    String api = v == null ? "" : v.replace("\"", "");
                    if (api.startsWith("http") && !onLanding) prefs.edit().putString("api", api).apply();
                });
    }

    private void choose(String url) {
        Log.i(TAG, "screen chosen: " + url);
        onLanding = false;
        lastModified = null;
        loadedAt = SystemClock.elapsedRealtime();
        prefs.edit().putString("url", url).remove("api").remove("modified").apply();
    }

    // ---------------------------------------------------------------- never flood the server

    /** True when the web view has started too many page loads in the last minute (a loop). */
    private boolean tooManyLoads() {
        long now = SystemClock.elapsedRealtime();
        pageStarts.addLast(now);
        while (!pageStarts.isEmpty() && now - pageStarts.peekFirst() > STORM_WINDOW_MS) pageStarts.removeFirst();
        return pageStarts.size() > STORM_LIMIT;
    }

    /** Circuit breaker: show the offline page and do not touch the server for 5 minutes. */
    private void pauseLoading() {
        Log.w(TAG, "too many page loads, pausing for " + STORM_PAUSE_MS / 1000 + " s");
        pausedUntil = SystemClock.elapsedRealtime() + STORM_PAUSE_MS;
        pageStarts.clear();
        showingOffline = true;
        web.loadUrl(OFFLINE);
        ui.removeCallbacks(offlineRetry);
        ui.postDelayed(offlineRetry, STORM_PAUSE_MS);
    }

    private boolean paused() { return SystemClock.elapsedRealtime() < pausedUntil; }

    private final Runnable retryAfterError = new Runnable() {
        @Override public void run() {
            if (onLanding || showingOffline || paused()) return;
            web.reload();
        }
    };

    private void showOffline() {
        if (showingOffline) return;
        showingOffline = true;
        web.loadUrl(OFFLINE);
        ui.removeCallbacks(offlineRetry);
        ui.postDelayed(offlineRetry, jitter(offlineDelay));
        offlineDelay = Math.min(offlineDelay * 2, POLL_MAX_MS);   // 15 s, 30 s, 1 min ... 5 min
    }

    private final Runnable offlineRetry = new Runnable() {
        @Override public void run() {
            if (!showingOffline) return;
            if (paused()) { ui.postDelayed(this, pausedUntil - SystemClock.elapsedRealtime() + 1000); return; }
            if (isOnline()) showTarget();
            else ui.postDelayed(this, OFFLINE_RETRY_MS);   // no network: nothing is sent, just looking
        }
    };

    // ---------------------------------------------------------------- change watch

    private final Runnable poller = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, jitter(pollDelay));   // normally pollSeconds, slower while the server fails
            final String api = prefs.getString("api", null);
            if (onLanding || showingOffline || paused() || prefs.getString("url", null) == null) return;
            if (SystemClock.elapsedRealtime() - loadedAt > DAILY_RELOAD_MS) { reloadFresh(); return; }
            if (api == null) return;
            net.execute(() -> {
                try {
                    String sep = api.contains("?") ? "&" : "?";
                    String body = get(api + sep + "_fields=modified_gmt");
                    String modified = new JSONObject(body).optString("modified_gmt", "");
                    if (modified.isEmpty()) return;
                    ui.post(() -> {
                        pollBase = Math.max(POLL_MS, prefs.getLong("pollMs", POLL_MS));
                        pollDelay = pollBase;
                        errorDelay = 30_000;
                        if (lastModified == null) {
                            remember(modified);
                        } else if (!modified.equals(lastModified)) {
                            Log.i(TAG, "page saved in WordPress, reloading");
                            remember(modified);
                            reloadFresh();
                        }
                    });
                } catch (Exception ignored) {
                    // server or network problem: keep showing what we have and ask less often
                    ui.post(() -> pollDelay = Math.min(pollDelay * 2, POLL_MAX_MS));
                }
            });
        }
    };

    /** +-20 % so that many TVs never ask at the same moment. */
    private static long jitter(long ms) {
        return Math.round(ms * (0.8 + Math.random() * 0.4));
    }

    private void remember(String modified) {
        lastModified = modified;
        prefs.edit().putString("modified", modified).apply();
    }

    private void reloadFresh() {
        if (paused()) return;
        web.clearCache(false);
        web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        loadedAt = SystemClock.elapsedRealtime();
        web.reload();
        ui.postDelayed(() -> web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT), 10_000);
    }

    // ---------------------------------------------------------------- updates

    private final Runnable updateCheck = new Runnable() {
        @Override public void run() {
            ui.postDelayed(this, jitter(UPDATE_EVERY_MS));
            net.execute(() -> Log.i(TAG, "update: " + Updater.check(MainActivity.this, prefs)));
        }
    };

    static String get(String url) throws Exception {
        Log.i(TAG, "GET " + url);   // every request the app itself makes is logged, so it can be audited
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", USER_AGENT_TAG + " (Android TV)");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ---------------------------------------------------------------- network

    private ConnectivityManager connectivity() {
        return (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private boolean isOnline() {
        ConnectivityManager cm = connectivity();
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void watchNetwork() {
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                ui.postDelayed(() -> { if (showingOffline) showTarget(); }, 2000);
            }
        };
        try { connectivity().registerDefaultNetworkCallback(netCallback); } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- remote keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackKey();
            return true;   // Back never leaves the kiosk
        }
        return super.dispatchKeyEvent(event);
    }

    private void onBackKey() {
        long now = SystemClock.elapsedRealtime();
        if (now - firstBackAt > ESCAPE_WINDOW_MS) { firstBackAt = now; backPresses = 0; }
        backPresses++;
        // Back x5: switch the home watch off (until the kiosk is opened again) and open the TV's own home, so someone can
        // work in Settings or the browser without the kiosk jumping back in front.
        if (now - firstOffPressAt > HELPER_OFF_WINDOW_MS) { firstOffPressAt = now; offPresses = 0; }
        if (++offPresses >= HELPER_OFF_PRESSES) {
            offPresses = 0;
            helperOff();
            return;
        }
        if (backPresses >= ESCAPE_PRESSES) {
            // Back x3 always works, also on the offline page and during a pause: back to the landing page.
            backPresses = 0;
            pausedUntil = 0;
            pageStarts.clear();
            showingOffline = false;
            showLanding();
            return;
        }
        if (onLanding || showingOffline) {
            // Inside the landing page Back steps back (for example from screens to locations).
            web.evaluateJavascript("window.kioskBack ? (kioskBack(), 1) : 0", v -> {
                if ("0".equals(v) && web.canGoBack() && onLanding) web.goBack();
            });
            return;
        }
    }

    /** An update switches the home watch off on this TV: switch it on again (needs WRITE_SECURE_SETTINGS). */
    private void ensureHelperEnabled() {
        try {
            String mine = getPackageName() + "/" + HomeWatch.class.getName();
            String now = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (now != null && now.contains(mine)) return;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (now == null || now.isEmpty()) ? mine : now + ":" + mine);
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "home watch switched on again");
        } catch (SecurityException e) {
            Log.w(TAG, "cannot switch the home watch on (permission not granted)");
        }
    }

    private void helperOff() {
        prefs.edit().putLong("watchOffUntil", Long.MAX_VALUE).apply();
        android.widget.Toast.makeText(this, "Pomocník vypnutý. Zapne sa, keď znova otvoríš kiosk.", android.widget.Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage("com.google.android.tvlauncher").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- JS bridge (window.Kiosk)

    class Bridge {
        @JavascriptInterface public String base() { return BuildConfig.BASE; }

        /** Address of the screen chosen now, or "". */
        @JavascriptInterface public String current() { return prefs.getString("url", ""); }

        /** Optional for the landing page: choose a screen explicitly (plain links work too). */
        @JavascriptInterface public void choose(String url) {
            if (!isWeb(url)) return;
            // Only remember it: the landing page's link does the navigation (loading it here too
            // would fetch the page twice). MainActivity.this.choose: a plain choose(url) would call
            // this very method again.
            ui.post(() -> MainActivity.this.choose(url));
        }

        /** Built-in picker (3 arguments). */
        @JavascriptInterface public void choose(String id, String url, String title) { choose(url); }  // picker built into the app

        /** Leave the landing page without changing anything. */
        @JavascriptInterface public void cancel() {
            if (prefs.getString("url", null) != null) ui.post(MainActivity.this::showTarget);
        }

        @JavascriptInterface public void retry() { ui.post(() -> { showingOffline = false; showTarget(); }); }

        @JavascriptInterface public void openWifi() { openSettings(Settings.ACTION_WIFI_SETTINGS); }

        @JavascriptInterface public void openTvSettings() { openSettings(Settings.ACTION_SETTINGS); }

        /** Built-in picker: loads the WordPress page list into window.onPages(json). */
        @JavascriptInterface public void loadPages() {
            net.execute(() -> {
                String js;
                try {
                    String body = get(BuildConfig.BASE + "/wp-json/wp/v2/pages?per_page=100"
                            + "&_fields=id,title,link,modified_gmt");
                    js = "window.onPages(" + JSONObject.quote(body) + ")";
                } catch (Exception e) {
                    js = "window.onPagesError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")";
                }
                final String call = js;
                ui.post(() -> web.evaluateJavascript(call, null));
            });
        }

        @JavascriptInterface public String version() {
            return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") · Android " + Build.VERSION.RELEASE;
        }

        private void openSettings(String action) {
            ui.post(() -> {
                try {
                    startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception ignored) { }
                }
            });
        }
    }
}
