package cn.arbore.shell;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import cn.jpush.android.api.JPushInterface;

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

        createDefaultNotificationChannel();
        JPushInterface.setDebugMode(BuildConfig.DEBUG);
        JPushInterface.init(this);
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
