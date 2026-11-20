package com.integratedresults.logoshortcut;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * TEST plugin (Nov 2026): creates an Android home-screen pinned shortcut that
 * carries the signed-in company's logo, so tapping it opens this app directly.
 *
 * How it works:
 * - Capacitor loads this plugin eagerly at bridge start (it is listed in
 *   capacitor.plugins.json by `npx cap sync android`), so load() runs at app
 *   launch with no JavaScript call from the web app needed.
 * - load() starts a periodic check: native code evaluates a small defensive
 *   script in the WebView that looks for the company logo image in the
 *   signed-in dashboard (sidebar logo) and returns its URL + company name.
 *   The web app's own code is NOT changed.
 * - When a logo URL is found, the logo is downloaded and
 *   ShortcutManagerCompat.requestPinShortcut() asks the user (system dialog) to
 *   add the shortcut to the home screen. This happens ONCE per install
 *   (SharedPreferences flag); declining the dialog means it will not ask
 *   again until the app is reinstalled or its data is cleared.
 * - The shortcut's intent is the app's own launch intent, so tapping it
 *   opens the mobile app directly (not a browser tab).
 *
 * Limits of this test:
 * - Only raster logos (PNG/JPG/WebP) work; BitmapFactory cannot decode SVG.
 * - The detection script guesses the logo from the live page's DOM; if the
 *   web app's markup changes, the guess can miss and no shortcut is offered.
 * - The shortcut icon is a pinned shortcut, not the app's launcher icon;
 *   the launcher icon itself still comes from the APK build.
 */
@CapacitorPlugin(name = "LogoShortcut")
public class LogoShortcutPlugin extends Plugin {

    private static final String TAG = "LogoShortcut";
    private static final String PREFS_NAME = "logo-shortcut-prefs";
    private static final String KEY_CREATED = "shortcut_created";
    private static final String KEY_LOGO_URL = "shortcut_logo_url";
    private static final String SHORTCUT_ID = "company-logo-shortcut";
    private static final long CHECK_INTERVAL_MS = 15000;
    private static final long FIRST_CHECK_DELAY_MS = 5000;
    private static final int MAX_ATTEMPTS = 40; // ~10 minutes of checking
    private static final int MAX_ICON_PX = 512;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int attempts = 0;
    private boolean loopStarted = false;
    private boolean stopped = false;

    /**
     * Defensive logo detection, run inside the WebView. Returns a JSON string
     * {"url": "<absolute logo url>", "label": "<company name>"} or null when
     * the user is not on a signed-in dashboard or no logo can be found.
     * Signals used (best-effort, any of them may change with the web app):
     * - the page is not a login/signup/register screen (hash route check),
     * - a sidebar/nav exists (only present once signed in),
     * - an <img> whose src/alt/class mentions "logo", preferably inside the
     *   sidebar, plus a localStorage value whose key mentions "logo".
     */
    private static final String DETECT_JS =
        "(function(){"
        + "try{"
        + "var hash=(window.location.hash||'').toLowerCase();"
        + "if(hash.indexOf('login')!==-1||hash.indexOf('signup')!==-1||hash.indexOf('register')!==-1)return null;"
        + "var hasNav=!!document.querySelector('aside, nav, [class*=\"sidebar\" i]');"
        + "if(!hasNav)return null;"
        + "function abs(u){try{return new URL(u,window.location.href).href;}catch(e){return null;}}"
        + "function usable(s){if(!s)return false;var x=s.toLowerCase();return x.indexOf('http://')===0||x.indexOf('https://')===0||x.indexOf('data:image')===0;}"
        + "var best=null;"
        + "function consider(src,score){if(!usable(src))return;var u=abs(src);if(!u)return;if(!best||score>best.score)best={url:u,score:score};}"
        + "var imgs=document.querySelectorAll('img');"
        + "for(var i=0;i<imgs.length;i++){"
        + "var im=imgs[i];var src=im.getAttribute('src')||im.currentSrc||'';"
        + "var alt=(im.getAttribute('alt')||'').toLowerCase();"
        + "var cls=(im.getAttribute('class')||'').toLowerCase();"
        + "var score=0;"
        + "if(src.toLowerCase().indexOf('logo')!==-1)score+=3;"
        + "if(alt.indexOf('logo')!==-1)score+=3;"
        + "if(cls.indexOf('logo')!==-1)score+=2;"
        + "var p=im.parentElement,d=0;"
        + "while(p&&d<6){var t=(p.tagName||'').toLowerCase();var pc=(p.getAttribute('class')||'').toLowerCase();"
        + "if(t==='aside'||t==='nav'||pc.indexOf('sidebar')!==-1){score+=2;break;}p=p.parentElement;d++;}"
        + "if(score>0)consider(src,score);}"
        + "try{for(var k=0;k<window.localStorage.length;k++){var key=window.localStorage.key(k)||'';var val=window.localStorage.getItem(key)||'';if(key.toLowerCase().indexOf('logo')!==-1&&usable(val))consider(val,1);}}catch(e){}"
        + "if(!best)return null;"
        + "var label='';"
        + "try{"
        + "var logoImg=null;"
        + "for(var j=0;j<imgs.length;j++){var s2=imgs[j].getAttribute('src')||'';if(s2&&abs(s2)===best.url){logoImg=imgs[j];break;}}"
        + "if(logoImg){var el=logoImg.parentElement,dd=0;"
        + "while(el&&dd<4){var tx=(el.textContent||'').replace(/\\s+/g,' ').trim();"
        + "if(tx&&tx.length>1&&tx.length<80){label=tx.split('Operations')[0].trim();break;}el=el.parentElement;dd++;}}"
        + "}catch(e){}"
        + "if(!label)label=(document.title||'Integrated Results').replace(/\\s+/g,' ').trim();"
        + "return JSON.stringify({url:best.url,label:label.slice(0,30)});"
        + "}catch(e){return null;}"
        + "})()";

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            if (stopped || isCreated()) {
                return;
            }
            attempts++;
            if (attempts > MAX_ATTEMPTS) {
                Log.i(TAG, "Giving up logo detection after " + MAX_ATTEMPTS + " attempts");
                return;
            }
            runDetection();
            mainHandler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void load() {
        // Called when the plugin is first constructed in the bridge (app start).
        if (isCreated()) {
            Log.i(TAG, "Logo shortcut already created for this install");
            return;
        }
        startLoop();
    }

    @Override
    protected void handleOnResume() {
        // Kick a check when the app comes back to the foreground (e.g. the
        // user just signed in), if the loop is not already running.
        if (!isCreated() && !loopStarted) {
            startLoop();
        }
    }

    private void startLoop() {
        if (loopStarted) {
            return;
        }
        loopStarted = true;
        mainHandler.postDelayed(checkRunnable, FIRST_CHECK_DELAY_MS);
    }

    private boolean isCreated() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_CREATED, false);
    }

    private void markCreated(String logoUrl) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_CREATED, true).putString(KEY_LOGO_URL, logoUrl).apply();
    }

    private void runDetection() {
        if (getBridge() == null) {
            return;
        }
        final WebView webView = getBridge().getWebView();
        if (webView == null) {
            return;
        }
        // evaluateJavascript must run on the UI thread.
        webView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    webView.evaluateJavascript(DETECT_JS, new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            onDetectionResult(value);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "Logo detection failed: " + e.getMessage());
                }
            }
        });
    }

    private void onDetectionResult(String value) {
        if (value == null || "null".equals(value)) {
            return; // Not signed in yet, or no logo found — keep checking.
        }
        try {
            Object parsed = new JSONTokener(value).nextValue();
            if (!(parsed instanceof String)) {
                return;
            }
            JSONObject obj = new JSONObject((String) parsed);
            final String logoUrl = obj.optString("url", null);
            final String label = obj.optString("label", "Integrated Results");
            if (logoUrl == null || logoUrl.isEmpty()) {
                return;
            }
            Log.i(TAG, "Found company logo: " + logoUrl + " (" + label + ")");
            stopped = true;
            mainHandler.removeCallbacks(checkRunnable);
            downloadAndCreate(logoUrl, label, null);
        } catch (Exception e) {
            Log.w(TAG, "Could not parse logo detection result: " + e.getMessage());
        }
    }

    private Bitmap fetchBitmap(String logoUrl) throws Exception {
        if (logoUrl.startsWith("data:image")) {
            String base64 = logoUrl.substring(logoUrl.indexOf(",") + 1);
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
        URL url = new URL(logoUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        try {
            InputStream in = conn.getInputStream();
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static Bitmap scaleBitmap(Bitmap bitmap, int maxPx) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w <= 0 || h <= 0 || (w <= maxPx && h <= maxPx)) {
            return bitmap;
        }
        float scale = Math.min((float) maxPx / w, (float) maxPx / h);
        return Bitmap.createScaledBitmap(bitmap, Math.round(w * scale), Math.round(h * scale), true);
    }

    private void downloadAndCreate(final String logoUrl, final String label, final PluginCall call) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap;
                try {
                    bitmap = fetchBitmap(logoUrl);
                } catch (Exception e) {
                    Log.w(TAG, "Logo download failed: " + e.getMessage());
                    if (call != null) {
                        call.reject("Could not download logo: " + e.getMessage());
                    }
                    return;
                }
                if (bitmap == null) {
                    Log.w(TAG, "Logo could not be decoded (SVG logos are not supported)");
                    if (call != null) {
                        call.reject("Logo could not be decoded (SVG logos are not supported)");
                    }
                    return;
                }
                final Bitmap icon = scaleBitmap(bitmap, MAX_ICON_PX);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean shown = requestPin(icon, label);
                        if (call != null) {
                            if (shown) {
                                JSObject ret = new JSObject();
                                ret.put("created", true);
                                call.resolve(ret);
                            } else {
                                call.reject("Pinned shortcuts are not supported on this device/launcher");
                            }
                        } else if (shown) {
                            markCreated(logoUrl);
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * Asks Android to pin the shortcut. Returns true when the system shows
     * the user the confirmation dialog. Uses the AndroidX compat APIs so the
     * plugin compiles without the framework android.graphics.Icon class.
     */
    private boolean requestPin(Bitmap bitmap, String label) {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            Log.i(TAG, "Pinned shortcuts are not supported on this device/launcher");
            return false;
        }
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent == null) {
            Log.w(TAG, "No launch intent for this app");
            return false;
        }
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(IconCompat.createWithBitmap(bitmap))
                .setIntent(launchIntent)
                .build();
        return ShortcutManagerCompat.requestPinShortcut(context, info, null);
    }

    /**
     * JavaScript-callable: createShortcut({ logoUrl, label }). The automatic
     * detection above is the primary path; this exists so the web app (or a
     * future test page) can trigger the same flow explicitly.
     */
    @PluginMethod
    public void createShortcut(PluginCall call) {
        String logoUrl = call.getString("logoUrl");
        String label = call.getString("label", "Integrated Results");
        if (logoUrl == null || logoUrl.isEmpty()) {
            call.reject("logoUrl is required");
            return;
        }
        downloadAndCreate(logoUrl, label, call);
    }

    /**
     * JavaScript-callable: isRequestPinShortcutSupported() -> { supported }.
     */
    @PluginMethod
    public void isRequestPinShortcutSupported(PluginCall call) {
        Context context = getContext();
        boolean supported = context != null && ShortcutManagerCompat.isRequestPinShortcutSupported(context);
        JSObject ret = new JSObject();
        ret.put("supported", supported);
        call.resolve(ret);
    }
}
