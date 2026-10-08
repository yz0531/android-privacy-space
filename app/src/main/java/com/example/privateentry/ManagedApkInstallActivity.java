package com.example.privateentry;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Receives one APK from the personal profile, installs it, then hides it. */
public final class ManagedApkInstallActivity extends Activity {
    private static final int REQUEST_UNKNOWN_SOURCES = 2401;
    private static final int REQUEST_INSTALL_APK = 2402;
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final long VERSION_NOT_INSTALLED = Long.MIN_VALUE;

    private String token;
    private Uri sourceUri;
    private ApkTempFiles.ArchiveInfo archive;
    private long previousInstalledVersion = VERSION_NOT_INSTALLED;
    private boolean copyStarted;
    private boolean waitingForUnknownSources;
    private boolean installerStarted;
    private volatile boolean canceled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!PrivacyAdminReceiver.isProfileOwner(this)
                || !readAndValidateRequest(getIntent())) {
            Toast.makeText(this, "无效的 APK 安装请求", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        ApkTempFiles.cleanupStale(this);
        if (savedInstanceState != null) {
            copyStarted = savedInstanceState.getBoolean("copy_started", false);
            waitingForUnknownSources = savedInstanceState.getBoolean(
                    "waiting_unknown_sources",
                    false);
            installerStarted = savedInstanceState.getBoolean("installer_started", false);
            previousInstalledVersion = savedInstanceState.getLong(
                    "previous_version",
                    VERSION_NOT_INSTALLED);
        }

        File localFile = ApkTempFiles.fileForToken(this, token);
        if (localFile.isFile()) {
            prepareLocalApk(localFile);
        } else if (installerStarted) {
            renderFailure("安装临时文件已被系统或清理流程移除，请回到主空间重新选择 APK。");
        } else {
            // A saved true value can outlive its worker after Android kills the process.
            copyStarted = false;
            copyFromPersonalProfile();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("copy_started", copyStarted);
        outState.putBoolean("waiting_unknown_sources", waitingForUnknownSources);
        outState.putBoolean("installer_started", installerStarted);
        outState.putLong("previous_version", previousInstalledVersion);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForUnknownSources && archive != null && !installerStarted) {
            if (getPackageManager().canRequestPackageInstalls()) {
                waitingForUnknownSources = false;
                launchSystemInstaller();
            } else {
                renderPermissionPrompt("尚未允许此来源安装。可以继续授权，或取消并清理临时文件。");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_UNKNOWN_SOURCES) {
            return;
        }
        if (requestCode != REQUEST_INSTALL_APK || archive == null) {
            return;
        }

        installerStarted = false;
        revokeLocalInstallerGrant();
        ApkTempFiles.delete(this, token);
        requestPersonalSourceCleanup();

        long installedVersion = getInstalledVersion(archive.packageName);
        boolean installedNow = installedVersion != VERSION_NOT_INSTALLED;
        boolean explicitSuccess = resultCode == RESULT_OK && installedNow;
        boolean newlyObservedInstall = previousInstalledVersion == VERSION_NOT_INSTALLED
                && installedNow
                && installedVersion == archive.versionCode;
        if (!explicitSuccess && !newlyObservedInstall) {
            renderInstallNotCompleted();
            return;
        }

        finishSuccessfulInstall();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        canceled = true;
        cancelAndCleanup();
    }

    private boolean readAndValidateRequest(Intent intent) {
        if (intent == null
                || !AppContract.ACTION_INSTALL_APK_IN_MANAGED.equals(intent.getAction())) {
            return false;
        }
        token = intent.getStringExtra(AppContract.EXTRA_APK_TOKEN);
        if (!ApkTempFiles.isValidToken(token)) {
            return false;
        }
        ClipData clipData = intent.getClipData();
        if (clipData == null || clipData.getItemCount() != 1) {
            return false;
        }
        sourceUri = clipData.getItemAt(0).getUri();
        if (sourceUri == null
                || !"content".equals(sourceUri.getScheme())
                || !isExpectedProviderAuthority(sourceUri.getAuthority())) {
            return false;
        }
        List<String> path = sourceUri.getPathSegments();
        return path.size() == 1 && token.equals(path.get(0));
    }

    private boolean isExpectedProviderAuthority(String authority) {
        if (authority == null) {
            return false;
        }
        String expected = getPackageName() + ".apkfiles";
        return expected.equals(authority) || authority.endsWith("@" + expected);
    }

    private void copyFromPersonalProfile() {
        if (copyStarted) {
            renderProgress("正在等待 APK 复制完成…");
            return;
        }
        copyStarted = true;
        renderProgress("正在将 APK 复制到隐私空间…");
        Thread worker = new Thread(() -> {
            try {
                File localFile = ApkTempFiles.copyFromUri(this, sourceUri, token);
                if (canceled) {
                    ApkTempFiles.delete(this, token);
                    runOnUiThread(this::requestPersonalSourceCleanup);
                    return;
                }
                runOnUiThread(() -> {
                    if (canceled || isFinishing() || isDestroyed()) {
                        ApkTempFiles.delete(this, token);
                        requestPersonalSourceCleanup();
                        return;
                    }
                    prepareLocalApk(localFile);
                });
            } catch (IOException | RuntimeException error) {
                ApkTempFiles.delete(this, token);
                runOnUiThread(() -> {
                    requestPersonalSourceCleanup();
                    if (!isFinishing() && !isDestroyed()) {
                        renderFailure("隐私空间未能完整接收 APK；两边的临时文件已进入清理流程，请重新选择文件。");
                    }
                });
            }
        }, "PrivateEntry-apk-managed-copy");
        worker.start();
    }

    private void prepareLocalApk(File localFile) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        // Once this complete managed copy exists, the personal staging copy is disposable.
        requestPersonalSourceCleanup();
        try {
            archive = ApkTempFiles.inspectArchive(this, localFile);
        } catch (IOException | RuntimeException error) {
            ApkTempFiles.delete(this, token);
            requestPersonalSourceCleanup();
            renderFailure("所选文件不是受支持的完整单 APK，临时文件已清理。APKS、XAPK 和拆分 APK 暂不支持。");
            return;
        }

        if (previousInstalledVersion == VERSION_NOT_INSTALLED) {
            previousInstalledVersion = getInstalledVersion(archive.packageName);
        }
        if (installerStarted) {
            renderProgress("系统安装确认仍在进行，请完成或取消该页面。");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            renderPermissionPrompt(null);
            return;
        }
        launchSystemInstaller();
    }

    private void openUnknownSourcesSettings() {
        Intent settings = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        try {
            waitingForUnknownSources = true;
            startActivityForResult(settings, REQUEST_UNKNOWN_SOURCES);
        } catch (ActivityNotFoundException | SecurityException error) {
            waitingForUnknownSources = false;
            renderPermissionPrompt("当前系统没有开放“允许此来源”设置，无法继续安装。");
        }
    }

    @SuppressWarnings("deprecation")
    private void launchSystemInstaller() {
        if (archive == null || installerStarted) {
            return;
        }
        File localFile = ApkTempFiles.fileForToken(this, token);
        if (!localFile.isFile()) {
            renderFailure("APK 临时文件已不存在，请回到主空间重新选择。");
            return;
        }

        Uri installUri = ApkTempFiles.uriForToken(this, token);
        Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(installUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_ALLOW_REPLACE, true)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true);
        install.setClipData(ClipData.newRawUri("APK", installUri));
        if (install.resolveActivity(getPackageManager()) == null) {
            cleanupAndRenderFailure("这台手机没有支持 content URI 的系统 APK 安装器。");
            return;
        }

        try {
            installerStarted = true;
            waitingForUnknownSources = false;
            startActivityForResult(install, REQUEST_INSTALL_APK);
            renderProgress("等待 Android 系统安装确认…");
        } catch (ActivityNotFoundException | SecurityException error) {
            installerStarted = false;
            cleanupAndRenderFailure("系统拒绝启动 APK 安装页面，临时文件已清理。");
        }
    }

    private void finishSuccessfulInstall() {
        if (!TransferApps.isSafeUserApplication(this, archive.packageName)) {
            renderResult(
                    "APK 已安装，但系统将其识别为关键、系统或不支持隐藏的应用，因此没有自动隐藏。",
                    false);
            return;
        }

        DevicePolicyManager policyManager = getSystemService(DevicePolicyManager.class);
        ComponentName admin = PrivacyAdminReceiver.component(this);
        boolean hidden = false;
        try {
            if (policyManager != null) {
                boolean changed = policyManager.setApplicationHidden(
                        admin,
                        archive.packageName,
                        true);
                hidden = changed || policyManager.isApplicationHidden(
                        admin,
                        archive.packageName);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            hidden = false;
        }

        if (hidden) {
            Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
            hiddenPackages.add(archive.packageName);
            AppSettings.setHiddenPackages(this, hiddenPackages);
            AppSettings.setLabel(this, archive.packageName, archive.label);
            renderResult("已从 APK 安装到隐私空间并自动隐藏。临时 APK 副本已删除。", true);
        } else {
            renderResult(
                    "APK 已安装，临时副本也已删除，但系统拒绝隐藏该应用。可在管理页中重新选择隐藏。",
                    false);
        }
    }

    private void renderInstallNotCompleted() {
        renderResult("安装已取消或失败；没有新增应用，临时 APK 副本已删除。", false);
    }

    private long getInstalledVersion(String packageName) {
        try {
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= 33) {
                info = getPackageManager().getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(
                                PackageManager.MATCH_DISABLED_COMPONENTS));
            } else {
                info = getPackageManager().getPackageInfo(
                        packageName,
                        PackageManager.MATCH_DISABLED_COMPONENTS);
            }
            ApplicationInfo applicationInfo = info.applicationInfo;
            if (applicationInfo == null
                    || (applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                return VERSION_NOT_INSTALLED;
            }
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            return VERSION_NOT_INSTALLED;
        }
    }

    private void requestPersonalSourceCleanup() {
        if (!ApkTempFiles.isValidToken(token)) {
            return;
        }
        Intent cleanup = new Intent(AppContract.ACTION_CLEANUP_APK_IN_PARENT)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_HISTORY)
                .putExtra(AppContract.EXTRA_APK_TOKEN, token);
        CrossProfileNavigator.start(this, cleanup);
    }

    private void cancelAndCleanup() {
        revokeLocalInstallerGrant();
        ApkTempFiles.delete(this, token);
        requestPersonalSourceCleanup();
        finish();
    }

    private void cleanupAndRenderFailure(String message) {
        revokeLocalInstallerGrant();
        ApkTempFiles.delete(this, token);
        requestPersonalSourceCleanup();
        renderFailure(message);
    }

    private void revokeLocalInstallerGrant() {
        if (!ApkTempFiles.isValidToken(token)) {
            return;
        }
        try {
            revokeUriPermission(
                    ApkTempFiles.uriForToken(this, token),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) {
            // The system installer may already have released its activity-scoped grant.
        }
    }

    private void renderPermissionPrompt(String statusMessage) {
        LinearLayout root = baseLayout();
        addTitle(root, "允许安装 APK");
        TextView app = textView(
                archive == null ? "所选 APK" : archive.label + "\n" + archive.packageName,
                17,
                Color.rgb(30, 34, 42));
        app.setGravity(Gravity.CENTER);
        addWithTopMargin(root, app, 20);
        TextView explanation = textView(
                "Android 需要在隐私空间中允许“隐私空间”安装未知应用。"
                        + "授权只用于你刚刚选择的 APK，安装仍会经过系统确认。",
                14,
                Color.rgb(130, 70, 45));
        explanation.setGravity(Gravity.CENTER);
        addWithTopMargin(root, explanation, 18);
        if (statusMessage != null) {
            TextView status = textView(statusMessage, 14, Color.rgb(150, 55, 45));
            status.setGravity(Gravity.CENTER);
            addWithTopMargin(root, status, 14);
        }
        Button allow = button("打开“允许此来源”设置");
        allow.setOnClickListener(view -> openUnknownSourcesSettings());
        addWithTopMargin(root, allow, 24);
        Button cancel = button("取消并清理临时文件");
        cancel.setOnClickListener(view -> cancelAndCleanup());
        addWithTopMargin(root, cancel, 10);
        setContentView(wrap(root));
    }

    private void renderProgress(String message) {
        LinearLayout root = baseLayout();
        addTitle(root, "安装 APK");
        TextView status = textView(message, 15, Color.rgb(25, 79, 170));
        status.setGravity(Gravity.CENTER);
        addWithTopMargin(root, status, 24);
        TextView note = textView(
                "安装成功、取消或失败后，隐私空间中的临时 APK 会立即删除。",
                13,
                Color.rgb(82, 87, 98));
        note.setGravity(Gravity.CENTER);
        addWithTopMargin(root, note, 10);
        setContentView(wrap(root));
    }

    private void renderFailure(String message) {
        renderResult(message, false);
    }

    private void renderResult(String message, boolean success) {
        LinearLayout root = baseLayout();
        addTitle(root, success ? "安装完成" : "未能完成");
        TextView result = textView(
                message,
                15,
                success ? Color.rgb(25, 120, 72) : Color.rgb(150, 55, 45));
        result.setGravity(Gravity.CENTER);
        addWithTopMargin(root, result, 24);
        Button manager = button("返回隐私空间管理页");
        manager.setOnClickListener(view -> {
            startActivity(new Intent(this, ManagerActivity.class));
            finish();
        });
        addWithTopMargin(root, manager, 24);
        Button close = button("关闭");
        close.setOnClickListener(view -> finish());
        addWithTopMargin(root, close, 10);
        setContentView(wrap(root));
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
