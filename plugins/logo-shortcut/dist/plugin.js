/**
 * logo-shortcut — JavaScript bridge (hand-written, no TypeScript build step).
 *
 * The native side (LogoShortcutPlugin.java) runs on its own: it is loaded
 * eagerly by Capacitor at app start and detects the signed-in company's logo
 * by evaluating JavaScript in the WebView, so the web app does NOT need to
 * call this file. This bridge only exists so the plugin is a complete
 * Capacitor plugin and so the web app (or a test page) can also trigger the
 * same flow explicitly with LogoShortcut.createShortcut({ logoUrl, label }).
 */
(function (global, factory) {
  if (typeof exports === 'object' && typeof module !== 'undefined') {
    module.exports = factory();
  } else {
    global.LogoShortcut = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  var cap = (typeof window !== 'undefined' && window.Capacitor) || null;
  var plugin = cap ? cap.registerPlugin('LogoShortcut', {}) : null;

  function createShortcut(logoUrl, label) {
    if (!plugin) {
      return Promise.reject(new Error('LogoShortcut plugin is not available'));
    }
    return plugin.createShortcut({ logoUrl: logoUrl, label: label });
  }

  function isRequestPinShortcutSupported() {
    if (!plugin) {
      return Promise.resolve(false);
    }
    return plugin.isRequestPinShortcutSupported().then(function (r) {
      return !!r.supported;
    });
  }

  return {
    createShortcut: createShortcut,
    isRequestPinShortcutSupported: isRequestPinShortcutSupported,
  };
});
