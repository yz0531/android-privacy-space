package com.example.privateentry;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Manages applications installed inside the work profile owned by this app. */
public final class ManagerActivity extends Activity {
    private DevicePolicyManager policyManager;
    private ComponentName adminComponent;
    private boolean contentCreated;
    private boolean readyAcknowledgementSent;
    private boolean profilePolicyReady;
    private long launchProvisioningAttemptId;
    private long launchConnectionNonce;
    private boolean managerConnectionRequestValid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        policyManager = getSystemService(DevicePolicyManager.class);
        adminComponent = PrivacyAdminReceiver.component(this);
        if (!PrivacyAdminReceiver.isProfileOwner(this)) {
            Toast.makeText(this, "此页面只能在隐私空间中打开", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        rememberLaunchAttempt(getIntent());

        // Refresh component states and cross-profile routes after application updates.
        profilePolicyReady = PrivacyAdminReceiver.configureManagedProfile(this);
        if (!profilePolicyReady) {
            Toast.makeText(
                    this,
                    "隐私空间策略刷新失败，部分功能可能暂时不可用",
                    Toast.LENGTH_LONG).show();
        }

        renderContent();
        contentCreated = true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        rememberLaunchAttempt(intent);
        readyAcknowledgementSent = false;
        if (profilePolicyReady) {
            notifyParentManagerReady();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!contentCreated || !PrivacyAdminReceiver.isProfileOwner(this)) {
            return;
        }

        if (!profilePolicyReady) {
            profilePolicyReady = PrivacyAdminReceiver.configureManagedProfile(this);
        }

        String pendingPackage = AppSettings.getPendingRehide(this);
        if (pendingPackage != null) {
            boolean hidden = setPackageHidden(pendingPackage, true);
            AppSettings.clearPendingRehide(this);
            if (!hidden && isTrackedPackagePresent(pendingPackage)) {
                Toast.makeText(
                        this,
                        "未能重新隐藏 " + AppSettings.getLabel(this, pendingPackage)
                                + "，请使用“立即重新隐藏全部”重试",
                        Toast.LENGTH_LONG).show();
            }
        }
        removeMissingTrackedPackages();
        ensureTrackedPackagesHidden();
        renderContent();
        if (profilePolicyReady) {
            notifyParentManagerReady();
        }
    }

    private void rememberLaunchAttempt(Intent intent) {
        long incomingAttemptId = intent == null
                ? 0L
                : intent.getLongExtra(AppContract.EXTRA_PROVISIONING_ATTEMPT_ID, 0L);
        long incomingNonce = intent == null
                ? 0L
                : intent.getLongExtra(AppContract.EXTRA_MANAGER_CONNECTION_NONCE, 0L);
        long storedAttemptId = AppSettings.getManagedProvisioningAttemptId(this);
        managerConnectionRequestValid = false;
        launchProvisioningAttemptId = 0L;
        launchConnectionNonce = 0L;
        if (incomingAttemptId <= 0L
                || incomingNonce == 0L
                || (storedAttemptId > 0L && storedAttemptId != incomingAttemptId)) {
            return;
        }

        // The managed profile echoes only the attempt persisted during provisioning.
        // A parent-supplied value must never become trusted managed-profile state.
        launchProvisioningAttemptId = storedAttemptId;
        launchConnectionNonce = incomingNonce;
        managerConnectionRequestValid = true;
    }

    private void notifyParentManagerReady() {
        if (readyAcknowledgementSent
                || !managerConnectionRequestValid) {
            return;
        }
        readyAcknowledgementSent = true;
        Intent ready = new Intent(AppContract.ACTION_PROFILE_READY)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(AppContract.EXTRA_MANAGER_READY_ACK, true)
                .putExtra(
                        AppContract.EXTRA_PROVISIONING_ATTEMPT_ID,
                        launchProvisioningAttemptId)
                .putExtra(
                        AppContract.EXTRA_MANAGER_CONNECTION_NONCE,
                        launchConnectionNonce)
                .putExtra(AppContract.EXTRA_MANAGER_PROFILE_USER_ID, currentUserId());
        if (!CrossProfileNavigator.start(this, ready)) {
            readyAcknowledgementSent = false;
        }
    }

    private void renderContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(36), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(248, 249, 252));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = textView("隐私空间", 27, Color.rgb(25, 28, 35));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWidthWrapHeight());

        TextView profileInfo = textView(
                "工作资料用户 ID：" + currentUserId(),
                14,
                Color.rgb(82, 87, 98));
        profileInfo.setGravity(Gravity.CENTER);
        addWithTopMargin(root, profileInfo, 8);

        TextView explanation = textView(
                "锁定后，所选应用会在这个工作资料中完全不可见且不可用。点击打开时会临时恢复；返回此页面后会再次隐藏。",
                14,
                Color.rgb(70, 75, 86));
        explanation.setGravity(Gravity.CENTER);
        addWithTopMargin(root, explanation, 18);

        Button chooseButton = button("选择要隐藏的应用");
        chooseButton.setOnClickListener(view -> showApplicationPicker());
        addWithTopMargin(root, chooseButton, 24);

        Button moveOutPickerButton = button("选择隐私空间应用，放回主空间");
        moveOutPickerButton.setOnClickListener(view -> showMoveOutPicker());
        addWithTopMargin(root, moveOutPickerButton, 8);

        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        TextView hiddenTitle = textView(
                "已隐藏应用（" + hiddenPackages.size() + "）",
                19,
                Color.rgb(25, 28, 35));
        addWithTopMargin(root, hiddenTitle, 30);

        if (hiddenPackages.isEmpty()) {
            TextView empty = textView(
                    "还没有隐藏应用。可在主空间选择已有应用直接放入，或安装工作资料应用后从上方选择。",
                    14,
                    Color.rgb(105, 109, 119));
            addWithTopMargin(root, empty, 12);
        } else {
            List<String> sortedPackages = new ArrayList<>(hiddenPackages);
            sortedPackages.sort(Comparator.comparing(
                    packageName -> AppSettings.getLabel(this, packageName),
                    Collator.getInstance(Locale.getDefault())));
            for (String packageName : sortedPackages) {
                addHiddenApplicationRow(root, packageName);
            }
        }

        Button rehideButton = button("立即重新隐藏全部");
        rehideButton.setEnabled(!hiddenPackages.isEmpty());
        rehideButton.setOnClickListener(view -> rehideAll());
        addWithTopMargin(root, rehideButton, 28);

        Button restoreButton = button("全部恢复显示");
        restoreButton.setEnabled(!hiddenPackages.isEmpty());
        restoreButton.setOnClickListener(view -> confirmRestoreAll());
        addWithTopMargin(root, restoreButton, 8);

        TextView adbTitle = textView("ADB 备用安装方式", 19, Color.rgb(25, 28, 35));
        addWithTopMargin(root, adbTitle, 32);

        TextView adbHelp = textView(
                "电脑执行：\n"
                        + "adb shell cmd package install-existing --user "
                        + currentUserId()
                        + " <应用包名>",
                13,
                Color.rgb(62, 68, 80));
        adbHelp.setTextIsSelectable(true);
        addWithTopMargin(root, adbHelp, 10);

        TextView warning = textView(
                "注意：这里只隐藏隐私空间内的副本。主空间保留的同名应用不会受影响。应用临时打开期间可能出现在工作资料的应用列表中；按 Home 或直接切走不会自动重锁，请返回此页面完成重锁。",
                13,
                Color.rgb(130, 70, 45));
        addWithTopMargin(root, warning, 22);

        Button deleteSpaceButton = button("永久删除整个隐私空间");
        deleteSpaceButton.setTextColor(Color.rgb(175, 35, 35));
        deleteSpaceButton.setOnClickListener(view -> confirmDeleteProfileFirstStep());
        addWithTopMargin(root, deleteSpaceButton, 22);

        Button closeButton = button("关闭");
        closeButton.setOnClickListener(view -> finishAndRemoveTask());
        addWithTopMargin(root, closeButton, 10);

        setContentView(scrollView);
    }

    private void addHiddenApplicationRow(LinearLayout root, String packageName) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.WHITE);

        TextView label = textView(
                AppSettings.getLabel(this, packageName),
                17,
                Color.rgb(30, 34, 42));
        card.addView(label, matchWidthWrapHeight());

        TextView packageView = textView(packageName, 12, Color.rgb(105, 109, 119));
        packageView.setTextIsSelectable(true);
        addWithTopMargin(card, packageView, 3);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button openButton = button("打开");
        openButton.setOnClickListener(view -> openHiddenApplication(packageName));
        actions.addView(openButton, weightedButtonParams());

        Button restoreButton = button("恢复显示");
        restoreButton.setOnClickListener(view -> restorePackage(packageName));
        LinearLayout.LayoutParams restoreParams = weightedButtonParams();
        restoreParams.leftMargin = dp(8);
        actions.addView(restoreButton, restoreParams);
        addWithTopMargin(card, actions, 8);

        Button moveOutButton = button("放回主空间");
        moveOutButton.setOnClickListener(view -> startInstallInParent(packageName));
        addWithTopMargin(card, moveOutButton, 6);

        addWithTopMargin(root, card, 10);
    }

    private void showApplicationPicker() {
        List<AppEntry> applications = findVisibleUserApplications();
        if (applications.isEmpty()) {
            Toast.makeText(
                    this,
                    "隐私空间中没有可选择的非系统应用",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String[] labels = new String[applications.size()];
        boolean[] selected = new boolean[applications.size()];
        for (int index = 0; index < applications.size(); index++) {
            AppEntry entry = applications.get(index);
            labels[index] = entry.label + "\n" + entry.packageName;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择要隐藏的应用")
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) ->
                        selected[which] = checked)
                .setNegativeButton("取消", null)
                .setPositiveButton("隐藏", (dialog, which) ->
                        hideSelectedApplications(applications, selected))
                .show();
    }

    private void showMoveOutPicker() {
        Map<String, TransferApps.Entry> byPackage = new LinkedHashMap<>();
        for (TransferApps.Entry entry : TransferApps.listTransferableLauncherApps(this)) {
            byPackage.put(entry.packageName, entry);
        }
        for (String packageName : AppSettings.getHiddenPackages(this)) {
            byPackage.putIfAbsent(
                    packageName,
                    new TransferApps.Entry(
                            packageName,
                            AppSettings.getLabel(this, packageName)));
        }

        List<TransferApps.Entry> applications = new ArrayList<>(byPackage.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        applications.sort((left, right) -> {
            int labelComparison = collator.compare(left.label, right.label);
            return labelComparison != 0
                    ? labelComparison
                    : left.packageName.compareTo(right.packageName);
        });
        if (applications.isEmpty()) {
            Toast.makeText(this, "没有可放回主空间的普通应用", Toast.LENGTH_LONG).show();
            return;
        }

        String[] labels = new String[applications.size()];
        for (int index = 0; index < applications.size(); index++) {
            TransferApps.Entry entry = applications.get(index);
            labels[index] = entry.label + "\n" + entry.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要放回主空间的应用")
                .setItems(labels, (dialog, which) ->
                        startInstallInParent(applications.get(which).packageName))
                .setNegativeButton("取消", null)
                .show();
    }

    private List<AppEntry> findVisibleUserApplications() {
        PackageManager packageManager = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packageManager.queryIntentActivities(launcherIntent, 0);
        Map<String, AppEntry> byPackage = new LinkedHashMap<>();
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);

        for (ResolveInfo resolveInfo : resolved) {
            if (resolveInfo.activityInfo == null
                    || resolveInfo.activityInfo.applicationInfo == null) {
                continue;
            }
            ApplicationInfo applicationInfo = resolveInfo.activityInfo.applicationInfo;
            String packageName = applicationInfo.packageName;
            if (hiddenPackages.contains(packageName)
                    || !TransferApps.isSafeUserApplication(this, packageName)) {
                continue;
            }

            CharSequence loadedLabel = applicationInfo.loadLabel(packageManager);
            String label = loadedLabel == null
                    ? packageName
                    : loadedLabel.toString().trim();
            if (label.isEmpty()) {
                label = packageName;
            }
            byPackage.putIfAbsent(packageName, new AppEntry(packageName, label));
        }

        List<AppEntry> applications = new ArrayList<>(byPackage.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        applications.sort((left, right) -> {
            int labelComparison = collator.compare(left.label, right.label);
            return labelComparison != 0
                    ? labelComparison
                    : left.packageName.compareTo(right.packageName);
        });
        return applications;
    }

    private void hideSelectedApplications(List<AppEntry> applications, boolean[] selected) {
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        int hiddenCount = 0;
        int failureCount = 0;

        for (int index = 0; index < applications.size(); index++) {
            if (!selected[index]) {
                continue;
            }
            AppEntry application = applications.get(index);
            if (setPackageHidden(application.packageName, true)) {
                hiddenPackages.add(application.packageName);
                AppSettings.setLabel(
                        this,
                        application.packageName,
                        application.label);
                hiddenCount++;
            } else {
                failureCount++;
            }
        }

        AppSettings.setHiddenPackages(this, hiddenPackages);
        String message = hiddenCount == 0
                ? "没有隐藏任何应用"
                : "已隐藏 " + hiddenCount + " 个应用";
        if (failureCount > 0) {
            message += "，失败 " + failureCount + " 个";
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        renderContent();
    }

    private void openHiddenApplication(String packageName) {
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        if (!hiddenPackages.contains(packageName) || getPackageName().equals(packageName)) {
            Toast.makeText(this, "该应用不在隐藏列表中", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!setPackageHidden(packageName, false)) {
            Toast.makeText(this, "无法临时恢复该应用", Toast.LENGTH_LONG).show();
            return;
        }

        Intent launchIntent = buildLaunchIntent(packageName);
        if (launchIntent == null) {
            setPackageHidden(packageName, true);
            Toast.makeText(this, "该应用没有可打开的桌面入口", Toast.LENGTH_LONG).show();
            return;
        }

        AppSettings.setPendingRehide(this, packageName);
        try {
            startActivity(launchIntent);
        } catch (RuntimeException error) {
            AppSettings.clearPendingRehide(this);
            setPackageHidden(packageName, true);
            Toast.makeText(this, "无法启动该应用", Toast.LENGTH_LONG).show();
        }
    }

    private void startInstallInParent(String packageName) {
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        boolean trackedHidden = hiddenPackages.contains(packageName);
        if (getPackageName().equals(packageName)
                || (!trackedHidden
                        && !TransferApps.isSafeUserApplication(this, packageName))) {
            Toast.makeText(this, "该应用不支持放回主空间", Toast.LENGTH_SHORT).show();
            return;
        }

        String label = trackedHidden
                ? AppSettings.getLabel(this, packageName)
                : TransferApps.loadLabel(this, packageName);

        Intent transfer = new Intent(AppContract.ACTION_INSTALL_IN_PARENT)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(AppContract.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(AppContract.EXTRA_APP_LABEL, label);
        if (!CrossProfileNavigator.start(this, transfer)) {
            Toast.makeText(
                    this,
                    "无法打开主空间安装页面",
                    Toast.LENGTH_LONG).show();
        }
    }

    private Intent buildLaunchIntent(String packageName) {
        PackageManager packageManager = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        List<ResolveInfo> matches = packageManager.queryIntentActivities(query, 0);
        if (matches.isEmpty() || matches.get(0).activityInfo == null) {
            return null;
        }

        ResolveInfo match = matches.get(0);
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(
                        match.activityInfo.packageName,
                        match.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private void restorePackage(String packageName) {
        if (!setPackageHidden(packageName, false)) {
            Toast.makeText(this, "恢复失败，请稍后重试", Toast.LENGTH_LONG).show();
            return;
        }

        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        hiddenPackages.remove(packageName);
        AppSettings.setHiddenPackages(this, hiddenPackages);
        AppSettings.removeLabel(this, packageName);
        if (packageName.equals(AppSettings.getPendingRehide(this))) {
            AppSettings.clearPendingRehide(this);
        }
        renderContent();
    }

    private void rehideAll() {
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        int successCount = 0;
        int failureCount = 0;
        for (String packageName : hiddenPackages) {
            if (setPackageHidden(packageName, true)) {
                successCount++;
            } else {
                failureCount++;
            }
        }
        AppSettings.clearPendingRehide(this);
        Toast.makeText(
                this,
                failureCount == 0
                        ? "已重新隐藏 " + successCount + " 个应用"
                        : "已隐藏 " + successCount + " 个，失败 " + failureCount + " 个",
                Toast.LENGTH_LONG).show();
        renderContent();
    }

    private void confirmRestoreAll() {
        new AlertDialog.Builder(this)
                .setTitle("全部恢复显示？")
                .setMessage("这会让列表中的应用重新出现在隐私空间的应用列表中，应用数据不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (dialog, which) -> restoreAll())
                .show();
    }

    private void confirmDeleteProfileFirstStep() {
        new AlertDialog.Builder(this)
                .setTitle("删除整个隐私空间？")
                .setMessage(
                        "隐私空间中的所有应用、账号、登录状态和文件都会永久删除。主空间不会被清除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (dialog, which) ->
                        confirmDeleteProfileFinalStep())
                .show();
    }

    private void confirmDeleteProfileFinalStep() {
        new AlertDialog.Builder(this)
                .setTitle("最后确认")
                .setMessage(
                        "此操作不可撤销。删除后主空间中的本程序仍会保留；若桌面入口已隐藏，"
                                + "仍需使用拨号暗码或 ADB 恢复入口。确定永久删除吗？")
                .setNegativeButton("保留隐私空间", null)
                .setPositiveButton("永久删除", (dialog, which) -> deleteManagedProfile())
                .show();
    }

    private void deleteManagedProfile() {
        if (!PrivacyAdminReceiver.isProfileOwner(this)
                || !isCurrentUserManagedProfile()) {
            Toast.makeText(this, "安全检查失败，未执行删除", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            policyManager.wipeData(0, "用户请求删除隐私空间工作资料");
        } catch (SecurityException | IllegalStateException error) {
            Toast.makeText(
                    this,
                    "系统拒绝删除，请在系统设置中移除工作资料",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isCurrentUserManagedProfile() {
        if (policyManager == null) {
            return false;
        }
        try {
            return policyManager.isManagedProfile(adminComponent);
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
    }

    private void restoreAll() {
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        Set<String> failedPackages = new java.util.HashSet<>();
        for (String packageName : hiddenPackages) {
            if (setPackageHidden(packageName, false)) {
                AppSettings.removeLabel(this, packageName);
            } else {
                failedPackages.add(packageName);
            }
        }

        AppSettings.setHiddenPackages(this, failedPackages);
        AppSettings.clearPendingRehide(this);
        Toast.makeText(
                this,
                failedPackages.isEmpty()
                        ? "已全部恢复显示"
                        : "有 " + failedPackages.size() + " 个应用恢复失败",
                Toast.LENGTH_LONG).show();
        renderContent();
    }

    private boolean setPackageHidden(String packageName, boolean hidden) {
        if (policyManager == null
                || !PrivacyAdminReceiver.isProfileOwner(this)
                || packageName == null
                || packageName.isEmpty()
                || getPackageName().equals(packageName)) {
            return false;
        }
        try {
            boolean updated = policyManager.setApplicationHidden(
                    adminComponent,
                    packageName,
                    hidden);
            return updated
                    || policyManager.isApplicationHidden(adminComponent, packageName) == hidden;
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
    }

    private boolean isTrackedPackagePresent(String packageName) {
        if (TransferApps.isInstalledForCurrentUser(this, packageName)) {
            return true;
        }
        return isPolicyHidden(packageName);
    }

    private boolean isPolicyHidden(String packageName) {
        try {
            return policyManager != null
                    && policyManager.isApplicationHidden(adminComponent, packageName);
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
    }

    private void ensureTrackedPackagesHidden() {
        for (String packageName : AppSettings.getHiddenPackages(this)) {
            if (!isPolicyHidden(packageName)) {
                setPackageHidden(packageName, true);
            }
        }
    }

    private void removeMissingTrackedPackages() {
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        Set<String> retainedPackages = new java.util.HashSet<>();
        for (String packageName : hiddenPackages) {
            if (isTrackedPackagePresent(packageName)) {
                retainedPackages.add(packageName);
            } else {
                AppSettings.removeLabel(this, packageName);
            }
        }
        if (retainedPackages.size() != hiddenPackages.size()) {
            AppSettings.setHiddenPackages(this, retainedPackages);
        }
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

    private void addWithTopMargin(LinearLayout root, View view, int topMarginDp) {
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.topMargin = dp(topMarginDp);
        root.addView(view, params);
    }

    private LinearLayout.LayoutParams matchWidthWrapHeight() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int currentUserId() {
        // Android allocates each user a stable block of 100,000 application UIDs.
        return Process.myUid() / 100000;
    }

    private static final class AppEntry {
        final String packageName;
        final String label;

        AppEntry(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }
}
