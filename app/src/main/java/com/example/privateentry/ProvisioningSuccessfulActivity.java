package com.example.privateentry;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.Gravity;
import android.widget.TextView;

/** Finishes profile-owner setup and enters the manager as one provisioning flow. */
public final class ProvisioningSuccessfulActivity extends Activity {
    private static final int MAX_INITIALIZATION_ATTEMPTS = 6;
    private static final long RETRY_DELAY_MILLIS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int attemptCount;
    private boolean completed;

    private final Runnable initializeProfile = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }

            attemptCount++;
            if (PrivacyAdminReceiver.configureManagedProfile(
                    ProvisioningSuccessfulActivity.this)) {
                completed = true;
                setResult(RESULT_OK);
                Intent ready = new Intent(AppContract.ACTION_PROFILE_READY)
                        .addCategory(Intent.CATEGORY_DEFAULT)
                        .putExtra(
                                AppContract.EXTRA_PROVISIONING_ATTEMPT_ID,
                                AppSettings.getManagedProvisioningAttemptId(
                                        ProvisioningSuccessfulActivity.this))
                        .putExtra(
                                AppContract.EXTRA_MANAGER_PROFILE_USER_ID,
                                Process.myUid() / 100000);
                if (!CrossProfileNavigator.start(
                        ProvisioningSuccessfulActivity.this,
                        ready)) {
                    startActivity(new Intent(
                            ProvisioningSuccessfulActivity.this,
                            ManagerActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                }
                finish();
                return;
            }

            if (attemptCount < MAX_INITIALIZATION_ATTEMPTS) {
                handler.postDelayed(this, RETRY_DELAY_MILLIS);
                return;
            }

            completed = true;
            setResult(RESULT_CANCELED);
            PrivacyAdminReceiver.rollbackIncompleteProfile(
                    ProvisioningSuccessfulActivity.this);
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PrivacyAdminReceiver.rememberProvisioningAttempt(this, getIntent());

        TextView message = new TextView(this);
        message.setText("正在完成隐私空间设置…");
        message.setTextSize(18);
        message.setTextColor(Color.rgb(25, 28, 35));
        message.setGravity(Gravity.CENTER);
        message.setPadding(48, 48, 48, 48);
        setContentView(message);

        handler.post(initializeProfile);
    }

    @Override
    public void onBackPressed() {
        // Provisioning owns this short-lived screen; leaving halfway can strand a hidden profile.
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(initializeProfile);
        if (isFinishing() && !isChangingConfigurations() && !completed) {
            PrivacyAdminReceiver.rollbackIncompleteProfile(this);
        }
        super.onDestroy();
    }
}
