package com.example.privateentry;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;

/** Installs a package already present in the other profile into the current profile. */
public class InstallExistingActivity extends Activity {
    private static final int REQUEST_INSTALL = 2101;

    private String packageName;
    private String appLabel;
    private boolean destinationIsManaged;
    private boolean openedUnknownSourcesSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!readAndValidateRequest(getIntent())) {
            finishWithMessage("无效的应用转移请求");
            return;
        }

        if (TransferApps.isInstalledForCurrentUser(this, packageName)) {
            finishInstallation();
        } else {
            renderInstallPrompt(null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!readAndValidateRequest(intent)) {
            finishWithMessage("无效的应用转移请求");
            return;
        }
        if (TransferApps.isInstalledForCurrentUser(this, packageName)) {
            finishInstallation();
        } else {
            renderInstallPrompt(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (openedUnknownSourcesSettings && packageName != null && !isFinishing()) {
            openedUnknownSourcesSettings = false;
            if (TransferApps.isInstalledForCurrentUser(this, packageName)) {
                finishInstallation();
            } else {
                renderInstallPrompt(null);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_INSTALL) {
            return;
        }

        if (TransferApps.isInstalledForCurrentUser(this, packageName)) {
            finishInstallation();
        } else {
            renderInstallPrompt("安装未完成。原空间中的应用和数据没有被删除。你可以重试或取消。");
        }
    }

    private boolean readAndValidateRequest(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        boolean profileOwner = PrivacyAdminReceiver.isProfileOwner(this);
        if (AppContract.ACTION_INSTALL_IN_MANAGED.equals(action)) {
            destinationIsManaged = true;
            if (!profileOwner) {
                return false;
            }
        } else if (AppContract.ACTION_INSTALL_IN_PARENT.equals(action)) {
            destinationIsManaged = false;
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
        String suppliedLabel = intent.getStringExtra(AppContract.EXTRA_APP_LABEL);
        appLabel = suppliedLabel == null || suppliedLabel.trim().isEmpty()
                ? packageName
                : suppliedLabel.trim();
        if (appLabel.length() > 100) {
            appLabel = appLabel.substring(0, 100);
        }
        return true;
    }

    private void renderInstallPrompt(String errorMessage) {
        LinearLayout root = baseLayout();
        addTitle(root, destinationIsManaged ? "放入隐私空间" : "放回主空间");

        TextView app = textView(appLabel + "\n" + packageName, 17, Color.rgb(30, 34, 42));
        app.setGravity(Gravity.CENTER);
        addWithTopMargin(root, app, 20);

        TextView explanation = textView(
                destinationIsManaged
                        ? "系统将把这款应用安装到隐私空间。安装成功后程序会立即将它隐藏。"
                        : "系统将把这款应用安装回主空间。登录状态和应用数据不会随安装迁移。",
                14,
                Color.rgb(70, 75, 86));
        explanation.setGravity(Gravity.CENTER);
        addWithTopMargin(root, explanation, 18);

        if (errorMessage != null) {
            TextView error = textView(errorMessage, 14, Color.rgb(150, 55, 45));
            error.setGravity(Gravity.CENTER);
            addWithTopMargin(root, error, 16);
        }

        PackageManager packageManager = getPackageManager();
        if (!packageManager.canRequestPackageInstalls()) {
            TextView permissionNote = textView(
                    "Android 需要先允许“隐私空间”安装未知应用。此权限只用于复制你主动选择的应用。",
                    14,
                    Color.rgb(130, 70, 45));
            permissionNote.setGravity(Gravity.CENTER);
            addWithTopMargin(root, permissionNote, 20);

            Button permissionButton = button("允许此来源安装");
            permissionButton.setOnClickListener(view -> openUnknownSourcesSettings());
            addWithTopMargin(root, permissionButton, 12);
        } else {
            Button installButton = button("继续，打开系统安装确认");
            installButton.setOnClickListener(view -> launchSystemInstaller());
            addWithTopMargin(root, installButton, 24);
        }

        Button cancelButton = button("取消");
        cancelButton.setOnClickListener(view -> finish());
        addWithTopMargin(root, cancelButton, 10);
        setContentView(wrap(root));
    }

    private void openUnknownSourcesSettings() {
        Intent settings = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        try {
            openedUnknownSourcesSettings = true;
            startActivity(settings);
        } catch (ActivityNotFoundException | SecurityException error) {
            openedUnknownSourcesSettings = false;
            Toast.makeText(
                    this,
                    "当前系统没有开放“允许此来源”设置",
                    Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void launchSystemInstaller() {
        Intent install = new Intent(
                Intent.ACTION_INSTALL_PACKAGE,
                Uri.fromParts("package", packageName, null))
                .putExtra(Intent.EXTRA_ALLOW_REPLACE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        if (install.resolveActivity(getPackageManager()) == null) {
            renderInstallPrompt("这台手机的系统安装器不支持无 ADB 复制应用。");
            return;
        }

        try {
            startActivityForResult(install, REQUEST_INSTALL);
        } catch (ActivityNotFoundException | SecurityException error) {
            renderInstallPrompt("系统拒绝启动安装流程。请检查“允许此来源”设置，或使用 ADB 备用方式。");
        }
    }

    private void finishInstallation() {
        boolean fullyReady = true;
        String resultMessage;

        if (destinationIsManaged) {
            if (!TransferApps.isSafeUserApplication(this, packageName)) {
                renderFailure("应用已经安装，但它被系统识别为关键或不支持隐藏的应用，因此没有删除原副本。");
                return;
            }

            DevicePolicyManager policyManager = getSystemService(DevicePolicyManager.class);
            ComponentName admin = PrivacyAdminReceiver.component(this);
            try {
                boolean changed = policyManager != null
                        && policyManager.setApplicationHidden(admin, packageName, true);
                boolean hidden = policyManager != null
                        && policyManager.isApplicationHidden(admin, packageName);
                fullyReady = changed || hidden;
            } catch (SecurityException | IllegalArgumentException error) {
                fullyReady = false;
            }

            if (fullyReady) {
                Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
                hiddenPackages.add(packageName);
                AppSettings.setHiddenPackages(this, hiddenPackages);
                AppSettings.setLabel(this, packageName, appLabel);
                resultMessage = "已安装到隐私空间并隐藏。";
            } else {
                resultMessage = "应用已安装到隐私空间，但系统拒绝隐藏它。原空间副本不会被删除。";
            }
        } else {
            if (!TransferApps.isEnabledForCurrentUser(this, packageName)) {
                renderFailure(
                        "主空间中已有这个应用，但它目前处于停用状态。请先在系统应用设置中启用；隐私空间副本不会被删除。");
                return;
            }
            resultMessage = "已安装到主空间。应用数据和登录状态仍分别保存在两个空间中。";
        }

        renderSuccess(resultMessage, fullyReady);
    }

    private void renderSuccess(String message, boolean allowSourceRemoval) {
        LinearLayout root = baseLayout();
        addTitle(root, "操作完成");

        TextView result = textView(message, 16, Color.rgb(25, 120, 72));
        result.setGravity(Gravity.CENTER);
        addWithTopMargin(root, result, 24);

        if (allowSourceRemoval) {
            Button removeSource = button(
                    destinationIsManaged
                            ? "删除主空间原副本（可选）"
                            : "删除隐私空间原副本（可选）");
            removeSource.setOnClickListener(view -> requestSourceRemoval());
            addWithTopMargin(root, removeSource, 26);

            TextView warning = textView(
                    "删除原副本会永久删除该空间内的应用数据。只有确认不再需要原数据时才继续。",
                    13,
                    Color.rgb(150, 55, 45));
            warning.setGravity(Gravity.CENTER);
            addWithTopMargin(root, warning, 8);
        }

        Button done = button("完成");
        done.setOnClickListener(view -> finish());
        addWithTopMargin(root, done, 12);
        setContentView(wrap(root));
    }

    private void renderFailure(String message) {
        LinearLayout root = baseLayout();
        addTitle(root, "未能完成操作");
        TextView result = textView(message, 15, Color.rgb(150, 55, 45));
        result.setGravity(Gravity.CENTER);
        addWithTopMargin(root, result, 22);
        Button done = button("关闭");
        done.setOnClickListener(view -> finish());
        addWithTopMargin(root, done, 22);
        setContentView(wrap(root));
    }

    private void requestSourceRemoval() {
        if (!isDestinationStillReady()) {
            Toast.makeText(
                    this,
                    destinationIsManaged
                            ? "隐私空间副本当前未处于隐藏状态，因此不会删除主空间原副本"
                            : "主空间副本当前不可用，因此不会删除隐私空间原副本",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String action = destinationIsManaged
                ? AppContract.ACTION_DELETE_IN_PARENT
                : AppContract.ACTION_DELETE_IN_MANAGED;
        Intent remove = new Intent(action)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(AppContract.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AppContract.EXTRA_APP_LABEL, appLabel);
        if (!CrossProfileNavigator.start(this, remove)) {
            Toast.makeText(
                    this,
                    "无法打开原空间，请确认隐私空间处于开启状态",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isDestinationStillReady() {
        if (!destinationIsManaged) {
            return TransferApps.isEnabledForCurrentUser(this, packageName);
        }
        DevicePolicyManager policyManager = getSystemService(DevicePolicyManager.class);
        try {
            return policyManager != null
                    && policyManager.isApplicationHidden(
                            PrivacyAdminReceiver.component(this),
                            packageName);
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
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
