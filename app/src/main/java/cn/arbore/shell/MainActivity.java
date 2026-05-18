package cn.arbore.shell;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.Arrays;

import cn.arbore.shell.push.ArboreJPushReceiver;
import cn.arbore.shell.push.JPushHelper;
import cn.jpush.android.api.JPushInterface;

/**
 * Shell entry. Hosts a single full-screen WebView pointing at the TPM web app.
 * Bridges the page to native via {@link AndroidBridge} and registers JPush.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_SETTINGS = 0x1001;
    private static final int REQ_CAMERA = 0x2001;
    private static final int REQ_NOTIFICATION = 0x2002;
    private static final int REQ_LOCATION = 0x2002;

    private static final String CAPABILITY_TEST_URL = "file:///android_asset/shell_test/index.html";

    /**
     * Mobile entry. Appended to the configured base URL so the shell skips the PC
     * shell (top bar, side menu, tabs) and jumps straight to the mobile portal.
     */
    private static final String MOBILE_PATH = "/m";

    private PermissionRequest pendingPermissionRequest;

    /** JS-supplied token for the in-flight native scan, used to echo the result back. */
    private String pendingScanToken;
    private String pendingLocationToken;
    private String pendingGeolocationOrigin;
    private GeolocationPermissions.Callback pendingGeolocationCallback;

    private ActivityResultLauncher<ScanOptions> scanLauncher;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private ValueCallback<Uri[]> filePathCallback;

    private static final String JS_BOOTSTRAP =
            // Auto-detect the logged-in user via /Account/Profile cookie session.
            // Calls AndroidBridge.bindUser(id, name) once when available.
            "(function(){\n"
                    + "  if (window.__arboreBootstrap) return;\n"
                    + "  window.__arboreBootstrap = true;\n"
                    + "  function tryBind(){\n"
                    + "    try {\n"
                    + "      fetch('/Account/Profile', { credentials: 'include' })\n"
                    + "        .then(function(r){ return r.ok ? r.json() : null; })\n"
                    + "        .then(function(j){\n"
                    + "          if (!j || j.code !== 200 || !j.data) return;\n"
                    + "          var id = String(j.data.id || '');\n"
                    + "          var name = String(j.data.name || j.data.account || '');\n"
                    + "          if (id && window.AndroidBridge && AndroidBridge.bindUser) {\n"
                    + "            AndroidBridge.bindUser(id, name);\n"
                    + "          }\n"
                    + "        }).catch(function(){});\n"
                    + "    } catch(e) {}\n"
                    + "  }\n"
                    + "  // Re-run a few times: page may finish loading before session cookie is applied.\n"
                    + "  tryBind();\n"
                    + "  setTimeout(tryBind, 800);\n"
                    + "  setTimeout(tryBind, 3000);\n"
                    + "})();";

    private SwipeRefreshLayout swipe;
    private WebView webView;
    private ProgressBar loading;
    private LinearLayout errorPanel;
    private TextView errorDetail;
    private boolean lastLoadFailed;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swipe = findViewById(R.id.swipe_refresh);
        webView = findViewById(R.id.web_view);
        loading = findViewById(R.id.loading);
        errorPanel = findViewById(R.id.error_panel);
        errorDetail = findViewById(R.id.error_detail);
        Button btnReload = findViewById(R.id.btn_reload);
        Button btnSettings = findViewById(R.id.btn_settings);
        ImageButton btnFloatingSettings = findViewById(R.id.btn_floating_settings);

        swipe.setColorSchemeResources(R.color.brand_600, R.color.brand_500, R.color.brand_700);
        swipe.setOnRefreshListener(() -> {
            if (lastLoadFailed) {
                reloadStartUrl();
            } else {
                webView.reload();
            }
            swipe.postDelayed(() -> swipe.setRefreshing(false), 600);
        });
        btnReload.setOnClickListener(v -> reloadStartUrl());
        btnSettings.setOnClickListener(v -> openSettings());
        btnFloatingSettings.setOnClickListener(v -> showShellMenu());

        configureWebView();
        registerScanLauncher();
        registerFileChooserLauncher();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        if (!Prefs.hasServerUrl(this)) {
            openSettings();
            return;
        }
        requestNotificationPermissionIfNeeded();
        JPushHelper.ensureInit(this);
        JPushHelper.pollRegistrationId(this, 15, null);
        loadStartUrl();
        handleDeepLinkIntent(getIntent());

        JPushInterface.onResume(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleDeepLinkIntent(intent);
    }

    /**
     * Android 13+ blocks notifications by default until POST_NOTIFICATIONS is granted
     * at runtime. JPush will happily deliver to the device — but the system will drop
     * the visual notification, so users see nothing. We ask exactly once on launch.
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
    }

    /**
     * Pulls the deep-link path from the intent (set by {@link ArboreJPushReceiver})
     * and navigates the WebView to {baseUrl}+path. Falls back silently if no path.
     */
    private void handleDeepLinkIntent(Intent intent) {
        if (intent == null) return;
        String path = intent.getStringExtra(ArboreJPushReceiver.EXTRA_DEEP_LINK);
        if (TextUtils.isEmpty(path)) return;
        intent.removeExtra(ArboreJPushReceiver.EXTRA_DEEP_LINK);

        String base = Prefs.getServerUrl(this);
        if (TextUtils.isEmpty(base)) return;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String url = path.startsWith("http://") || path.startsWith("https://")
                ? path
                : base + (path.startsWith("/") ? path : "/" + path);
        webView.loadUrl(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        JPushInterface.onResume(this);
    }

    @Override
    protected void onPause() {
        JPushInterface.onPause(this);
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS && resultCode == Activity.RESULT_OK) {
            loadStartUrl();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " ArboreShell/"
                + BuildConfig.VERSION_NAME);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        }

        webView.addJavascriptInterface(new AndroidBridge(this), AndroidBridge.NAME);
        webView.setWebChromeClient(new ChromeClient());
        webView.setWebViewClient(new Client());
    }

    private void loadStartUrl() {
        String url = Prefs.getServerUrl(this);
        if (TextUtils.isEmpty(url)) {
            openSettings();
            return;
        }
        lastLoadFailed = false;
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(buildMobileEntryUrl(url));
    }

    /**
     * Append {@link #MOBILE_PATH} to the base server URL so the shell always
     * lands on the mobile portal instead of the PC layout. Keeps any existing
     * {@code /m...} path the user might have typed (so a deep-link to
     * {@code /m/maintain} remains usable).
     */
    private static String buildMobileEntryUrl(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (TextUtils.isEmpty(url)) {
            return url;
        }
        int qIdx = url.indexOf('?');
        String base = qIdx < 0 ? url : url.substring(0, qIdx);
        String query = qIdx < 0 ? "" : url.substring(qIdx);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        Uri parsed = Uri.parse(base);
        String path = parsed.getPath();
        if (path == null) path = "";
        if (path.equals(MOBILE_PATH) || path.startsWith(MOBILE_PATH + "/")) {
            return base + query;
        }
        return base + MOBILE_PATH + query;
    }

    private void reloadStartUrl() {
        loadStartUrl();
    }

    /** Loads the offline capability test page bundled in assets. */
    void loadCapabilityTestPage() {
        lastLoadFailed = false;
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(CAPABILITY_TEST_URL);
    }

    void reloadBusinessPage() {
        loadStartUrl();
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    /** Quick action sheet for the user to switch server URL / reload / clear cache. */
    private void showShellMenu() {
        CharSequence[] items = new CharSequence[] {
                "重新加载",
                getString(R.string.menu_capability_test),
                "服务器设置",
                "清除缓存（含登录状态）",
                "退出"
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Arbore Shell " + BuildConfig.VERSION_NAME)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            reloadStartUrl();
                            break;
                        case 1:
                            loadCapabilityTestPage();
                            break;
                        case 2:
                            openSettings();
                            break;
                        case 3:
                            SettingsActivity.clearWebData(this);
                            android.widget.Toast.makeText(this,
                                    R.string.msg_cache_cleared,
                                    android.widget.Toast.LENGTH_SHORT).show();
                            reloadStartUrl();
                            break;
                        case 4:
                            finishAndRemoveTask();
                            break;
                    }
                })
                .show();
    }

    private void showError(String detail) {
        lastLoadFailed = true;
        errorDetail.setText(detail == null ? "" : detail);
        errorPanel.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
    }

    private final class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            loading.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            loading.setVisibility(View.GONE);
            view.evaluateJavascript(JS_BOOTSTRAP, null);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if (scheme == null) {
                return false;
            }
            switch (scheme) {
                case "http":
                case "https":
                    return false;
                case "tel":
                case "mailto":
                case "sms":
                case "geo":
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) {
                return;
            }
            showError(request.getUrl() + " (" + error.getErrorCode() + " "
                    + error.getDescription() + ")");
        }
    }

    private final class ChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (newProgress >= 100) {
                loading.setVisibility(View.GONE);
            } else {
                loading.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            if (request == null) return;
            String[] requested = request.getResources();
            boolean wantsCamera = false;
            for (String r : requested) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                    wantsCamera = true;
                    break;
                }
            }
            if (!wantsCamera) {
                request.deny();
                return;
            }
            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                grantCamera(request);
            } else {
                pendingPermissionRequest = request;
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingPermissionRequest == request) {
                pendingPermissionRequest = null;
            }
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = callback;
            Intent intent = params.createIntent();
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                fileChooserLauncher.launch(Intent.createChooser(intent, "选择文件"));
            } catch (Exception e) {
                filePathCallback = null;
                return false;
            }
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       GeolocationPermissions.Callback callback) {
            if (ContextCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                callback.invoke(origin, true, false);
                return;
            }
            pendingGeolocationOrigin = origin;
            pendingGeolocationCallback = callback;
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQ_LOCATION);
        }
    }

    private void grantCamera(PermissionRequest request) {
        runOnUiThread(() -> request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}));
    }

    /**
     * Register the ZXing launcher once. The web app invokes
     * {@link AndroidBridge#scan(String)} which calls {@link #startNativeScan(String)};
     * on completion we evaluate {@code window.__arboreScanResult(token, code, err)}.
     */
    private void registerFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Uri[] uris = null;
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        if (data.getClipData() != null) {
                            int n = data.getClipData().getItemCount();
                            uris = new Uri[n];
                            for (int i = 0; i < n; i++) {
                                uris[i] = data.getClipData().getItemAt(i).getUri();
                            }
                        } else if (data.getData() != null) {
                            uris = new Uri[]{data.getData()};
                        }
                    }
                    if (filePathCallback != null) {
                        filePathCallback.onReceiveValue(uris);
                        filePathCallback = null;
                    }
                });
    }

    private void registerScanLauncher() {
        scanLauncher = registerForActivityResult(new ScanContract(), result -> {
            String token = pendingScanToken == null ? "" : pendingScanToken;
            pendingScanToken = null;
            String contents = result == null ? null : result.getContents();
            if (contents == null) {
                dispatchScanResult(token, null, "CANCELLED");
            } else {
                dispatchScanResult(token, contents, null);
            }
        });
    }

    /** Called from the JS bridge thread (already marshalled to UI). */
    void startNativeScan(String token) {
        if (scanLauncher == null) {
            dispatchScanResult(token, null, "NOT_READY");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            // No way to "queue" the scan against runtime permission with the launcher API;
            // ask the user to tap again once permission is granted.
            dispatchScanResult(token, null, "PERMISSION_REQUESTED");
            return;
        }
        pendingScanToken = token;
        ScanOptions opts = new ScanOptions();
        opts.setDesiredBarcodeFormats(Arrays.asList(
                ScanOptions.QR_CODE,
                ScanOptions.CODE_128,
                ScanOptions.CODE_39,
                ScanOptions.EAN_13,
                ScanOptions.DATA_MATRIX,
                ScanOptions.PDF_417));
        opts.setPrompt("\u5C06\u4E8C\u7EF4\u7801 / \u6761\u7801\u5BF9\u51C6\u626B\u63CF\u6846");
        opts.setBeepEnabled(true);
        opts.setOrientationLocked(false);
        opts.setBarcodeImageEnabled(false);
        opts.setCaptureActivity(CaptureActivity.class);
        opts.addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN);
        scanLauncher.launch(opts);
    }

    /**
     * Echo the scan outcome back to the web app via the well-known callback
     * {@code window.__arboreScanResult(token, code, err)}.
     */
    private void dispatchScanResult(String token, String code, String err) {
        String t = jsString(token);
        String c = code == null ? "null" : jsString(code);
        String e = err == null ? "null" : jsString(err);
        final String js = "if(window.__arboreScanResult){try{window.__arboreScanResult("
                + t + "," + c + "," + e + ");}catch(_e){}}";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private static String jsString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\u2028': sb.append("\\u2028"); break;
                case '\u2029': sb.append("\\u2029"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    void startNativeLocation(String token) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            pendingLocationToken = token;
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQ_LOCATION);
            return;
        }
        deliverNativeLocation(token);
    }

    private void deliverNativeLocation(String token) {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) {
                dispatchLocationResult(token, null, "NO_LOCATION_SERVICE");
                return;
            }
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    && !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                dispatchLocationResult(token, null, "LOCATION_DISABLED");
                return;
            }
            Location best = null;
            for (String provider : new String[]{
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER}) {
                if (!lm.isProviderEnabled(provider)) {
                    continue;
                }
                @SuppressLint("MissingPermission")
                Location loc = lm.getLastKnownLocation(provider);
                if (loc != null && (best == null || loc.getTime() > best.getTime())) {
                    best = loc;
                }
            }
            if (best == null) {
                dispatchLocationResult(token, null, "NO_FIX");
                return;
            }
            String json = "{\"lat\":" + best.getLatitude()
                    + ",\"lng\":" + best.getLongitude()
                    + ",\"accuracy\":" + best.getAccuracy() + "}";
            dispatchLocationResult(token, json, null);
        } catch (SecurityException se) {
            dispatchLocationResult(token, null, "PERMISSION_DENIED");
        }
    }

    private void dispatchLocationResult(String token, String json, String err) {
        String t = jsString(token);
        String j = json == null ? "null" : jsString(json);
        String e = err == null ? "null" : jsString(err);
        final String js = "if(window.__arboreLocationResult){try{window.__arboreLocationResult("
                + t + "," + j + "," + e + ");}catch(_e){}}";
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            PermissionRequest req = pendingPermissionRequest;
            pendingPermissionRequest = null;
            if (req != null) {
                if (granted) {
                    grantCamera(req);
                } else {
                    req.deny();
                }
            }
            if (!granted) {
                Toast.makeText(this, R.string.toast_camera_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_NOTIFICATION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, R.string.toast_notification_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_LOCATION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            String token = pendingLocationToken;
            pendingLocationToken = null;
            if (granted && token != null) {
                deliverNativeLocation(token);
            } else if (token != null) {
                dispatchLocationResult(token, null, "PERMISSION_DENIED");
            }
            GeolocationPermissions.Callback geoCb = pendingGeolocationCallback;
            String geoOrigin = pendingGeolocationOrigin;
            pendingGeolocationCallback = null;
            pendingGeolocationOrigin = null;
            if (geoCb != null) {
                geoCb.invoke(geoOrigin, granted, false);
            }
            if (!granted && (token != null || geoOrigin != null)) {
                Toast.makeText(this, R.string.toast_location_denied, Toast.LENGTH_LONG).show();
            }
        }
    }
}
