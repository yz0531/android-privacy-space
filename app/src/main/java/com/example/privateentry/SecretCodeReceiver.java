package com.example.privateentry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Opens the private app grid when the configured dialer secret code is entered. */
public final class SecretCodeReceiver extends BroadcastReceiver {
    private static final String ACTION_SECRET_CODE = "android.telephony.action.SECRET_CODE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SECRET_CODE.equals(intent.getAction())) {
            return;
        }

        Uri data = intent.getData();
        if (data == null || !"android_secret_code".equals(data.getScheme())) {
            return;
        }

        String receivedCode = data.getHost();
        if (!AppSettings.isValidSecretCode(receivedCode)
                || !AppSettings.getSecretCode(context).equals(receivedCode)) {
            return;
        }

        Intent openHome = new Intent(AppContract.ACTION_OPEN_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (CrossProfileNavigator.start(context, openHome)) {
            return;
        }

        Intent repairAndOpen = new Intent(AppContract.ACTION_OPEN_MANAGER)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(AppContract.EXTRA_OPEN_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (CrossProfileNavigator.start(context, repairAndOpen)) {
            return;
        }

        // If the profile is unavailable or still needs its post-update policy refresh, keep a
        // recoverable entry in the personal profile instead of silently doing nothing.
        Intent openSetup = new Intent(context, MainActivity.class)
                .putExtra(AppContract.EXTRA_OPEN_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(openSetup);
    }
}
