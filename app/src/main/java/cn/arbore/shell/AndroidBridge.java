package cn.arbore.shell;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import cn.jpush.android.api.JPushInterface;

/**
 * Bridge exposed to the loaded web page as {@code window.AndroidBridge}.
 * <p>
 * Methods are invoked from the WebView thread; keep them small and side-effect-only.
 */
public class AndroidBridge {

    public static final String NAME = "AndroidBridge";

    private final Context appContext;
    private final MainActivity activity;

    public AndroidBridge(MainActivity activity) {
        this.activity = activity;
        this.appContext = activity.getApplicationContext();
    }

    /**
     * Called by the web app after a successful login.
     * Registers the user id as the JPush alias so server-side push can target them.
     */
    @JavascriptInterface
    public void bindUser(String userId, String userName) {
        if (TextUtils.isEmpty(userId)) {
            return;
        }
        String safeAlias = sanitizeAlias(userId);
        JPushInterface.setAlias(appContext, (int) (System.currentTimeMillis() % Integer.MAX_VALUE),
                safeAlias);
        Prefs.get(appContext).edit()
                .putString(Prefs.KEY_LAST_USER_ID, userId)
                .putString(Prefs.KEY_LAST_USER_NAME, userName == null ? "" : userName)
                .apply();
    }

    /** Called by the web app on logout. */
    @JavascriptInterface
    public void unbindUser() {
        JPushInterface.deleteAlias(appContext, (int) (System.currentTimeMillis() % Integer.MAX_VALUE));
        Prefs.get(appContext).edit()
                .remove(Prefs.KEY_LAST_USER_ID)
                .remove(Prefs.KEY_LAST_USER_NAME)
                .apply();
    }

    /** Returns the JPush registration id so the web backend can persist a device id per user. */
    @JavascriptInterface
    public String getRegistrationId() {
        String id = JPushInterface.getRegistrationID(appContext);
        return id == null ? "" : id;
    }

    /** Build identifier exposed to the page (e.g. for support overlays). */
    @JavascriptInterface
    public String getBuildLabel() {
        return BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    }

    /** Open the native settings screen so the user can change the server URL. */
    @JavascriptInterface
    public void openSettings() {
        Intent i = new Intent(activity, SettingsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(i);
    }

    /** Convenience toast for the web app (debug aid). */
    @JavascriptInterface
    public void toast(String msg) {
        activity.runOnUiThread(() -> Toast.makeText(appContext,
                msg == null ? "" : msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * Launch the native ZXing scanner. WebView cannot use navigator.mediaDevices
     * on http origins (Chromium hides it on insecure contexts), so the mobile web
     * portal calls this bridge instead. When the scan finishes (or the user
     * cancels), the activity calls {@code window.__arboreScanResult(token, code, err)}
     * back in JS.
     *
     * @param token opaque correlation id chosen by JS; echoed back on completion
     */
    @JavascriptInterface
    public void scan(String token) {
        activity.runOnUiThread(() -> activity.startNativeScan(token == null ? "" : token));
    }

    /** Configured TPM server base URL (no trailing path forced). */
    @JavascriptInterface
    public String getServerUrl() {
        return Prefs.getServerUrl(appContext);
    }

    @JavascriptInterface
    public String getBoundUserId() {
        return Prefs.get(appContext).getString(Prefs.KEY_LAST_USER_ID, "");
    }

    @JavascriptInterface
    public String getBoundUserName() {
        return Prefs.get(appContext).getString(Prefs.KEY_LAST_USER_NAME, "");
    }

    /**
     * Native GPS/network location. Result is delivered to
     * {@code window.__arboreLocationResult(token, json, err)}.
     */
    @JavascriptInterface
    public void requestLocation(String token) {
        activity.runOnUiThread(() -> activity.startNativeLocation(token == null ? "" : token));
    }

    /** Opens the built-in capability test page (file:///android_asset/shell_test/index.html). */
    @JavascriptInterface
    public void openCapabilityTest() {
        activity.runOnUiThread(() -> activity.loadCapabilityTestPage());
    }

    /** Reload the configured TPM server (mobile entry /m). */
    @JavascriptInterface
    public void reloadBusiness() {
        activity.runOnUiThread(() -> activity.reloadBusinessPage());
    }

    /** JPush rejects empty or specially-shaped aliases; coerce to a safe form. */
    private static String sanitizeAlias(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
