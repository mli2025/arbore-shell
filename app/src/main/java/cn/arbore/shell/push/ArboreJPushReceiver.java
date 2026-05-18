package cn.arbore.shell.push;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import cn.arbore.shell.MainActivity;
import cn.jpush.android.api.JPushMessage;
import cn.jpush.android.api.NotificationMessage;
import cn.jpush.android.service.JPushMessageReceiver;

import org.json.JSONObject;

/**
 * 极光消息 / 通知回调入口。负责：
 *
 *   1) 用户点击通知时，从 extras.path 取出业务深链 (例如 /m/maintain/detail?id=1)
 *      并通过 Intent 启动 MainActivity，由 MainActivity 加载该路径
 *   2) 透传消息 (passthrough) 也走同样的入口
 *
 * 服务端推送 payload 示例：
 * {
 *   "platform": "android",
 *   "audience": { "alias": ["123"] },
 *   "notification": {
 *     "android": {
 *       "title": "新工单",
 *       "alert": "您有一条待处理工单",
 *       "extras": { "path": "/m/maintain/detail?id=1" }
 *     }
 *   }
 * }
 */
public class ArboreJPushReceiver extends JPushMessageReceiver {

    private static final String TAG = "ArboreJPush";

    /** Intent extra key carrying the deep-link path; consumed by MainActivity. */
    public static final String EXTRA_DEEP_LINK = "arbore.deep_link";

    /** Called when the user taps the notification in the status bar. */
    @Override
    public void onNotifyMessageOpened(Context context, NotificationMessage message) {
        super.onNotifyMessageOpened(context, message);
        String path = extractPath(message == null ? null : message.notificationExtras);
        Log.i(TAG, "onNotifyMessageOpened path=" + path);
        launchMain(context, path);
    }

    /** Custom (透传) messages — we treat them the same as a tap if extras.path exists. */
    @Override
    public void onMessage(Context context, cn.jpush.android.api.CustomMessage message) {
        super.onMessage(context, message);
        if (message == null) return;
        String path = extractPath(message.extra);
        Log.i(TAG, "onMessage path=" + path + " content=" + message.message);
        // For passthrough, only auto-open if path is present and user is interacting.
        // (Most use cases prefer building an actual notification — left as TODO.)
        if (!TextUtils.isEmpty(path)) {
            launchMain(context, path);
        }
    }

    @Override
    public void onRegister(Context context, String registrationId) {
        super.onRegister(context, registrationId);
        Log.i(TAG, "onRegister id=" + registrationId);
    }

    @Override
    public void onConnected(Context context, boolean isConnected) {
        super.onConnected(context, isConnected);
        Log.i(TAG, "onConnected " + isConnected);
    }

    @Override
    public void onAliasOperatorResult(Context context, JPushMessage jPushMessage) {
        super.onAliasOperatorResult(context, jPushMessage);
        if (jPushMessage == null) return;
        Log.i(TAG, "alias=" + jPushMessage.getAlias()
                + " seq=" + jPushMessage.getSequence()
                + " errCode=" + jPushMessage.getErrorCode());
    }

    /** Best-effort: extras is sent as a JSON string by the JPush console. */
    private static String extractPath(String extrasJson) {
        if (TextUtils.isEmpty(extrasJson)) return null;
        try {
            JSONObject json = new JSONObject(extrasJson);
            String path = json.optString("path", null);
            if (!TextUtils.isEmpty(path)) return path;
            // Sometimes the console double-wraps under extras.
            JSONObject inner = json.optJSONObject("extras");
            if (inner != null) {
                String p = inner.optString("path", null);
                if (!TextUtils.isEmpty(p)) return p;
            }
        } catch (Exception e) {
            Log.w(TAG, "extras parse failed: " + e.getMessage());
        }
        return null;
    }

    private static void launchMain(Context context, String path) {
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!TextUtils.isEmpty(path)) {
            i.putExtra(EXTRA_DEEP_LINK, path);
        }
        try {
            context.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "launchMain failed: " + e.getMessage());
        }
    }
}
