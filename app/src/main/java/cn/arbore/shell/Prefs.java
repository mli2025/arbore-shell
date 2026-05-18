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
