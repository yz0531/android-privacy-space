package com.example.privateentry;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Deletes only the named personal-profile APK staging file after managed copy completion. */
public final class ParentApkCleanupActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
        finish();
    }

    private void handle(Intent intent) {
        ApkTempFiles.cleanupStale(this);
        if (PrivacyAdminReceiver.isProfileOwner(this)
                || intent == null
                || !AppContract.ACTION_CLEANUP_APK_IN_PARENT.equals(intent.getAction())) {
            return;
        }
        String token = intent.getStringExtra(AppContract.EXTRA_APK_TOKEN);
        if (ApkTempFiles.isValidToken(token)) {
            try {
                revokeUriPermission(
                        ApkTempFiles.uriForToken(this, token),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
                // The cross-profile activity-scoped grant may already have expired.
            }
            ApkTempFiles.delete(this, token);
        }
    }
}
