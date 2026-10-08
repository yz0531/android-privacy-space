package com.example.privateentry;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;

/** Profile Owner entry point for the managed profile named "隐私空间". */
public final class PrivacyAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "PrivacyAdminReceiver";
    private static final int POLICY_SCHEMA_VERSION = 5;
    private static final int BACKGROUND_RETRY_COUNT = 6;
    private static final long BACKGROUND_RETRY_DELAY_MILLIS = 1_000L;

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        rememberProvisioningAttempt(context, intent);
        if (configureManagedProfile(context)) {
            return;
        }

        Log.e(TAG, "Initial profile configuration failed; starting bounded retry");
        android.content.BroadcastReceiver.PendingResult pendingResult = goAsync();
        Context applicationContext = context.getApplicationContext();
        Thread retryThread = new Thread(() -> {
            try {
                for (int attempt = 0; attempt < BACKGROUND_RETRY_COUNT; attempt++) {
                    SystemClock.sleep(BACKGROUND_RETRY_DELAY_MILLIS);
                    if (configureManagedProfile(applicationContext)) {
                        return;
                    }
                }
                rollbackIncompleteProfile(applicationContext);
            } finally {
                pendingResult.finish();
            }
        }, "PrivateEntry-profile-init");
        retryThread.start();
    }

    static ComponentName component(Context context) {
        return new ComponentName(context, PrivacyAdminReceiver.class);
    }

    static void rememberProvisioningAttempt(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        PersistableBundle extras;
        if (Build.VERSION.SDK_INT >= 33) {
            extras = intent.getParcelableExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                    PersistableBundle.class);
        } else {
            extras = intent.getParcelableExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
        }
        if (extras != null) {
            AppSettings.setManagedProvisioningAttemptId(
                    context,
                    extras.getLong(AppContract.EXTRA_PROVISIONING_ATTEMPT_ID, 0L));
        }
    }

    static boolean isProfileOwner(Context context) {
        DevicePolicyManager policyManager = context.getSystemService(DevicePolicyManager.class);
        return policyManager != null && policyManager.isProfileOwnerApp(context.getPackageName());
    }

    static synchronized boolean configureManagedProfile(Context context) {
        DevicePolicyManager policyManager = context.getSystemService(DevicePolicyManager.class);
        if (policyManager == null || !policyManager.isProfileOwnerApp(context.getPackageName())) {
            return false;
        }

        try {
            ApkTempFiles.cleanupStale(context);
            ComponentName admin = component(context);
            policyManager.setProfileName(admin, AppContract.PROFILE_NAME);

            if (AppSettings.getManagedProfilePolicyVersion(context)
                    < POLICY_SCHEMA_VERSION) {
                policyManager.clearCrossProfileIntentFilters(admin);
                IntentFilter parentToManaged = new IntentFilter();
                parentToManaged.addAction(AppContract.ACTION_OPEN_MANAGER);
                parentToManaged.addAction(AppContract.ACTION_INSTALL_IN_MANAGED);
                parentToManaged.addAction(AppContract.ACTION_INSTALL_APK_IN_MANAGED);
                parentToManaged.addAction(AppContract.ACTION_DELETE_IN_MANAGED);
                parentToManaged.addCategory(Intent.CATEGORY_DEFAULT);
                policyManager.addCrossProfileIntentFilter(
                        admin,
                        parentToManaged,
                        DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT);

                IntentFilter managedToParent = new IntentFilter();
                managedToParent.addAction(AppContract.ACTION_INSTALL_IN_PARENT);
                managedToParent.addAction(AppContract.ACTION_DELETE_IN_PARENT);
                managedToParent.addAction(AppContract.ACTION_CLEANUP_APK_IN_PARENT);
                managedToParent.addAction(AppContract.ACTION_PROFILE_READY);
                managedToParent.addCategory(Intent.CATEGORY_DEFAULT);
                policyManager.addCrossProfileIntentFilter(
                        admin,
                        managedToParent,
                        DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED);

                // Managed profiles start with this restriction attributed to their profile
                // owner. Clear it so Android can expose the per-source confirmation screen for
                // APKs that the user explicitly chooses in this app.
                policyManager.clearUserRestriction(
                        admin,
                        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            }

            setComponentState(context, ManagerActivity.class, true);
            setComponentState(context, LauncherActivity.class, false);
            setComponentState(context, SecretCodeReceiver.class, false);
            setComponentState(context, ProvisioningStatusReceiver.class, false);
            setComponentState(context, ProvisioningReadyActivity.class, false);
            setComponentState(context, ManagedInstallActivity.class, true);
            setComponentState(context, ManagedApkInstallActivity.class, true);
            setComponentState(context, ManagedRemoveActivity.class, true);
            setComponentState(context, ParentInstallActivity.class, false);
            setComponentState(context, ParentRemoveActivity.class, false);
            setComponentState(context, PersonalApkImportActivity.class, false);
            setComponentState(context, ParentApkCleanupActivity.class, false);

            policyManager.setProfileEnabled(admin);
            AppSettings.markManagedProfileInitialized(context, POLICY_SCHEMA_VERSION);
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to configure managed profile", error);
            return false;
        }
    }

    static void rollbackIncompleteProfile(Context context) {
        if (AppSettings.isManagedProfileInitialized(context)) {
            return;
        }
        DevicePolicyManager policyManager = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = component(context);
        if (policyManager == null || !policyManager.isProfileOwnerApp(context.getPackageName())) {
            return;
        }
        try {
            if (policyManager.isManagedProfile(admin)) {
                policyManager.wipeData(0, "隐私空间初始化失败，自动回滚");
            }
        } catch (RuntimeException ignored) {
            Log.e(TAG, "Unable to roll back incomplete managed profile", ignored);
            // Android's provisioning service also removes a failed profile as part of rollback.
        }
    }

    private static void setComponentState(Context context, Class<?> componentClass, boolean enabled) {
        context.getPackageManager().setComponentEnabledSetting(
                new ComponentName(context, componentClass),
                enabled
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
