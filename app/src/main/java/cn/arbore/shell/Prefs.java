package cn.arbore.shell;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/** Thin wrapper around the single SharedPreferences file the shell uses. */
public final class Prefs {

    public static final String FILE = "shell_prefs";
    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_LAST_USER_ID = "last_user_id";
    public static final String KEY_LAST_USER_NAME = "last_user_name";
    /** Cached from JPush onRegister; getRegistrationID() may stay empty until then. */
    public static final String KEY_JPUSH_REGISTRATION_ID = "jpush_registration_id";
    public static final String KEY_JPUSH_CONNECTED = "jpush_connected";
    public static final String KEY_JPUSH_INIT_COUNT = "jpush_init_count";
    public static final String KEY_JPUSH_LAST_INIT_AT = "jpush_last_init_at";
    public static final String KEY_JPUSH_LAST_ERROR = "jpush_last_error";
    public static final String KEY_APP_ONCREATE_AT = "app_oncreate_at";
    /** Persisted X/Y (in pixels, relative to parent) of the draggable wrench button. */
    public static final String KEY_FLOATING_X = "floating_x";
    public static final String KEY_FLOATING_Y = "floating_y";

    private Prefs() {
    }

    public static SharedPreferences get(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getServerUrl(Context ctx) {
        return get(ctx).getString(KEY_SERVER_URL, "");
    }

    public static void setServerUrl(Context ctx, String url) {
        get(ctx).edit().putString(KEY_SERVER_URL, url == null ? "" : url.trim()).apply();
    }

    public static boolean hasServerUrl(Context ctx) {
        return !TextUtils.isEmpty(getServerUrl(ctx));
    }
}
