package com.example.privateentry;

import android.content.BroadcastReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.Set;

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
            return;
        }
        rehideTrackedApplications(context);
    }

    private void rehideTrackedApplications(Context context) {
        DevicePolicyManager policyManager =
                context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = PrivacyAdminReceiver.component(context);
        if (policyManager == null) {
            return;
        }

        String pendingPackage = AppSettings.getPendingRehide(context);
        Set<String> trackedPackages = AppSettings.getHiddenPackages(context);
        for (String packageName : trackedPackages) {
            try {
                boolean changed = policyManager.setApplicationHidden(admin, packageName, true);
                boolean hidden = changed || policyManager.isApplicationHidden(admin, packageName);
                if (hidden && packageName.equals(pendingPackage)) {
                    AppSettings.clearPendingRehide(context);
                    pendingPackage = null;
                }
            } catch (SecurityException | IllegalArgumentException error) {
                Log.w(TAG, "Unable to re-hide tracked package " + packageName, error);
            }
        }
    }
}
