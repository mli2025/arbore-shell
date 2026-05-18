package cn.arbore.shell;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import cn.arbore.shell.push.JPushHelper;

public class ShellApp extends Application {

    public static final String NOTIFICATION_CHANNEL_ID = "arbore.default";

    private static ShellApp instance;

    public static ShellApp get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 记录 Application.onCreate 是否被真正执行（诊断 ShellApp 未生效场景）
        Prefs.get(this).edit()
                .putLong(Prefs.KEY_APP_ONCREATE_AT, System.currentTimeMillis())
                .apply();

        createDefaultNotificationChannel();
        JPushHelper.ensureInit(this);
    }

    private void createDefaultNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_default),
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription(getString(R.string.notification_channel_default_desc));
        ch.enableLights(true);
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }
}
