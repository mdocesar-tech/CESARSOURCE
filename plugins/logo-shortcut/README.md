# logo-shortcut (TEST — Nov 2026)

Local Capacitor plugin that creates an Android home-screen **pinned shortcut**
carrying the signed-in company's logo. Tapping the shortcut opens the mobile
app directly (its intent is the app's own launch intent), not a browser tab.

## How it is triggered — no web-app changes

The web app's source is not in this repo, so the plugin triggers itself from
the native side:

1. Capacitor lists the plugin in `capacitor.plugins.json` (`npx cap sync
   android`) and the Bridge instantiates it eagerly at app start, so
   `LogoShortcutPlugin.load()` runs with no JavaScript call needed.
2. `load()` starts a periodic check (every 15 s, up to ~10 min, restarted on
   every foreground return): native code evaluates a defensive script in the
   WebView that looks for the company logo image on the signed-in page and
   returns its URL plus the company name. No sidebar/nav element is required
   — phones show no sidebar, so any logo-like image (or a localStorage logo
   value) counts. It does not fire on the login / signup screens or on the
   company picker ("Select a company" — no active company yet).
3. When a logo URL is found, the logo is downloaded and
   `ShortcutManager.requestPinShortcut()` asks the user in a system dialog to
   add the shortcut. The shortcut follows the **active company**: every
   company that gets a shortcut is recorded (label + logo URL), and a
   different company gets its own pin offer under its own shortcut id. A
   company that already has a shortcut is never asked twice, including when
   the user switches back to it. A failed download or an undecodable logo
   (e.g. SVG) is logged with the URL and the check loop keeps running — the
   loop only stops once a pin dialog has actually been shown (or the current
   company already has a shortcut).
4. The same flow is also available from JavaScript as
   `LogoShortcut.createShortcut({ logoUrl, label })` (see `dist/plugin.js`),
   plus `isRequestPinShortcutSupported()`.

## Limits of this test

- Only raster logos (PNG/JPG/WebP) can be used as shortcut icons;
  `BitmapFactory` cannot decode SVG.
- The detection script guesses the logo from the live page's DOM. If the web
  app's markup changes, the guess can miss and no shortcut is offered.
  Check `adb logcat -s LogoShortcut` to see what it found.
- A pinned shortcut is not the app's launcher icon. The launcher icon still
  comes from the APK build (`assets/icon.png`); changing it per customer
  still needs one build per customer.
- Pinned shortcuts need Android 8+ and a launcher that supports them
  (`ShortcutManager.isRequestPinShortcutSupported()` is checked first).
