package com.example.privateentry;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Package validation shared by the personal and managed profile transfer screens. */
final class TransferApps {
    private TransferApps() {
    }

    static List<Entry> listTransferableLauncherApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcherIntent, 0);
        Map<String, Entry> byPackage = new LinkedHashMap<>();

        for (ResolveInfo resolveInfo : resolved) {
            if (resolveInfo.activityInfo == null
                    || resolveInfo.activityInfo.applicationInfo == null) {
                continue;
            }
            String packageName = resolveInfo.activityInfo.packageName;
            if (!isSafeUserApplication(context, packageName)) {
                continue;
            }
            String label = loadLabel(context, packageName);
            byPackage.putIfAbsent(packageName, new Entry(packageName, label));
        }

        List<Entry> entries = new ArrayList<>(byPackage.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        entries.sort((left, right) -> {
            int labelResult = collator.compare(left.label, right.label);
            return labelResult != 0
                    ? labelResult
                    : left.packageName.compareTo(right.packageName);
        });
        return entries;
    }

    static boolean isSafeUserApplication(Context context, String packageName) {
        if (!isValidPackageName(packageName)
                || context.getPackageName().equals(packageName)) {
            return false;
        }

        ApplicationInfo info = getInstalledApplicationInfo(context, packageName);
        if (info == null
                || !info.enabled
                || isInstantApp(context, packageName)
                || (info.flags & ApplicationInfo.FLAG_HAS_CODE) == 0
                || (info.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            return false;
        }

        if (criticalPackages(context).contains(packageName)) {
            return false;
        }

        DevicePolicyManager policyManager = context.getSystemService(DevicePolicyManager.class);
        if (policyManager != null) {
            try {
                List<ComponentName> admins = policyManager.getActiveAdmins();
                if (admins != null) {
                    for (ComponentName admin : admins) {
                        if (packageName.equals(admin.getPackageName())) {
                            return false;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Continue with the remaining safety checks on customized systems.
            }
        }
        return true;
    }

    private static boolean isInstantApp(Context context, String packageName) {
        try {
            return context.getPackageManager().isInstantApp(packageName);
        } catch (RuntimeException error) {
            return false;
        }
    }

    static boolean isInstalledForCurrentUser(Context context, String packageName) {
        return getInstalledApplicationInfo(context, packageName) != null;
    }

    static boolean isEnabledForCurrentUser(Context context, String packageName) {
        ApplicationInfo info = getInstalledApplicationInfo(context, packageName);
        return info != null && info.enabled;
    }

    static String loadLabel(Context context, String packageName) {
        ApplicationInfo info = getInstalledApplicationInfo(context, packageName);
        if (info == null) {
            return packageName;
        }
        CharSequence loaded = info.loadLabel(context.getPackageManager());
        if (loaded == null || loaded.toString().trim().isEmpty()) {
            return packageName;
        }
        return loaded.toString().trim();
    }

    static Drawable loadIcon(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        ApplicationInfo info = getInstalledApplicationInfo(context, packageName);
        if (info != null) {
            try {
                Drawable icon = info.loadIcon(packageManager);
                if (icon != null) {
                    return icon;
                }
            } catch (RuntimeException ignored) {
                // Some customized systems hide package resources together with the app state.
            }
        }
        return packageManager.getDefaultActivityIcon();
    }

    static boolean isValidPackageName(String value) {
        return value != null
                && value.length() <= 255
                && !value.startsWith(".")
                && !value.endsWith(".")
                && !value.contains("..")
                && value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+");
    }

    private static ApplicationInfo getInstalledApplicationInfo(
            Context context,
            String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    packageName,
                    PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_UNINSTALLED_PACKAGES);
            return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0 ? info : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return null;
        }
    }

    private static Set<String> criticalPackages(Context context) {
        Set<String> packages = new HashSet<>();
        PackageManager packageManager = context.getPackageManager();

        ResolveInfo home = packageManager.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) {
            packages.add(home.activityInfo.packageName);
        }

        TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
        if (telecomManager != null) {
            try {
                String dialer = telecomManager.getDefaultDialerPackage();
                if (dialer != null) {
                    packages.add(dialer);
                }
            } catch (RuntimeException ignored) {
                // The package is still filtered by the remaining safety checks.
            }
        }

        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(context);
            if (sms != null) {
                packages.add(sms);
            }
        } catch (RuntimeException ignored) {
            // Some Wi-Fi-only and customized systems do not expose an SMS role.
        }

        InputMethodManager inputMethodManager =
                context.getSystemService(InputMethodManager.class);
        if (inputMethodManager != null) {
            try {
                for (InputMethodInfo method : inputMethodManager.getEnabledInputMethodList()) {
                    packages.add(method.getPackageName());
                }
            } catch (RuntimeException ignored) {
                // Continue with the packages already identified.
            }
        }
        return packages;
    }

    static final class Entry {
        final String packageName;
        final String label;

        Entry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }
}
