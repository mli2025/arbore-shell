package cn.arbore.shell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputEditText;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.Theme_ArboreShell_Settings);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }

        TextInputEditText inputUrl = findViewById(R.id.input_url);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnCancel = findViewById(R.id.btn_cancel);
        Button btnClearCache = findViewById(R.id.btn_clear_cache);
        TextView info = findViewById(R.id.info);

        inputUrl.setText(Prefs.getServerUrl(this));
        info.setText("Build " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");

        btnCancel.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(saveClickListener(inputUrl));
        btnClearCache.setOnClickListener(v -> {
            clearWebData(this);
            Toast.makeText(this, R.string.msg_cache_cleared, Toast.LENGTH_LONG).show();
        });
    }

    /** Wipes WebView cookies, storage, cache and the saved server URL stays untouched. */
    static void clearWebData(Context ctx) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Throwable ignored) {
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {
        }
        try {
            WebView w = new WebView(ctx.getApplicationContext());
            w.clearCache(true);
            w.clearHistory();
            w.clearFormData();
            w.destroy();
        } catch (Throwable ignored) {
        }
        try {
            deleteRecursive(new File(ctx.getApplicationContext().getCacheDir(), "WebView"));
            deleteRecursive(new File(ctx.getApplicationContext().getCacheDir(), "org.chromium.android_webview"));
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteRecursive(kid);
                }
            }
        }
        f.delete();
    }

    private View.OnClickListener saveClickListener(TextInputEditText inputUrl) {
        return v -> {
            String url = inputUrl.getText() == null ? "" : inputUrl.getText().toString().trim();
            if (TextUtils.isEmpty(url) || !(url.startsWith("http://") || url.startsWith("https://"))) {
                Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.setServerUrl(this, url);
            setResult(Activity.RESULT_OK, new Intent().putExtra("url", url));
            finish();
        };
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
