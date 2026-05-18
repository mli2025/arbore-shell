package cn.arbore.shell.push;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import cn.arbore.shell.BuildConfig;
import cn.arbore.shell.Prefs;
import cn.jiguang.api.utils.JCollectionAuth;
import cn.jpush.android.api.JPushInterface;

/**
 * Centralizes JPush privacy consent + init + RegistrationId polling.
 */
public final class JPushHelper {

    private static final String TAG = "JPushHelper";

    private JPushHelper() {
    }

    /** Idempotent: always grant consent, then init if RegistrationId is still empty. */
    public static void ensureInit(Context context) {
        Context app = context.getApplicationContext();
        long t0 = System.currentTimeMillis();
        try {
            JCollectionAuth.setAuth(app, true);
        } catch (Throwable t) {
            saveError(app, "setAuth", t);
        }
        if (BuildConfig.DEBUG) {
            try { JPushInterface.setDebugMode(true); } catch (Throwable ignored) {}
        }
        String reg = readRegistrationId(app);
        if (!TextUtils.isEmpty(reg)) {
            Log.i(TAG, "already registered id=" + reg);
            return;
        }
        Log.i(TAG, "JPushInterface.init() package=" + app.getPackageName()
                + " appKey=" + BuildConfig.JPUSH_APPKEY);
        try {
            JPushInterface.init(app);
        } catch (Throwable t) {
            saveError(app, "init", t);
        }
        // 兜底：直接显式启动 :pushcore 子进程，避免某些 ROM 上自动启动失败
        try {
            Intent svc = new Intent();
            svc.setComponent(new ComponentName(app.getPackageName(),
                    "cn.jpush.android.service.PushService"));
            svc.setAction("cn.jpush.android.intent.PushService");
            app.startService(svc);
        } catch (Throwable t) {
            saveError(app, "startService", t);
        }
        int n = Prefs.get(app).getInt(Prefs.KEY_JPUSH_INIT_COUNT, 0) + 1;
        Prefs.get(app).edit()
                .putInt(Prefs.KEY_JPUSH_INIT_COUNT, n)
                .putLong(Prefs.KEY_JPUSH_LAST_INIT_AT, t0)
                .apply();
    }

    private static void saveError(Context app, String stage, Throwable t) {
        String msg = stage + ": " + t.getClass().getSimpleName() + " " + t.getMessage();
        Log.e(TAG, msg, t);
        Prefs.get(app).edit().putString(Prefs.KEY_JPUSH_LAST_ERROR, msg).apply();
    }

    public static String readRegistrationId(Context context) {
        Context app = context.getApplicationContext();
        String cached = Prefs.get(app).getString(Prefs.KEY_JPUSH_REGISTRATION_ID, "");
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }
        String id = JPushInterface.getRegistrationID(app);
        if (id == null || id.isEmpty() || "0".equals(id)) {
            return "";
        }
        Prefs.get(app).edit().putString(Prefs.KEY_JPUSH_REGISTRATION_ID, id).apply();
        return id;
    }

    /**
     * Poll RegistrationId for up to {@code maxAttempts} times (every 2s).
     * Invokes {@code onDone} on the main thread with the final id (may still be empty).
     */
    public static void pollRegistrationId(Context context, int maxAttempts, Runnable onDone) {
        Handler h = new Handler(Looper.getMainLooper());
        final int[] attempt = {0};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                attempt[0]++;
                ensureInit(context);
                String reg = readRegistrationId(context);
                if (!TextUtils.isEmpty(reg)) {
                    Log.i(TAG, "poll success at attempt " + attempt[0] + " id=" + reg);
                    if (onDone != null) onDone.run();
                    return;
                }
                if (attempt[0] >= maxAttempts) {
                    Log.w(TAG, "poll gave up after " + attempt[0] + " attempts");
                    if (onDone != null) onDone.run();
                    return;
                }
                h.postDelayed(this, 2000);
            }
        };
        h.post(tick);
    }
}
