package com.example.privateentry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Repairs a profile whose system provisioning finished before DPC setup ran. */
public final class ProfileRepairReceiver extends BroadcastReceiver {
    private static final String TAG = "ProfileRepairReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
                        && !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                        && !Intent.ACTION_USER_UNLOCKED.equals(intent.getAction()))) {
            return;
        }
        ApkTempFiles.cleanupStale(context);
        if (!PrivacyAdminReceiver.isProfileOwner(context)) {
            return;
        }

        if (!PrivacyAdminReceiver.configureManagedProfile(context)) {
            Log.e(TAG, "Unable to finish managed-profile initialization");
        }
    }
}
