package com.example.privateentry;

final class AppContract {
    static final String ACTION_OPEN_MANAGER =
            "com.example.privateentry.action.OPEN_MANAGER";
    static final String ACTION_INSTALL_IN_MANAGED =
            "com.example.privateentry.action.INSTALL_IN_MANAGED";
    static final String ACTION_INSTALL_IN_PARENT =
            "com.example.privateentry.action.INSTALL_IN_PARENT";
    static final String ACTION_INSTALL_APK_IN_MANAGED =
            "com.example.privateentry.action.INSTALL_APK_IN_MANAGED";
    static final String ACTION_CLEANUP_APK_IN_PARENT =
            "com.example.privateentry.action.CLEANUP_APK_IN_PARENT";
    static final String ACTION_DELETE_IN_MANAGED =
            "com.example.privateentry.action.DELETE_IN_MANAGED";
    static final String ACTION_DELETE_IN_PARENT =
            "com.example.privateentry.action.DELETE_IN_PARENT";
    static final String ACTION_PROFILE_READY =
            "com.example.privateentry.action.PROFILE_READY";
    static final String EXTRA_OPEN_MANAGER = "open_manager";
    static final String EXTRA_PROVISIONING_ATTEMPT_ID = "provisioning_attempt_id";
    static final String EXTRA_MANAGER_READY_ACK = "manager_ready_ack";
    static final String EXTRA_MANAGER_CONNECTION_NONCE = "manager_connection_nonce";
    static final String EXTRA_MANAGER_PROFILE_USER_ID = "manager_profile_user_id";
    static final String EXTRA_APK_TOKEN = "apk_token";
    static final String EXTRA_PACKAGE_NAME = "package_name";
    static final String EXTRA_APP_LABEL = "app_label";
    static final String EXTRA_APK_DISPLAY_NAME = "apk_display_name";
    static final String EXTRA_APK_SIZE = "apk_size";
    static final String PROFILE_NAME = "隐私空间";

    private AppContract() {
    }
}
