package com.example.privateentry;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserHandle;

/** Persists Android's authoritative managed-profile provisioning success callback. */
public final class ProvisioningStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !DevicePolicyManager.ACTION_MANAGED_PROFILE_PROVISIONED.equals(
                        intent.getAction())
                || PrivacyAdminReceiver.isProfileOwner(context)) {
            return;
        }

        int targetUserId = getTargetUserId(intent);
        if (targetUserId == AppSettings.USER_ID_UNKNOWN) {
            return;
        }
        AppSettings.markPlatformProvisioningSucceeded(context, targetUserId);
    }

    @SuppressWarnings("deprecation")
    private static int getTargetUserId(Intent intent) {
        UserHandle user;
        if (Build.VERSION.SDK_INT >= 33) {
            user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle.class);
        } else {
            user = intent.getParcelableExtra(Intent.EXTRA_USER);
        }
        if (user != null) {
            return user.hashCode();
        }
        return intent.getIntExtra(
                "android.intent.extra.user_handle",
                AppSettings.USER_ID_UNKNOWN);
    }
}
