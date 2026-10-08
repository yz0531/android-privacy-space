package com.example.privateentry;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.os.Bundle;

/** Visible bootstrap entry in the personal profile only. */
public final class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DevicePolicyManager policyManager = getSystemService(DevicePolicyManager.class);
        Class<?> destination = policyManager != null
                && policyManager.isProfileOwnerApp(getPackageName())
                ? ManagerActivity.class
                : MainActivity.class;

        startActivity(new Intent(this, destination));
        finish();
    }
}
