package com.example.privateentry;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/** Confirms removal of the source-profile copy after a verified cross-profile install. */
public class RemovePackageActivity extends Activity {
    private static final int REQUEST_UNINSTALL = 2201;

    private String packageName;
    private String appLabel;
    private boolean sourceIsManaged;
    private boolean uninstallStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!readAndValidateRequest(getIntent())) {
            finishWithMessage("无效的删除请求");
            return;
        }
        renderConfirmation(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!readAndValidateRequest(intent)) {
            finishWithMessage("无效的删除请求");
            return;
        }
        renderConfirmation(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_UNINSTALL) {
            return;
        }
        uninstallStarted = false;

        if (!TransferApps.isInstalledForCurrentUser(this, packageName)) {
            clearManagedTrackingIfNeeded();
            renderResult("原空间副本已删除。", true);
        } else {
            restoreManagedHiddenState();
            renderConfirmation("删除已取消或未完成，原空间副本和数据仍然保留。");
        }
    }

    private boolean readAndValidateRequest(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        boolean profileOwner = PrivacyAdminReceiver.isProfileOwner(this);
        if (AppContract.ACTION_DELETE_IN_MANAGED.equals(action)) {
            sourceIsManaged = true;
            if (!profileOwner) {
                return false;
            }
        } else if (AppContract.ACTION_DELETE_IN_PARENT.equals(action)) {
            sourceIsManaged = false;
            if (profileOwner) {
                return false;
            }
        } else {
            return false;
        }

        packageName = intent.getStringExtra(AppContract.EXTRA_PACKAGE_NAME);
        if (!TransferApps.isValidPackageName(packageName)
                || getPackageName().equals(packageName)) {
            return false;
        }
        boolean trackedHidden = sourceIsManaged
                && AppSettings.getHiddenPackages(this).contains(packageName);
        if (sourceIsManaged
                && !trackedHidden
                && !TransferApps.isSafeUserApplication(this, packageName)) {
            return false;
        }
        if (!sourceIsManaged && !TransferApps.isSafeUserApplication(this, packageName)) {
            return false;
        }

        String suppliedLabel = intent.getStringExtra(AppContract.EXTRA_APP_LABEL);
        appLabel = suppliedLabel == null || suppliedLabel.trim().isEmpty()
                ? (sourceIsManaged
                        ? AppSettings.getLabel(this, packageName)
                        : TransferApps.loadLabel(this, packageName))
                : suppliedLabel.trim();
        if (appLabel.length() > 100) {
            appLabel = appLabel.substring(0, 100);
        }
        return true;
    }

    private void renderConfirmation(String statusMessage) {
        LinearLayout root = baseLayout();
        addTitle(root, sourceIsManaged ? "删除隐私空间副本" : "删除主空间副本");

        TextView app = textView(appLabel + "\n" + packageName, 17, Color.rgb(30, 34, 42));
        app.setGravity(Gravity.CENTER);
        addWithTopMargin(root, app, 20);

        TextView warning = textView(
                "这是独立的第二步。删除会永久清除该空间内的应用数据、登录状态和本地文件，但不会影响刚刚安装到另一空间的副本。",
                15,
                Color.rgb(150, 55, 45));
        warning.setGravity(Gravity.CENTER);
        addWithTopMargin(root, warning, 20);

        if (statusMessage != null) {
            TextView status = textView(statusMessage, 14, Color.rgb(105, 78, 35));
            status.setGravity(Gravity.CENTER);
            addWithTopMargin(root, status, 14);
        }

        Button deleteButton = button("继续，打开系统卸载确认");
        deleteButton.setOnClickListener(view -> launchSystemUninstaller());
        addWithTopMargin(root, deleteButton, 26);

        Button keepButton = button("保留两个空间的副本");
        keepButton.setOnClickListener(view -> finish());
        addWithTopMargin(root, keepButton, 10);
        setContentView(wrap(root));
    }

    @SuppressWarnings("deprecation")
    private void launchSystemUninstaller() {
        if (sourceIsManaged && !unhideManagedSource()) {
            renderConfirmation("无法临时恢复隐私空间副本，因此没有启动卸载。");
            return;
        }

        if (!TransferApps.isInstalledForCurrentUser(this, packageName)) {
            clearManagedTrackingIfNeeded();
            renderResult("原空间中已经没有这个应用。", true);
            return;
        }

        Intent uninstall = new Intent(
                Intent.ACTION_UNINSTALL_PACKAGE,
                Uri.fromParts("package", packageName, null))
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        if (uninstall.resolveActivity(getPackageManager()) == null) {
            restoreManagedHiddenState();
            renderConfirmation("当前系统没有可用的卸载确认页面。");
            return;
        }

        try {
            uninstallStarted = true;
            startActivityForResult(uninstall, REQUEST_UNINSTALL);
        } catch (ActivityNotFoundException | SecurityException error) {
            uninstallStarted = false;
            restoreManagedHiddenState();
            renderConfirmation("系统拒绝启动卸载流程。");
        }
    }

    private boolean unhideManagedSource() {
        if (!AppSettings.getHiddenPackages(this).contains(packageName)) {
            return true;
        }
        DevicePolicyManager policyManager = getSystemService(DevicePolicyManager.class);
        ComponentName admin = PrivacyAdminReceiver.component(this);
        try {
            boolean updated = policyManager != null
                    && policyManager.setApplicationHidden(admin, packageName, false);
            boolean hidden = policyManager != null
                    && policyManager.isApplicationHidden(admin, packageName);
            if (updated || !hidden) {
                AppSettings.setPendingRehide(this, packageName);
                return true;
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // The caller receives a safe failure below.
        }
        return false;
    }

    private void restoreManagedHiddenState() {
        if (!sourceIsManaged
                || !AppSettings.getHiddenPackages(this).contains(packageName)) {
            return;
        }
        DevicePolicyManager policyManager = getSystemService(DevicePolicyManager.class);
        boolean restored = false;
        try {
            if (policyManager != null) {
                boolean changed = policyManager.setApplicationHidden(
                        PrivacyAdminReceiver.component(this),
                        packageName,
                        true);
                restored = changed || policyManager.isApplicationHidden(
                        PrivacyAdminReceiver.component(this),
                        packageName);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // ManagerActivity will retry from its persistent pending marker.
        }
        if (restored) {
            AppSettings.clearPendingRehide(this);
        }
    }

    private void clearManagedTrackingIfNeeded() {
        if (!sourceIsManaged) {
            return;
        }
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        hiddenPackages.remove(packageName);
        AppSettings.setHiddenPackages(this, hiddenPackages);
        AppSettings.removeLabel(this, packageName);
        AppSettings.clearPendingRehide(this);
    }

    private void renderResult(String message, boolean success) {
        LinearLayout root = baseLayout();
        addTitle(root, "操作完成");
        TextView result = textView(
                message,
                16,
                success ? Color.rgb(25, 120, 72) : Color.rgb(150, 55, 45));
        result.setGravity(Gravity.CENTER);
        addWithTopMargin(root, result, 24);
        Button done = button("完成");
        done.setOnClickListener(view -> finish());
        addWithTopMargin(root, done, 22);
        setContentView(wrap(root));
    }

    private void finishWithMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private LinearLayout baseLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(42), dp(24), dp(28));
        root.setBackgroundColor(Color.rgb(248, 249, 252));
        return root;
    }

    private ScrollView wrap(LinearLayout root) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private void addTitle(LinearLayout root, String text) {
        TextView title = textView(text, 25, Color.rgb(25, 28, 35));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWidthWrapHeight());
    }

    private TextView textView(String text, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private void addWithTopMargin(LinearLayout root, android.view.View view, int topMarginDp) {
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.topMargin = dp(topMarginDp);
        root.addView(view, params);
    }

    private LinearLayout.LayoutParams matchWidthWrapHeight() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
