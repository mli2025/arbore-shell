package cn.arbore.shell.push;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

import cn.jiguang.api.utils.JCollectionAuth;

/**
 * Runs before JPush's {@code InitProvider} so {@link JCollectionAuth#setAuth} is
 * already true when the SDK auto-initializes. Without this, RegistrationId stays
 * empty because init happens before {@link cn.arbore.shell.ShellApp#onCreate}.
 */
public class JPushConsentProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        if (getContext() != null) {
            JCollectionAuth.setAuth(getContext().getApplicationContext(), true);
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
