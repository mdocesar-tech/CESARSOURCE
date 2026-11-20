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
import java.util.HashSet;
import java.util.Set;
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
 *   script in the WebView that looks for the company logo image on the
 *   signed-in page and returns its URL + company name. The web app's own
 *   code is NOT changed. No sidebar or nav element is required: phones show
 *   no sidebar, so the logo is found from any VISIBLE logo-like image on a
 *   signed-in page. Only images that are actually visible count (zero-size
 *   or hidden images, e.g. inside a collapsed switcher/picker, are skipped),
 *   and a logo URL must be seen on TWO consecutive checks before a pin is
 *   offered, so a logo that only flashes by on a transient screen can never
 *   win. localStorage is NOT consulted: a stale stored value could belong
 *   to another company. The label falls back to "Integrated Results" when
 *   no company name is found next to the logo.
 * - When a logo URL is found, the logo is downloaded and
 *   ShortcutManagerCompat.requestPinShortcut() asks the user (system dialog)
 *   to add the shortcut to the home screen.
 * - The shortcut follows the ACTIVE company: the label + logo URL of every
 *   company a shortcut was created for are stored, and a company that has
 *   no shortcut yet gets its own pin offer under a distinct shortcut id.
 *   A company that already has a shortcut is never asked twice — including
 *   when the user switches back to it later.
 * - The loop NEVER stops after the first pin: once a shortcut exists for
 *   the current company (or a pin dialog has been shown), the check keeps
 *   running on a slower 30 s cadence, and a foreground return re-checks
 *   within seconds — so an in-app company switch (which fires no resume
 *   of its own) is still picked up and offered its own shortcut.
 * - Failures never kill the flow silently: a failed download or an
 *   undecodable logo (e.g. SVG) is logged with the URL and the check loop
 *   keeps running.
 * - The shortcut's intent is the app's own launch intent, so tapping it
 *   opens the mobile app directly (not a browser tab).
 *
 * Limits of this test:
 * - Only raster logos (PNG/JPG/WebP) work; BitmapFactory cannot decode SVG.
 *   A served SVG logo is logged and retried, but will not produce a shortcut
 *   in this build.
 * - The detection script guesses the logo from the live page's DOM; if the
 *   web app's markup changes, the guess can miss and no shortcut is offered.
 * - The shortcut icon is a pinned shortcut, not the app's launcher icon;
 *   the launcher icon itself still comes from the APK build.
 */
@CapacitorPlugin(name = "LogoShortcut")
public class LogoShortcutPlugin extends Plugin {

    private static final String TAG = "LogoShortcut";
    private static final String PREFS_NAME = "logo-shortcut-prefs";
    // Per-company storage: entries are "logoUrl\u0001label".
    private static final String KEY_CREATED_COMPANIES = "shortcut_created_companies";
    // Legacy keys from the first test build (once-per-install flag). Read
    // only as a fallback when the per-company set is empty.
    private static final String KEY_CREATED_LEGACY = "shortcut_created";
    private static final String KEY_LOGO_URL_LEGACY = "shortcut_logo_url";
    private static final long CHECK_INTERVAL_MS = 15000;
    // Slower cadence once a shortcut exists for the current company (or a
    // pin dialog has been shown): the loop keeps watching for a company
    // switch, it just does not need to ask that often.
    private static final long CHECK_INTERVAL_IDLE_MS = 30000;
    private static final long FIRST_CHECK_DELAY_MS = 5000;
    private static final int MAX_ATTEMPTS = 40; // ~10 minutes of fast checking
    private static final int MAX_ICON_PX = 512;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int attempts = 0;
    private long currentIntervalMs = CHECK_INTERVAL_MS;
    private boolean loopStarted = false;
    private boolean creating = false;
    private String creatingUrl = null;
    private String lastAttemptedUrl = null;
    // Stability gate: a logo URL must be seen on two consecutive checks
    // before a pin is offered for it.
    private String lastSeenUrl = null;
    private int consecutiveCount = 0;

    /**
     * Defensive logo detection, run inside the WebView. Returns a JSON string
     * {"url": "<absolute logo url>", "label": "<company name>"} or null when
     * the user is not on a signed-in page or no logo can be found.
     * Signals used (best-effort, any of them may change with the web app):
     * - the page is not a login/signup/register screen (hash route check),
     * - the page is not the company picker ("Select a company" — no active
     *   company yet),
     * - a VISIBLE <img> whose src/alt/class mentions "logo", preferably
     *   inside a sidebar/nav when one exists (phones show no sidebar, so it
     *   is a bonus, never a requirement). Hidden images (zero-size bounding
     *   rect, display:none, visibility:hidden — including hidden switcher
     *   or picker DOM) are skipped. localStorage is deliberately NOT read:
     *   a stored logo value can be stale or belong to another company.
     *   The label falls back to "Integrated Results", never document.title
     *   (the title can name a company from another context).
     */
    private static final String DETECT_JS =
        "(function(){"
        + "try{"
        + "var hash=(window.location.hash||'').toLowerCase();"
        + "if(hash.indexOf('login')!==-1||hash.indexOf('signup')!==-1||hash.indexOf('register')!==-1)return null;"
        + "var bodyText=(document.body&&document.body.innerText)||'';"
        + "if(bodyText.indexOf('Select a company')!==-1)return null;"
        + "function abs(u){try{return new URL(u,window.location.href).href;}catch(e){return null;}}"
        + "function usable(s){if(!s)return false;var x=s.toLowerCase();return x.indexOf('http://')===0||x.indexOf('https://')===0||x.indexOf('data:image')===0;}"
        + "function isVisible(el){var r=el.getBoundingClientRect();if(!r||r.width===0||r.height===0)return false;var n=el,d=0;while(n&&d<8){var cs=null;try{cs=window.getComputedStyle(n);}catch(e){}if(!cs||cs.display==='none'||cs.visibility==='hidden')return false;n=n.parentElement;d++;}return true;}"
        + "var best=null;"
        + "function consider(src,score){if(!usable(src))return;var u=abs(src);if(!u)return;if(!best||score>best.score)best={url:u,score:score};}"
        + "var imgs=document.querySelectorAll('img');"
        + "for(var i=0;i<imgs.length;i++){"
        + "var im=imgs[i];if(!isVisible(im))continue;"
        + "var src=im.getAttribute('src')||im.currentSrc||'';"
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
        + "if(!best)return null;"
        + "var label='';"
        + "try{"
        + "var logoImg=null;"
        + "for(var j=0;j<imgs.length;j++){var s2=imgs[j].getAttribute('src')||'';if(s2&&abs(s2)===best.url){logoImg=imgs[j];break;}}"
        + "if(logoImg){var el=logoImg.parentElement,dd=0;"
        + "while(el&&dd<4){var tx=(el.textContent||'').replace(/\\s+/g,' ').trim();"
        + "if(tx&&tx.length>1&&tx.length<80){label=tx.split('Operations')[0].trim();break;}el=el.parentElement;dd++;}}"
        + "}catch(e){}"
        + "if(!label)label='Integrated Results';"
        + "return JSON.stringify({url:best.url,label:label.slice(0,30)});"
        + "}catch(e){return null;}"
        + "})()";

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            attempts++;
            if (attempts > MAX_ATTEMPTS) {
                // The fast budget is spent, but the loop NEVER stops: fall
                // back to the slow idle cadence and keep watching, so a
                // later company switch is still picked up.
                attempts = 0;
                currentIntervalMs = CHECK_INTERVAL_IDLE_MS;
                Log.i(TAG, "No new logo after " + MAX_ATTEMPTS
                        + " fast attempts; continuing to watch every "
                        + (CHECK_INTERVAL_IDLE_MS / 1000) + "s");
            }
            runDetection();
            mainHandler.postDelayed(this, currentIntervalMs);
        }
    };

    @Override
    public void load() {
        // Detection itself decides per company whether a shortcut already
        // exists for the company currently signed in, so always start.
        startLoop();
    }

    @Override
    protected void handleOnResume() {
        // A foreground return is the moment a company switch becomes
        // visible: check soon (the WebView needs a moment to settle), at
        // the fast cadence, with a fresh stability gate — a logo must be
        // seen twice in a row after the resume before a pin is offered.
        attempts = 0;
        currentIntervalMs = CHECK_INTERVAL_MS;
        lastSeenUrl = null;
        consecutiveCount = 0;
        loopStarted = true;
        mainHandler.removeCallbacks(checkRunnable);
        mainHandler.postDelayed(checkRunnable, FIRST_CHECK_DELAY_MS);
    }

    private void startLoop() {
        if (loopStarted) {
            return;
        }
        loopStarted = true;
        mainHandler.postDelayed(checkRunnable, FIRST_CHECK_DELAY_MS);
    }

    /** Companies that already have a shortcut, as "logoUrl\u0001label" entries. */
    private Set<String> getCreatedCompanies() {
        Context context = getContext();
        if (context == null) {
            return new HashSet<>();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> stored = prefs.getStringSet(KEY_CREATED_COMPANIES, null);
        if (stored != null) {
            return new HashSet<>(stored);
        }
        // Legacy fallback: the first build stored one flag + one URL.
        Set<String> migrated = new HashSet<>();
        if (prefs.getBoolean(KEY_CREATED_LEGACY, false)) {
            String url = prefs.getString(KEY_LOGO_URL_LEGACY, null);
            if (url != null && !url.isEmpty()) {
                migrated.add(url + "\u0001");
            }
        }
        return migrated;
    }

    private boolean isCreatedFor(String logoUrl, String label) {
        for (String entry : getCreatedCompanies()) {
            int sep = entry.indexOf('\u0001');
            String storedUrl = sep >= 0 ? entry.substring(0, sep) : entry;
            String storedLabel = sep >= 0 ? entry.substring(sep + 1) : "";
            if (!storedUrl.equals(logoUrl)) {
                continue;
            }
            // Legacy entries have no label; a URL match alone is enough.
            if (storedLabel.isEmpty() || storedLabel.equals(label)) {
                return true;
            }
        }
        return false;
    }

    private void markCreated(String logoUrl, String label) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> created = getCreatedCompanies();
        created.add(logoUrl + "\u0001" + label);
        prefs.edit().putStringSet(KEY_CREATED_COMPANIES, created).apply();
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
            // Not signed in yet, or no logo found — keep checking. A logo
            // that was seen once and then disappears loses its first
            // sighting: the stability gate needs two CONSECUTIVE checks.
            if (lastSeenUrl != null) {
                Log.i(TAG, "Logo no longer visible (" + lastSeenUrl
                        + "); stability gate reset");
                lastSeenUrl = null;
                consecutiveCount = 0;
            }
            return;
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
            // Stability gate: only trust a logo URL seen on two
            // consecutive checks. A logo that only flashes by on a
            // transient screen (landing page, picker, an open switcher
            // dropdown) must never win on a single frame.
            if (!logoUrl.equals(lastSeenUrl)) {
                lastSeenUrl = logoUrl;
                consecutiveCount = 1;
                Log.i(TAG, "Logo seen once, waiting for a second consecutive sighting: " + logoUrl);
                return;
            }
            consecutiveCount++;
            if (consecutiveCount < 2) {
                return;
            }
            if (isCreatedFor(logoUrl, label)) {
                // Already have a shortcut for this company: do NOT halt —
                // keep watching on the slow cadence so a later switch to
                // another company is picked up.
                Log.i(TAG, "Shortcut already exists for " + label + "; not asking again, still watching");
                attempts = 0;
                currentIntervalMs = CHECK_INTERVAL_IDLE_MS;
                return;
            }
            if (creating) {
                // A download for a logo is already in flight; the loop will
                // pick this one up on the next round.
                return;
            }
            if (!logoUrl.equals(lastAttemptedUrl)) {
                // A new logo gets a fresh retry budget.
                attempts = 0;
                lastAttemptedUrl = logoUrl;
            }
            creating = true;
            creatingUrl = logoUrl;
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
                Bitmap bitmap = null;
                String failure = null;
                try {
                    bitmap = fetchBitmap(logoUrl);
                } catch (Exception e) {
                    failure = "Could not download logo: " + e.getMessage();
                    Log.w(TAG, "Logo download failed for " + logoUrl + ": " + e.getMessage());
                }
                if (failure == null && bitmap == null) {
                    failure = "Logo could not be decoded (SVG logos are not supported)";
                    Log.w(TAG, "Logo could not be decoded for " + logoUrl
                            + " (SVG logos are not supported by BitmapFactory)");
                }
                if (failure != null) {
                    // Keep the loop alive: a later check can retry.
                    final String reason = failure;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            finishCreationAttempt();
                            if (call != null) {
                                call.reject(reason);
                            }
                        }
                    });
                    return;
                }
                final Bitmap icon = scaleBitmap(bitmap, MAX_ICON_PX);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean shown = requestPin(icon, label, logoUrl);
                        finishCreationAttempt();
                        if (call != null) {
                            if (shown) {
                                markCreated(logoUrl, label);
                                JSObject ret = new JSObject();
                                ret.put("created", true);
                                call.resolve(ret);
                            } else {
                                call.reject("Pinned shortcuts are not supported on this device/launcher");
                            }
                        } else if (shown) {
                            Log.i(TAG, "Pin dialog shown for " + label + " (" + logoUrl + ")");
                            markCreated(logoUrl, label);
                            // Never stop looking: keep watching on the slow
                            // cadence so a later company switch is offered
                            // its own shortcut.
                            attempts = 0;
                            currentIntervalMs = CHECK_INTERVAL_IDLE_MS;
                        } else {
                            // No dialog was shown (e.g. pinning unsupported):
                            // stay in the loop so a later check can retry.
                            Log.i(TAG, "Pin dialog NOT shown for " + label + "; will keep checking");
                        }
                    }
                });
            }
        }).start();
    }

    private void finishCreationAttempt() {
        creating = false;
        creatingUrl = null;
    }

    /**
     * Asks Android to pin the shortcut. Returns true when the system shows
     * the user the confirmation dialog. Uses the AndroidX compat APIs so the
     * plugin compiles without the framework android.graphics.Icon class.
     * The shortcut id is derived from the logo URL, so each company gets
     * its own shortcut and switching back to a company reuses its id.
     */
    private boolean requestPin(Bitmap bitmap, String label, String logoUrl) {
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
        String shortcutId = "company-logo-" + (logoUrl.hashCode() & 0x7fffffff);
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(context, shortcutId)
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
        if (creating) {
            call.reject("A shortcut request is already in progress");
            return;
        }
        creating = true;
        creatingUrl = logoUrl;
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
