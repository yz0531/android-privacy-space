package com.example.privateentry;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Parent-profile bridge for provisioning completion and manager-resumed acknowledgements. */
public final class ProvisioningReadyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (PrivacyAdminReceiver.isProfileOwner(this)) {
            finish();
            return;
        }

        Intent request = getIntent();
        long attemptId = request.getLongExtra(
                AppContract.EXTRA_PROVISIONING_ATTEMPT_ID,
                0L);
        long connectionNonce = request.getLongExtra(
                AppContract.EXTRA_MANAGER_CONNECTION_NONCE,
                0L);
        int profileUserId = request.getIntExtra(
                AppContract.EXTRA_MANAGER_PROFILE_USER_ID,
                AppSettings.USER_ID_UNKNOWN);
        boolean managerReady = request.getBooleanExtra(
                AppContract.EXTRA_MANAGER_READY_ACK,
                false);
        if (managerReady) {
            AppSettings.confirmManagerConnection(
                    this,
                    attemptId,
                    connectionNonce,
                    profileUserId);
            finish();
            return;
        }

        if (!AppSettings.acceptManagedProfileReady(this, attemptId, profileUserId)) {
            finish();
            return;
        }

        long managerConnectionNonce =
                AppSettings.getOrCreateManagerConnectionNonce(this);

        Intent manager = new Intent(AppContract.ACTION_OPEN_MANAGER)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(AppContract.EXTRA_PROVISIONING_ATTEMPT_ID, attemptId)
                .putExtra(
                        AppContract.EXTRA_MANAGER_CONNECTION_NONCE,
                        managerConnectionNonce)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        CrossProfileNavigator.start(this, manager);
        finish();
    }
}
