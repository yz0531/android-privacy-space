package com.example.privateentry;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;

/** Android 12+ admin-integrated provisioning callback. */
public final class ProvisioningModeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ArrayList<Integer> allowedModes = getIntent().getIntegerArrayListExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES);
        if (allowedModes == null || allowedModes.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        int selectedMode;
        if (allowedModes.contains(
                DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE_ON_PERSONAL_DEVICE)) {
            selectedMode = DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE_ON_PERSONAL_DEVICE;
        } else if (allowedModes.contains(DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE)) {
            selectedMode = DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE;
        } else {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        Intent result = new Intent();
        result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, selectedMode);
        setResult(RESULT_OK, result);
        finish();
    }
}
