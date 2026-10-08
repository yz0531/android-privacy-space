package com.example.privateentry;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class AppSettings {
    private static final SecureRandom CONNECTION_NONCE_RANDOM = new SecureRandom();

    static final String DEFAULT_SECRET_CODE = "83746291";
    static final int PROVISIONING_NONE = 0;
    static final int PROVISIONING_STARTED = 1;
    static final int PROVISIONING_ACCEPTED = 2;
    static final int PROVISIONING_SUCCEEDED = 3;
    static final int PROVISIONING_CANCEL_PENDING = 4;
    static final int PROVISIONING_PROFILE_CREATED = 5;
    static final int PROVISIONING_ROLLED_BACK = 6;
    static final int PROVISIONING_PLATFORM_SUCCEEDED = 7;
    static final int PROVISIONING_CONNECTING = 8;
    static final int PROVISIONING_TIMEOUT_UNKNOWN = 9;
    static final int PROVISIONING_CANCELED_CLEAN = 10;
    static final int PROVISIONING_PROFILE_ABSENT = 11;
    static final int USER_ID_UNKNOWN = -10000;
    static final long PROVISIONING_STALE_WARNING_MILLIS = 15L * 60L * 1000L;
    static final long AUTO_OPEN_WINDOW_MILLIS = 20L * 60L * 1000L;
    static final long CANCELED_CLEAN_STABILITY_MILLIS = 30L * 1000L;
    static final long PROFILE_ABSENT_STABILITY_MILLIS = 10L * 1000L;

    private static final String PREFS_NAME = "privacy_space_settings";
    private static final String KEY_SECRET_CODE = "secret_code";
    private static final String KEY_HIDDEN_PACKAGES = "hidden_packages";
    private static final String KEY_PENDING_REHIDE = "pending_rehide";
    private static final String KEY_PROVISIONING_STATE = "provisioning_state";
    private static final String KEY_PROVISIONING_STARTED_AT = "provisioning_started_at";
    private static final String KEY_PROVISIONING_DETAIL = "provisioning_detail";
    private static final String KEY_PROVISIONING_ATTEMPT_ID = "provisioning_attempt_id";
    private static final String KEY_PROVISIONING_TARGET_USER_ID =
            "provisioning_target_user_id";
    private static final String KEY_PROVISIONING_CLEAN_SINCE =
            "provisioning_clean_since";
    private static final String KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS =
            "provisioning_clean_observations";
    private static final String KEY_AUTO_OPEN_PENDING = "provisioning_auto_open_pending";
    private static final String KEY_AUTO_OPEN_DEADLINE = "provisioning_auto_open_deadline";
    private static final String KEY_PENDING_MANAGER_CONNECTION_NONCE =
            "pending_manager_connection_nonce";
    private static final String KEY_MANAGED_PROFILE_INITIALIZED = "managed_profile_initialized";
    private static final String KEY_MANAGED_PROFILE_POLICY_VERSION =
            "managed_profile_policy_version";
    private static final String KEY_MANAGED_PROVISIONING_ATTEMPT_ID =
            "managed_provisioning_attempt_id";
    private static final String LABEL_PREFIX = "label:";

    private AppSettings() {
    }

    static String getSecretCode(Context context) {
        String value = preferences(context).getString(KEY_SECRET_CODE, DEFAULT_SECRET_CODE);
        return isValidSecretCode(value) ? value : DEFAULT_SECRET_CODE;
    }

    static void setSecretCode(Context context, String value) {
        if (!isValidSecretCode(value)) {
            throw new IllegalArgumentException("Secret code must contain at least one digit");
        }
        preferences(context).edit().putString(KEY_SECRET_CODE, value).apply();
    }

    static boolean isValidSecretCode(String value) {
        return value != null && value.matches("[0-9]+");
    }

    static String formatDialCode(String code) {
        return "*#*#" + code + "#*#*";
    }

    static Set<String> getHiddenPackages(Context context) {
        Set<String> stored = preferences(context).getStringSet(
                KEY_HIDDEN_PACKAGES,
                Collections.emptySet());
        return new HashSet<>(stored == null ? Collections.emptySet() : stored);
    }

    static void setHiddenPackages(Context context, Set<String> packages) {
        preferences(context)
                .edit()
                .putStringSet(KEY_HIDDEN_PACKAGES, new HashSet<>(packages))
                .apply();
    }

    static String getLabel(Context context, String packageName) {
        return preferences(context).getString(LABEL_PREFIX + packageName, packageName);
    }

    static void setLabel(Context context, String packageName, String label) {
        preferences(context).edit().putString(LABEL_PREFIX + packageName, label).apply();
    }

    static void removeLabel(Context context, String packageName) {
        preferences(context).edit().remove(LABEL_PREFIX + packageName).apply();
    }

    static String getPendingRehide(Context context) {
        return preferences(context).getString(KEY_PENDING_REHIDE, null);
    }

    static void setPendingRehide(Context context, String packageName) {
        preferences(context).edit().putString(KEY_PENDING_REHIDE, packageName).apply();
    }

    static void clearPendingRehide(Context context) {
        preferences(context).edit().remove(KEY_PENDING_REHIDE).apply();
    }

    static void markProfileProvisioningStarted(Context context) {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = preferences(context);
        long previousAttempt = prefs.getLong(KEY_PROVISIONING_ATTEMPT_ID, 0L);
        preferences(context)
                .edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_STARTED)
                .putLong(KEY_PROVISIONING_STARTED_AT, now)
                .putLong(KEY_PROVISIONING_ATTEMPT_ID, Math.max(now, previousAttempt + 1L))
                .putInt(KEY_PROVISIONING_TARGET_USER_ID, USER_ID_UNKNOWN)
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                .putBoolean(KEY_AUTO_OPEN_PENDING, true)
                .putLong(KEY_AUTO_OPEN_DEADLINE, now + AUTO_OPEN_WINDOW_MILLIS)
                .remove(KEY_PENDING_MANAGER_CONNECTION_NONCE)
                .remove(KEY_PROVISIONING_DETAIL)
                .commit();
    }

    static void markProfileProvisioningAccepted(Context context) {
        SharedPreferences prefs = preferences(context);
        int state = getProfileProvisioningState(context);
        if (state == PROVISIONING_SUCCEEDED
                || state == PROVISIONING_PROFILE_CREATED
                || state == PROVISIONING_PLATFORM_SUCCEEDED
                || state == PROVISIONING_CONNECTING
                || state == PROVISIONING_ROLLED_BACK) {
            return;
        }
        long startedAt = prefs.getLong(KEY_PROVISIONING_STARTED_AT, 0L);
        SharedPreferences.Editor editor = prefs.edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_ACCEPTED)
                .putString(KEY_PROVISIONING_DETAIL, "系统前置检查已通过，等待最终完成回执");
        if (startedAt <= 0L) {
            editor.putLong(KEY_PROVISIONING_STARTED_AT, System.currentTimeMillis());
        }
        editor.commit();
    }

    static void markProfileContainerCreated(Context context, int userId) {
        int state = getProfileProvisioningState(context);
        if (!hasProvisioningAttempt(context)
                || state == PROVISIONING_SUCCEEDED
                || state == PROVISIONING_PLATFORM_SUCCEEDED
                || state == PROVISIONING_CONNECTING
                || state == PROVISIONING_ROLLED_BACK) {
            return;
        }
        SharedPreferences.Editor editor = preferences(context).edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_PROFILE_CREATED)
                .putString(KEY_PROVISIONING_DETAIL, "系统已创建资料容器，正在完成管理配置");
        if (userId != USER_ID_UNKNOWN) {
            editor.putInt(KEY_PROVISIONING_TARGET_USER_ID, userId);
        }
        editor.commit();
    }

    static void rememberProfileUserId(Context context, int userId) {
        if (userId == USER_ID_UNKNOWN) {
            return;
        }
        SharedPreferences prefs = preferences(context);
        int expected = prefs.getInt(KEY_PROVISIONING_TARGET_USER_ID, USER_ID_UNKNOWN);
        if (expected == USER_ID_UNKNOWN) {
            prefs.edit().putInt(KEY_PROVISIONING_TARGET_USER_ID, userId).apply();
        }
    }

    static void markPlatformProvisioningSucceeded(Context context, int userId) {
        long now = System.currentTimeMillis();
        SharedPreferences prefs = preferences(context);
        int state = getProfileProvisioningState(context);
        if (state == PROVISIONING_ROLLED_BACK
                || state == PROVISIONING_CANCELED_CLEAN
                || state == PROVISIONING_PROFILE_ABSENT
                || !hasProvisioningAttempt(context)
                || !isExpectedProfileUser(context, userId)) {
            return;
        }
        if (state == PROVISIONING_SUCCEEDED
                || state == PROVISIONING_PLATFORM_SUCCEEDED
                || state == PROVISIONING_CONNECTING) {
            if (userId != USER_ID_UNKNOWN
                    && getProfileProvisioningTargetUserId(context) == USER_ID_UNKNOWN) {
                prefs.edit()
                        .putInt(KEY_PROVISIONING_TARGET_USER_ID, userId)
                        .apply();
            }
            return;
        }
        long startedAt = prefs.getLong(KEY_PROVISIONING_STARTED_AT, 0L);
        SharedPreferences.Editor editor = prefs.edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_PLATFORM_SUCCEEDED)
                .putString(KEY_PROVISIONING_DETAIL, "Android 已完成创建，正在连接管理页")
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                .putBoolean(KEY_AUTO_OPEN_PENDING, true)
                .putLong(KEY_AUTO_OPEN_DEADLINE, now + AUTO_OPEN_WINDOW_MILLIS);
        if (startedAt <= 0L) {
            editor.putLong(KEY_PROVISIONING_STARTED_AT, now)
                    .putLong(KEY_PROVISIONING_ATTEMPT_ID, now);
        }
        if (userId != USER_ID_UNKNOWN) {
            editor.putInt(KEY_PROVISIONING_TARGET_USER_ID, userId);
        }
        editor.commit();
    }

    static synchronized boolean acceptManagedProfileReady(
            Context context,
            long attemptId,
            int profileUserId) {
        int currentState = getProfileProvisioningState(context);
        if (!matchesProvisioningAttempt(context, attemptId)
                || profileUserId == USER_ID_UNKNOWN
                || !isExpectedProfileUser(context, profileUserId)
                || !canAcceptManagerConnection(currentState)) {
            return false;
        }
        if (currentState == PROVISIONING_CANCELED_CLEAN
                || currentState == PROVISIONING_PROFILE_ABSENT) {
            long now = System.currentTimeMillis();
            preferences(context)
                    .edit()
                    .putInt(KEY_PROVISIONING_STATE, PROVISIONING_PLATFORM_SUCCEEDED)
                    .putString(KEY_PROVISIONING_DETAIL, "Android 已完成创建，正在连接管理页")
                    .putInt(KEY_PROVISIONING_TARGET_USER_ID, profileUserId)
                    .remove(KEY_PROVISIONING_CLEAN_SINCE)
                    .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                    .putBoolean(KEY_AUTO_OPEN_PENDING, true)
                    .putLong(KEY_AUTO_OPEN_DEADLINE, now + AUTO_OPEN_WINDOW_MILLIS)
                    .commit();
            return true;
        }
        markPlatformProvisioningSucceeded(context, profileUserId);
        int state = getProfileProvisioningState(context);
        return state == PROVISIONING_PLATFORM_SUCCEEDED
                || state == PROVISIONING_CONNECTING;
    }

    static void markProfileConnecting(Context context) {
        int state = getProfileProvisioningState(context);
        if (state == PROVISIONING_SUCCEEDED
                || state == PROVISIONING_CONNECTING
                || state == PROVISIONING_ROLLED_BACK) {
            return;
        }
        long now = System.currentTimeMillis();
        preferences(context)
                .edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_CONNECTING)
                .putString(KEY_PROVISIONING_DETAIL, "资料已启用，正在建立管理连接")
                .putBoolean(KEY_AUTO_OPEN_PENDING, true)
                .putLong(KEY_AUTO_OPEN_DEADLINE, now + AUTO_OPEN_WINDOW_MILLIS)
                .commit();
    }

    static void markProfileProvisioningCancelPending(Context context, String detail) {
        int state = getProfileProvisioningState(context);
        if (state == PROVISIONING_SUCCEEDED
                || state == PROVISIONING_PLATFORM_SUCCEEDED
                || state == PROVISIONING_CONNECTING
                || state == PROVISIONING_PROFILE_CREATED
                || state == PROVISIONING_ROLLED_BACK
                || state == PROVISIONING_CANCELED_CLEAN) {
            return;
        }
        preferences(context)
                .edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_CANCEL_PENDING)
                .putString(KEY_PROVISIONING_DETAIL, detail)
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                .commit();
    }

    static void markCanceledWithoutProfile(Context context) {
        if (getProfileProvisioningState(context) != PROVISIONING_CANCEL_PENDING) {
            return;
        }
        preferences(context)
                .edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_CANCELED_CLEAN)
                .putString(
                        KEY_PROVISIONING_DETAIL,
                        "系统持续允许创建，且未检测到本次流程生成的资料")
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                .putBoolean(KEY_AUTO_OPEN_PENDING, false)
                .remove(KEY_AUTO_OPEN_DEADLINE)
                .remove(KEY_PENDING_MANAGER_CONNECTION_NONCE)
                .commit();
    }

    static void markObservedProfileAbsent(Context context) {
        int state = getProfileProvisioningState(context);
        if (state == PROVISIONING_NONE
                || state == PROVISIONING_ROLLED_BACK
                || state == PROVISIONING_CANCELED_CLEAN
                || state == PROVISIONING_PROFILE_ABSENT) {
            return;
        }
        preferences(context)
                .edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_PROFILE_ABSENT)
                .putString(
                        KEY_PROVISIONING_DETAIL,
                        "系统持续未检测到本次流程生成的资料，并已恢复创建能力")
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                .putBoolean(KEY_AUTO_OPEN_PENDING, false)
                .remove(KEY_AUTO_OPEN_DEADLINE)
                .remove(KEY_PENDING_MANAGER_CONNECTION_NONCE)
                .commit();
    }

    static void markProfileProvisioningTimeoutUnknown(Context context) {
        int state = getProfileProvisioningState(context);
        if (state == PROVISIONING_SUCCEEDED || state == PROVISIONING_ROLLED_BACK) {
            return;
        }
        preferences(context)
                .edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_TIMEOUT_UNKNOWN)
                .putString(KEY_PROVISIONING_DETAIL, "等待超过 15 分钟，系统未提供最终结果")
                .commit();
    }

    static void markProfileRollbackConfirmed(Context context) {
        preferences(context)
                .edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_ROLLED_BACK)
                .putString(KEY_PROVISIONING_DETAIL, "已收到 Android 的工作资料删除回执")
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                .putBoolean(KEY_AUTO_OPEN_PENDING, false)
                .remove(KEY_AUTO_OPEN_DEADLINE)
                .remove(KEY_PENDING_MANAGER_CONNECTION_NONCE)
                .commit();
    }

    static int getProfileProvisioningState(Context context) {
        SharedPreferences prefs = preferences(context);
        int state = prefs.getInt(KEY_PROVISIONING_STATE, PROVISIONING_NONE);
        if (state == PROVISIONING_NONE
                && prefs.getLong(KEY_PROVISIONING_STARTED_AT, 0L) > 0L) {
            return PROVISIONING_STARTED;
        }
        return state;
    }

    static long getProfileProvisioningStartedAt(Context context) {
        return preferences(context).getLong(KEY_PROVISIONING_STARTED_AT, 0L);
    }

    static String getProfileProvisioningDetail(Context context) {
        return preferences(context).getString(KEY_PROVISIONING_DETAIL, "");
    }

    static long getProfileProvisioningAttemptId(Context context) {
        return preferences(context).getLong(KEY_PROVISIONING_ATTEMPT_ID, 0L);
    }

    static int getProfileProvisioningTargetUserId(Context context) {
        return preferences(context).getInt(
                KEY_PROVISIONING_TARGET_USER_ID,
                USER_ID_UNKNOWN);
    }

    static boolean isExpectedProfileUser(Context context, int userId) {
        int expected = getProfileProvisioningTargetUserId(context);
        return userId == USER_ID_UNKNOWN
                || expected == USER_ID_UNKNOWN
                || expected == userId;
    }

    static boolean isConfirmedExpectedProfileUser(Context context, int userId) {
        int expected = getProfileProvisioningTargetUserId(context);
        return userId != USER_ID_UNKNOWN
                && expected != USER_ID_UNKNOWN
                && expected == userId;
    }

    static boolean hasProvisioningAttempt(Context context) {
        return getProfileProvisioningAttemptId(context) > 0L
                || getProfileProvisioningStartedAt(context) > 0L;
    }

    static boolean isProvisioningInProgress(int state) {
        return state == PROVISIONING_STARTED
                || state == PROVISIONING_ACCEPTED
                || state == PROVISIONING_PROFILE_CREATED
                || state == PROVISIONING_PLATFORM_SUCCEEDED
                || state == PROVISIONING_CONNECTING
                || state == PROVISIONING_CANCEL_PENDING;
    }

    static long noteCleanProvisioningObservation(Context context) {
        SharedPreferences prefs = preferences(context);
        long now = System.currentTimeMillis();
        long since = prefs.getLong(KEY_PROVISIONING_CLEAN_SINCE, 0L);
        if (since <= 0L || since > now) {
            since = now;
            prefs.edit().putLong(KEY_PROVISIONING_CLEAN_SINCE, since).commit();
        }
        return Math.max(0L, now - since);
    }

    static void clearCleanProvisioningObservations(Context context) {
        preferences(context)
                .edit()
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                .apply();
    }

    static boolean shouldAutoOpenManager(Context context) {
        SharedPreferences prefs = preferences(context);
        if (!prefs.getBoolean(KEY_AUTO_OPEN_PENDING, false)) {
            return false;
        }
        long deadline = prefs.getLong(KEY_AUTO_OPEN_DEADLINE, 0L);
        if (deadline > 0L && System.currentTimeMillis() > deadline) {
            markAutoOpenHandled(context);
            return false;
        }
        return true;
    }

    static void markAutoOpenHandled(Context context) {
        preferences(context)
                .edit()
                .putBoolean(KEY_AUTO_OPEN_PENDING, false)
                .remove(KEY_AUTO_OPEN_DEADLINE)
                .remove(KEY_PENDING_MANAGER_CONNECTION_NONCE)
                .apply();
    }

    static synchronized long getOrCreateManagerConnectionNonce(Context context) {
        SharedPreferences prefs = preferences(context);
        long existing = prefs.getLong(KEY_PENDING_MANAGER_CONNECTION_NONCE, 0L);
        if (existing != 0L) {
            return existing;
        }

        long nonce;
        do {
            nonce = CONNECTION_NONCE_RANDOM.nextLong();
        } while (nonce == 0L);
        prefs.edit()
                .putLong(KEY_PENDING_MANAGER_CONNECTION_NONCE, nonce)
                .commit();
        return nonce;
    }

    static synchronized boolean confirmManagerConnection(
            Context context,
            long attemptId,
            long nonce,
            int profileUserId) {
        SharedPreferences prefs = preferences(context);
        long expectedAttempt = prefs.getLong(KEY_PROVISIONING_ATTEMPT_ID, 0L);
        long expectedNonce = prefs.getLong(KEY_PENDING_MANAGER_CONNECTION_NONCE, 0L);
        int state = getProfileProvisioningState(context);
        int expectedUserId = prefs.getInt(
                KEY_PROVISIONING_TARGET_USER_ID,
                USER_ID_UNKNOWN);
        boolean trustedAttemptMatches = attemptId > 0L && attemptId == expectedAttempt;
        boolean legacyProfileMatchesKnownUser = attemptId == 0L
                && expectedUserId != USER_ID_UNKNOWN
                && expectedUserId == profileUserId;

        if (expectedAttempt <= 0L
                || (!trustedAttemptMatches && !legacyProfileMatchesKnownUser)
                || nonce == 0L
                || nonce != expectedNonce
                || profileUserId == USER_ID_UNKNOWN
                || (expectedUserId != USER_ID_UNKNOWN
                        && expectedUserId != profileUserId)
                || !canAcceptManagerConnection(state)) {
            return false;
        }

        SharedPreferences.Editor editor = prefs.edit()
                .putInt(KEY_PROVISIONING_STATE, PROVISIONING_SUCCEEDED)
                .putString(KEY_PROVISIONING_DETAIL, "管理连接已验证，可以正常使用")
                .putBoolean(KEY_AUTO_OPEN_PENDING, false)
                .remove(KEY_AUTO_OPEN_DEADLINE)
                .remove(KEY_PENDING_MANAGER_CONNECTION_NONCE)
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS);
        if (expectedUserId == USER_ID_UNKNOWN) {
            editor.putInt(KEY_PROVISIONING_TARGET_USER_ID, profileUserId);
        }
        return editor.commit();
    }

    private static boolean canAcceptManagerConnection(int state) {
        return state == PROVISIONING_STARTED
                || state == PROVISIONING_ACCEPTED
                || state == PROVISIONING_PROFILE_CREATED
                || state == PROVISIONING_PLATFORM_SUCCEEDED
                || state == PROVISIONING_CONNECTING
                || state == PROVISIONING_CANCEL_PENDING
                || state == PROVISIONING_TIMEOUT_UNKNOWN
                || state == PROVISIONING_CANCELED_CLEAN
                || state == PROVISIONING_PROFILE_ABSENT;
    }

    static boolean needsManagerConnectionVerification(Context context) {
        return getProfileProvisioningAttemptId(context) > 0L
                && canAcceptManagerConnection(getProfileProvisioningState(context));
    }

    static void clearPendingManagerConnectionNonce(Context context) {
        preferences(context)
                .edit()
                .remove(KEY_PENDING_MANAGER_CONNECTION_NONCE)
                .apply();
    }

    static void clearProfileProvisioningState(Context context) {
        preferences(context)
                .edit()
                .remove(KEY_PROVISIONING_STATE)
                .remove(KEY_PROVISIONING_STARTED_AT)
                .remove(KEY_PROVISIONING_DETAIL)
                .remove(KEY_PROVISIONING_ATTEMPT_ID)
                .remove(KEY_PROVISIONING_TARGET_USER_ID)
                .remove(KEY_PROVISIONING_CLEAN_SINCE)
                .remove(KEY_LEGACY_PROVISIONING_CLEAN_OBSERVATIONS)
                .remove(KEY_AUTO_OPEN_PENDING)
                .remove(KEY_AUTO_OPEN_DEADLINE)
                .remove(KEY_PENDING_MANAGER_CONNECTION_NONCE)
                .commit();
    }

    static boolean isManagedProfileInitialized(Context context) {
        return getManagedProfilePolicyVersion(context) > 0;
    }

    static int getManagedProfilePolicyVersion(Context context) {
        SharedPreferences prefs = preferences(context);
        int version = prefs.getInt(KEY_MANAGED_PROFILE_POLICY_VERSION, 0);
        if (version == 0 && prefs.getBoolean(KEY_MANAGED_PROFILE_INITIALIZED, false)) {
            return 1;
        }
        return version;
    }

    static void markManagedProfileInitialized(Context context, int policyVersion) {
        preferences(context)
                .edit()
                .putBoolean(KEY_MANAGED_PROFILE_INITIALIZED, true)
                .putInt(KEY_MANAGED_PROFILE_POLICY_VERSION, policyVersion)
                .commit();
    }

    static void setManagedProvisioningAttemptId(Context context, long attemptId) {
        if (attemptId <= 0L) {
            return;
        }
        preferences(context)
                .edit()
                .putLong(KEY_MANAGED_PROVISIONING_ATTEMPT_ID, attemptId)
                .commit();
    }

    static long getManagedProvisioningAttemptId(Context context) {
        return preferences(context).getLong(KEY_MANAGED_PROVISIONING_ATTEMPT_ID, 0L);
    }

    static boolean matchesProvisioningAttempt(Context context, long attemptId) {
        long expected = getProfileProvisioningAttemptId(context);
        return expected > 0L && attemptId > 0L && expected == attemptId;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
