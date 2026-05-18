package cn.arbore.shell;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import cn.arbore.shell.push.JPushHelper;
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
        return JPushHelper.readRegistrationId(appContext);
    }

    /** Force JPush init again (capability test page). */
    @JavascriptInterface
    public void retryJPushInit() {
        activity.runOnUiThread(() -> JPushHelper.ensureInit(appContext));
    }

    /** Installed package name — must match the Android package registered in Jiguang console. */
    @JavascriptInterface
    public String getPackageName() {
        return appContext.getPackageName();
    }

    /**
     * One-line diagnostic for push setup (package name + registration id + bound user).
     * Shown on the capability test page when RegistrationId is empty.
     */
    @JavascriptInterface
    public String getJPushDiagnostics() {
        String pkg = appContext.getPackageName();
        String reg = getRegistrationId();
        String uid = Prefs.get(appContext).getString(Prefs.KEY_LAST_USER_ID, "");
        boolean connected = Prefs.get(appContext).getBoolean(Prefs.KEY_JPUSH_CONNECTED, false);
        int initCount = Prefs.get(appContext).getInt(Prefs.KEY_JPUSH_INIT_COUNT, 0);
        String supported = android.text.TextUtils.join(",", android.os.Build.SUPPORTED_ABIS);
        boolean has64 = false;
        for (String a : android.os.Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(a)) { has64 = true; break; }
        }
        String abiWarn = has64 ? "OK" : "WARN: JPush 5.5 only has arm64-v8a; this device is 32-bit, SDK cannot run";
        long appOnCreate = Prefs.get(appContext).getLong(Prefs.KEY_APP_ONCREATE_AT, 0);
        long lastInit = Prefs.get(appContext).getLong(Prefs.KEY_JPUSH_LAST_INIT_AT, 0);
        String lastErr = Prefs.get(appContext).getString(Prefs.KEY_JPUSH_LAST_ERROR, "");
        return "build=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                + "\npackage=" + pkg
                + "\nappKey=" + BuildConfig.JPUSH_APPKEY
                + "\ndevice=" + android.os.Build.BRAND + " " + android.os.Build.MODEL
                + " (Android " + android.os.Build.VERSION.RELEASE + ")"
                + "\nsupportedAbis=" + supported
                + "\nabiCheck=" + abiWarn
                + "\nappOnCreate=" + (appOnCreate == 0 ? "(never)" : Long.toString(appOnCreate))
                + "\nlastInit=" + (lastInit == 0 ? "(never)" : Long.toString(lastInit))
                + "\nlastError=" + (TextUtils.isEmpty(lastErr) ? "(none)" : lastErr)
                + "\nregistrationId=" + (TextUtils.isEmpty(reg) ? "(empty)" : reg)
                + "\njpushConnected=" + connected
                + "\ninitAttempts=" + initCount
                + "\nboundUserId=" + (TextUtils.isEmpty(uid) ? "(none)" : uid);
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
