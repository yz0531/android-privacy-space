package com.example.privateentry;

import android.app.Activity;
import android.os.Bundle;

/** Completes provisioning after registering the profile policies. */
public final class PolicyComplianceActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PrivacyAdminReceiver.rememberProvisioningAttempt(this, getIntent());
        boolean configured = PrivacyAdminReceiver.configureManagedProfile(this);
        setResult(configured ? RESULT_OK : RESULT_CANCELED);

        finish();
    }
}
