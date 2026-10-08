package com.example.privateentry;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.util.List;

/** Starts only the system-owned cross-profile forwarder for an approved custom action. */
final class CrossProfileNavigator {
    private static final String LEGACY_FORWARDER_TARGET =
            "com.android.internal.app.IntentForwarderActivity";

    private CrossProfileNavigator() {
    }

    static boolean start(Context context, Intent intent) {
        ComponentName forwarder = findSystemCrossProfileForwarder(context, intent);
        if (forwarder == null) {
            return false;
        }
        Intent securedIntent = new Intent(intent).setComponent(forwarder);
        try {
            context.startActivity(securedIntent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean canStart(Context context, Intent intent) {
        return findSystemCrossProfileForwarder(context, intent) != null;
    }

    private static ComponentName findSystemCrossProfileForwarder(
            Context context,
            Intent intent) {
        PackageManager packageManager = context.getPackageManager();
        List<ResolveInfo> candidates = packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo candidate : candidates) {
            if (!isSystemCrossProfileForwarder(candidate)
                    || candidate.activityInfo == null) {
                continue;
            }
            return new ComponentName(
                    candidate.activityInfo.packageName,
                    candidate.activityInfo.name);
        }
        return null;
    }

    private static boolean isSystemCrossProfileForwarder(ResolveInfo resolveInfo) {
        if (resolveInfo.activityInfo == null) {
            return false;
        }
        if (!"android".equals(resolveInfo.activityInfo.packageName)) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            return resolveInfo.isCrossProfileIntentForwarderActivity();
        }
        return LEGACY_FORWARDER_TARGET.equals(resolveInfo.activityInfo.targetActivity);
    }
}
