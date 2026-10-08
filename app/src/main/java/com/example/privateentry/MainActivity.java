package com.example.privateentry;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.CrossProfileApps;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Personal-profile setup, bridge, and dial-code configuration screen. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PROVISION_PROFILE = 1001;
    private static final long PROVISIONING_MONITOR_INTERVAL_MILLIS = 1_000L;

    private DevicePolicyManager policyManager;
    private ComponentName launcherComponent;
    private EditText secretCodeInput;
    private TextView launcherStatusView;
    private Button launcherVisibilityButton;
    private BroadcastReceiver profileLifecycleReceiver;
    private Handler provisioningMonitorHandler;
    private boolean provisioningMonitorRunning;
    private int profileBootstrapAttempts;
    private long lastProfileBootstrapAttemptAt;
    private boolean managerAutoOpenInProgress;
    private boolean activityResumed;
    private final Runnable provisioningMonitorTick = this::runProvisioningMonitor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        policyManager = getSystemService(DevicePolicyManager.class);

        if (PrivacyAdminReceiver.isProfileOwner(this)) {
            startActivity(new Intent(this, ManagerActivity.class));
            finish();
            return;
        }

        ApkTempFiles.cleanupStale(this);
        launcherComponent = new ComponentName(this, LauncherActivity.class);
        provisioningMonitorHandler = new Handler(Looper.getMainLooper());
        configurePersonalComponents();
        registerProfileLifecycleReceiver();
        if (!maybeOpenRequestedDestination(getIntent())) {
            renderContent();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeOpenRequestedDestination(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (!isFinishing() && launcherComponent != null) {
            managerAutoOpenInProgress = false;
            startProvisioningMonitor();
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        stopProvisioningMonitor();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopProvisioningMonitor();
        if (profileLifecycleReceiver != null) {
            try {
                unregisterReceiver(profileLifecycleReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver may already have been released with the activity context.
            }
            profileLifecycleReceiver = null;
        }
        super.onDestroy();
    }

    private void registerProfileLifecycleReceiver() {
        if (profileLifecycleReceiver != null) {
            return;
        }
        profileLifecycleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                int state = AppSettings.getProfileProvisioningState(MainActivity.this);
                int userId = getProfileUserId(intent);
                if (Intent.ACTION_MANAGED_PROFILE_ADDED.equals(intent.getAction())) {
                    if (userId != AppSettings.USER_ID_UNKNOWN
                            && AppSettings.isProvisioningInProgress(state)
                            && AppSettings.isExpectedProfileUser(
                                    MainActivity.this,
                                    userId)) {
                        AppSettings.markProfileContainerCreated(
                                MainActivity.this,
                                userId);
                    }
                } else if (Intent.ACTION_MANAGED_PROFILE_REMOVED.equals(intent.getAction())) {
                    if (state != AppSettings.PROVISIONING_NONE
                            && AppSettings.isConfirmedExpectedProfileUser(
                                    MainActivity.this,
                                    userId)) {
                        AppSettings.markProfileRollbackConfirmed(MainActivity.this);
                    }
                }
                if (!isFinishing()) {
                    reconcileProvisioningState(activityResumed);
                    if (activityResumed) {
                        renderContent();
                        scheduleProvisioningMonitor();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(profileLifecycleReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(profileLifecycleReceiver, filter);
            }
        } catch (RuntimeException error) {
            profileLifecycleReceiver = null;
        }
    }

    private void renderContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(44), dp(24), dp(28));
        root.setBackgroundColor(Color.rgb(248, 249, 252));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = textView("隐私空间", 27, Color.rgb(25, 28, 35));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWidthWrapHeight());

        TextView description = textView(
                "点击一次并完成 Android 系统确认；程序会持续检测创建结果，成功后自动进入管理页。",
                15,
                Color.rgb(70, 75, 86));
        description.setGravity(Gravity.CENTER);
        addWithTopMargin(root, description, 18);

        ProvisioningSnapshot provisioning = inspectProvisioningStatus();
        boolean profileAvailable = provisioning.profileAvailable;

        TextView state = textView(
                provisioning.title,
                16,
                provisioning.color);
        state.setGravity(Gravity.CENTER);
        addWithTopMargin(root, state, 28);

        TextView stateDetail = textView(
                provisioning.detail,
                13,
                Color.rgb(82, 87, 98));
        stateDetail.setGravity(Gravity.CENTER);
        addWithTopMargin(root, stateDetail, 8);

        Button refreshStatusButton = button("重新检测创建结果");
        refreshStatusButton.setOnClickListener(view -> {
            reconcileProvisioningState(false);
            renderContent();
        });
        addWithTopMargin(root, refreshStatusButton, 10);

        if (profileAvailable) {
            Button openButton = button("打开隐私空间");
            openButton.setOnClickListener(view -> openPrivateHome());
            addWithTopMargin(root, openButton, 10);
        } else if (provisioning.blockedActionLabel != null) {
            Button creatingButton = button(provisioning.blockedActionLabel);
            creatingButton.setEnabled(false);
            addWithTopMargin(root, creatingButton, 10);
        } else {
            Button createButton = button("创建并自动连接");
            createButton.setEnabled(provisioning.provisioningAllowed);
            createButton.setOnClickListener(view -> requestStartProfileProvisioning());
            addWithTopMargin(root, createButton, 10);

            if (provisioning.featureSupported && !provisioning.provisioningAllowed) {
                TextView unavailable = textView(
                        "系统当前不允许再次创建：可能仍有资料正在清理、存在残留，或厂商关闭了创建能力。",
                        13,
                        Color.rgb(130, 70, 45));
                unavailable.setGravity(Gravity.CENTER);
                addWithTopMargin(root, unavailable, 8);
            }
        }

        if (provisioning.showCleanupSettings) {
            Button cleanupButton = button("打开系统设置检查或清理残留");
            cleanupButton.setOnClickListener(view -> confirmOpenProfileSettings());
            addWithTopMargin(root, cleanupButton, 8);
        }

        addSectionTitle(root, "应用放入与放出", 34);

        Button transferButton = button("选择主空间应用，放入隐私空间");
        transferButton.setEnabled(profileAvailable);
        transferButton.setOnClickListener(view -> showPersonalApplicationPicker());
        addWithTopMargin(root, transferButton, 10);

        Button apkButton = button("选择 APK 文件，安装到隐私空间");
        apkButton.setEnabled(profileAvailable);
        apkButton.setOnClickListener(view -> openPersonalApkImporter());
        addWithTopMargin(root, apkButton, 8);

        TextView transferNote = textView(
                "既可复制主空间已安装的应用，也可选择一个完整的单 APK。Android 会显示安装确认；APK 临时副本会自动清理。应用数据和登录状态不会迁移。",
                13,
                Color.rgb(105, 82, 45));
        transferNote.setGravity(Gravity.CENTER);
        addWithTopMargin(root, transferNote, 10);

        addSectionTitle(root, "拨号暗码", 34);

        TextView currentCode = textView(
                AppSettings.formatDialCode(AppSettings.getSecretCode(this)),
                21,
                Color.rgb(25, 79, 170));
        currentCode.setGravity(Gravity.CENTER);
        currentCode.setTextIsSelectable(true);
        addWithTopMargin(root, currentCode, 10);

        secretCodeInput = new EditText(this);
        secretCodeInput.setSingleLine(true);
        secretCodeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        secretCodeInput.setHint("输入至少 1 位数字");
        secretCodeInput.setText(AppSettings.getSecretCode(this));
        secretCodeInput.setSelectAllOnFocus(true);
        addWithTopMargin(root, secretCodeInput, 10);

        Button saveCodeButton = button("保存暗码");
        saveCodeButton.setOnClickListener(view -> saveSecretCode());
        addWithTopMargin(root, saveCodeButton, 8);

        TextView codeFormatNote = textView(
                "Android 规定完整格式为 *#*#数字#*#*；只输入 #数字# 会被拨号器当作运营商指令。",
                13,
                Color.rgb(105, 82, 45));
        codeFormatNote.setGravity(Gravity.CENTER);
        addWithTopMargin(root, codeFormatNote, 8);

        addSectionTitle(root, "主空间入口", 34);

        launcherStatusView = textView("", 15, Color.rgb(70, 75, 86));
        launcherStatusView.setGravity(Gravity.CENTER);
        root.addView(launcherStatusView, matchWidthWrapHeight());

        launcherVisibilityButton = button("");
        launcherVisibilityButton.setOnClickListener(view -> toggleLauncherEntry());
        addWithTopMargin(root, launcherVisibilityButton, 10);
        refreshLauncherState();

        Button closeButton = button("关闭");
        closeButton.setOnClickListener(view -> finishAndRemoveTask());
        addWithTopMargin(root, closeButton, 10);

        TextView note = textView(
                "主空间若保留同名副本，它仍会显示。部分定制系统可能不支持无 ADB 复制应用，拨号器也可能限制暗码功能。",
                13,
                Color.rgb(105, 109, 119));
        note.setGravity(Gravity.CENTER);
        addWithTopMargin(root, note, 24);

        setContentView(scrollView);
    }

    private void startProfileProvisioning() {
        if (policyManager == null
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)
                || !isManagedProfileProvisioningAllowed()) {
            Toast.makeText(this, "当前系统不允许创建工作资料", Toast.LENGTH_LONG).show();
            return;
        }

        Intent provisioning = new Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
                .putExtra(
                        DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                        PrivacyAdminReceiver.component(this))
                .putExtra(
                        DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE,
                        true);

        if (provisioning.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "系统缺少工作资料配置组件", Toast.LENGTH_LONG).show();
            return;
        }

        profileBootstrapAttempts = 0;
        lastProfileBootstrapAttemptAt = 0L;
        managerAutoOpenInProgress = false;
        AppSettings.markProfileProvisioningStarted(this);
        PersistableBundle adminExtras = new PersistableBundle();
        adminExtras.putLong(
                AppContract.EXTRA_PROVISIONING_ATTEMPT_ID,
                AppSettings.getProfileProvisioningAttemptId(this));
        provisioning.putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                adminExtras);
        try {
            startActivityForResult(provisioning, REQUEST_PROVISION_PROFILE);
        } catch (RuntimeException error) {
            AppSettings.markProfileProvisioningCancelPending(
                    this,
                    "系统创建页面无法启动；没有创建新的工作资料");
            Toast.makeText(this, "无法启动工作资料创建流程", Toast.LENGTH_LONG).show();
            startProvisioningMonitor();
        }
    }

    private void requestStartProfileProvisioning() {
        int state = AppSettings.getProfileProvisioningState(this);
        if (AppSettings.isProvisioningInProgress(state)) {
            Toast.makeText(
                    this,
                    "创建流程仍在进行，程序会自动检测结果",
                    Toast.LENGTH_LONG).show();
            startProvisioningMonitor();
            return;
        }
        if (state == AppSettings.PROVISIONING_NONE
                || state == AppSettings.PROVISIONING_ROLLED_BACK
                || state == AppSettings.PROVISIONING_CANCELED_CLEAN
                || state == AppSettings.PROVISIONING_PROFILE_ABSENT) {
            startProfileProvisioning();
            return;
        }
        Toast.makeText(
                this,
                "尚未确认旧流程结束，请先检查或清理系统中的资料",
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PROVISION_PROFILE) {
            if (resultCode == RESULT_OK) {
                AppSettings.markProfileProvisioningAccepted(this);
            } else {
                AppSettings.markProfileProvisioningCancelPending(
                        this,
                        "用户取消或系统前置检查未通过；正在确认 Android 是否已清理临时资料");
            }
            Toast.makeText(
                    this,
                    resultCode == RESULT_OK
                            ? "系统已受理，程序会自动连接创建结果"
                            : "创建已取消，正在自动确认回滚",
                    Toast.LENGTH_LONG).show();
            startProvisioningMonitor();
            renderContent();
        }
    }

    private void startProvisioningMonitor() {
        if (provisioningMonitorHandler == null) {
            return;
        }
        provisioningMonitorRunning = true;
        provisioningMonitorHandler.removeCallbacks(provisioningMonitorTick);
        provisioningMonitorHandler.post(provisioningMonitorTick);
    }

    private void stopProvisioningMonitor() {
        provisioningMonitorRunning = false;
        if (provisioningMonitorHandler != null) {
            provisioningMonitorHandler.removeCallbacks(provisioningMonitorTick);
        }
    }

    private void scheduleProvisioningMonitor() {
        if (!provisioningMonitorRunning || provisioningMonitorHandler == null) {
            return;
        }
        provisioningMonitorHandler.removeCallbacks(provisioningMonitorTick);
        if (shouldContinueProvisioningMonitor()) {
            provisioningMonitorHandler.postDelayed(
                    provisioningMonitorTick,
                    PROVISIONING_MONITOR_INTERVAL_MILLIS);
        }
    }

    private void runProvisioningMonitor() {
        if (!provisioningMonitorRunning || isFinishing()) {
            return;
        }
        boolean managerOpened = reconcileProvisioningState(true);
        if (!managerOpened && !isFinishing()) {
            renderContent();
            scheduleProvisioningMonitor();
        }
    }

    private boolean shouldContinueProvisioningMonitor() {
        int state = AppSettings.getProfileProvisioningState(this);
        return AppSettings.isProvisioningInProgress(state)
                || state == AppSettings.PROVISIONING_TIMEOUT_UNKNOWN
                || (state == AppSettings.PROVISIONING_SUCCEEDED
                && !hasManagedProfileTarget())
                || AppSettings.shouldAutoOpenManager(this);
    }

    private boolean reconcileProvisioningState(boolean allowAutoOpen) {
        boolean routeReady = hasManagedProfileTarget();
        int candidateUserId = getManagedProfileCandidateUserId();
        boolean profileVisible = routeReady || candidateUserId != AppSettings.USER_ID_UNKNOWN;
        int state = AppSettings.getProfileProvisioningState(this);

        if (routeReady) {
            AppSettings.clearCleanProvisioningObservations(this);
            AppSettings.rememberProfileUserId(this, candidateUserId);
            if (state != AppSettings.PROVISIONING_SUCCEEDED
                    && state != AppSettings.PROVISIONING_NONE) {
                AppSettings.markProfileConnecting(this);
            }
            if (allowAutoOpen
                    && AppSettings.shouldAutoOpenManager(this)
                    && !managerAutoOpenInProgress) {
                managerAutoOpenInProgress = true;
                if (openManagedProfile(false)) {
                    return true;
                }
                managerAutoOpenInProgress = false;
            }
            return false;
        }

        if (profileVisible) {
            AppSettings.clearCleanProvisioningObservations(this);
            AppSettings.rememberProfileUserId(this, candidateUserId);
            if (state != AppSettings.PROVISIONING_NONE) {
                AppSettings.markProfileContainerCreated(this, candidateUserId);
                AppSettings.markProfileConnecting(this);
            }
            long now = System.currentTimeMillis();
            if (allowAutoOpen
                    && profileBootstrapAttempts < 3
                    && now - lastProfileBootstrapAttemptAt >= 5_000L
                    && AppSettings.hasProvisioningAttempt(this)) {
                profileBootstrapAttempts++;
                lastProfileBootstrapAttemptAt = now;
                tryStartProfileBootstrap();
            }
            return false;
        }

        boolean provisioningAllowed = isManagedProfileProvisioningAllowed();
        int additionalProfiles = countAdditionalProfiles();
        int expectedUserId = AppSettings.getProfileProvisioningTargetUserId(this);
        if (state == AppSettings.PROVISIONING_CANCEL_PENDING
                && expectedUserId == AppSettings.USER_ID_UNKNOWN) {
            if (provisioningAllowed && additionalProfiles == 0) {
                if (AppSettings.noteCleanProvisioningObservation(this)
                        >= AppSettings.CANCELED_CLEAN_STABILITY_MILLIS) {
                    AppSettings.markCanceledWithoutProfile(this);
                }
            } else {
                AppSettings.clearCleanProvisioningObservations(this);
            }
        } else {
            boolean knownProfileCanBeChecked = expectedUserId != AppSettings.USER_ID_UNKNOWN;
            boolean unknownProfileCanBeChecked =
                    (state == AppSettings.PROVISIONING_SUCCEEDED
                            || state == AppSettings.PROVISIONING_TIMEOUT_UNKNOWN)
                            && expectedUserId == AppSettings.USER_ID_UNKNOWN;
            if (provisioningAllowed
                    && (knownProfileCanBeChecked || unknownProfileCanBeChecked)
                    && isExpectedProfileAbsent(expectedUserId, additionalProfiles)) {
                if (AppSettings.noteCleanProvisioningObservation(this)
                        >= AppSettings.PROFILE_ABSENT_STABILITY_MILLIS) {
                    AppSettings.markObservedProfileAbsent(this);
                }
            } else {
                AppSettings.clearCleanProvisioningObservations(this);
            }
        }

        long startedAt = AppSettings.getProfileProvisioningStartedAt(this);
        long elapsed = startedAt <= 0L
                ? 0L
                : Math.max(0L, System.currentTimeMillis() - startedAt);
        state = AppSettings.getProfileProvisioningState(this);
        if (AppSettings.isProvisioningInProgress(state)
                && elapsed > AppSettings.PROVISIONING_STALE_WARNING_MILLIS) {
            AppSettings.markProfileProvisioningTimeoutUnknown(this);
        }
        return false;
    }

    private ProvisioningSnapshot inspectProvisioningStatus() {
        boolean featureSupported = getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_MANAGED_USERS);
        boolean profileAvailable = hasManagedProfileTarget();
        boolean profileVisible = profileAvailable
                || getManagedProfileCandidateUserId() != AppSettings.USER_ID_UNKNOWN;
        boolean provisioningAllowed = featureSupported
                && isManagedProfileProvisioningAllowed();
        int additionalProfiles = countAdditionalProfiles();
        int recordedState = AppSettings.getProfileProvisioningState(this);
        long startedAt = AppSettings.getProfileProvisioningStartedAt(this);
        long elapsed = startedAt <= 0L
                ? 0L
                : Math.max(0L, System.currentTimeMillis() - startedAt);

        if (profileAvailable) {
            boolean connectionVerified = recordedState == AppSettings.PROVISIONING_SUCCEEDED;
            return new ProvisioningSnapshot(
                    true,
                    featureSupported,
                    false,
                    null,
                    false,
                    connectionVerified
                            ? "状态：创建成功"
                            : "状态：隐私空间已创建，正在连接",
                    connectionVerified
                            ? "管理页已实际启动并验证，可以正常使用。"
                            : "已发现管理路由，程序正在自动打开管理页。",
                    connectionVerified
                            ? Color.rgb(25, 120, 72)
                            : Color.rgb(25, 79, 170));
        }

        if (!featureSupported) {
            return new ProvisioningSnapshot(
                    false,
                    false,
                    false,
                    null,
                    false,
                    "状态：无法创建",
                    "当前系统没有开放工作资料功能。",
                    Color.rgb(150, 48, 48));
        }

        if (recordedState == AppSettings.PROVISIONING_SUCCEEDED) {
            String detail = profileVisible
                    ? "隐私空间存在，但管理连接暂时不可用；请确认工作资料已开启。"
                    : "此前的隐私空间当前已不可见；可能已被删除或仍在系统清理。";
            return new ProvisioningSnapshot(
                    false,
                    true,
                    false,
                    "请先检查现有隐私空间",
                    true,
                    "状态：隐私空间暂时无法访问",
                    detail,
                    Color.rgb(150, 92, 24));
        }

        if (recordedState == AppSettings.PROVISIONING_ROLLED_BACK) {
            return new ProvisioningSnapshot(
                    false,
                    true,
                    provisioningAllowed,
                    null,
                    false,
                    "状态：创建未完成，回滚已确认",
                    "已收到目标资料的删除回执；系统允许创建后可以重新尝试。",
                    Color.rgb(150, 48, 48));
        }

        if (recordedState == AppSettings.PROVISIONING_CANCELED_CLEAN) {
            return new ProvisioningSnapshot(
                    false,
                    true,
                    provisioningAllowed,
                    null,
                    false,
                    "状态：创建已取消，可以重新尝试",
                    "连续 30 秒未检测到新资料，且系统持续允许创建。",
                    Color.rgb(150, 92, 24));
        }

        if (recordedState == AppSettings.PROVISIONING_PROFILE_ABSENT) {
            return new ProvisioningSnapshot(
                    false,
                    true,
                    provisioningAllowed,
                    null,
                    false,
                    "状态：此前检测到的隐私空间已不存在",
                    "系统连续确认该资料不在用户列表中；现在可以重新创建。",
                    Color.rgb(150, 92, 24));
        }

        if (recordedState == AppSettings.PROVISIONING_CANCEL_PENDING) {
            boolean profileWasObserved =
                    AppSettings.getProfileProvisioningTargetUserId(this)
                            != AppSettings.USER_ID_UNKNOWN;
            return new ProvisioningSnapshot(
                    false,
                    true,
                    false,
                    "系统正在确认清理，请稍候…",
                    true,
                    "状态：创建已取消，正在确认回滚",
                    AppSettings.getProfileProvisioningDetail(this)
                            + (profileWasObserved
                            ? "。已经观察到资料容器，需等待匹配的删除回执或手动清理。"
                            : "。连续 30 秒确认未出现新资料后，才会允许再次尝试。"),
                    Color.rgb(150, 48, 48));
        }

        if (recordedState == AppSettings.PROVISIONING_STARTED
                || recordedState == AppSettings.PROVISIONING_ACCEPTED
                || recordedState == AppSettings.PROVISIONING_PROFILE_CREATED
                || recordedState == AppSettings.PROVISIONING_PLATFORM_SUCCEEDED
                || recordedState == AppSettings.PROVISIONING_CONNECTING) {
            String phase;
            if (recordedState == AppSettings.PROVISIONING_PLATFORM_SUCCEEDED
                    || recordedState == AppSettings.PROVISIONING_CONNECTING) {
                phase = "阶段 3/3：Android 已完成创建，正在连接管理页";
            } else if (recordedState == AppSettings.PROVISIONING_PROFILE_CREATED) {
                phase = "阶段 2/3：资料容器已创建，正在设置管理权限";
            } else if (recordedState == AppSettings.PROVISIONING_ACCEPTED) {
                phase = "阶段 2/3：系统已受理，正在完成配置";
            } else {
                phase = "阶段 1/3：系统创建流程已启动";
            }
            return new ProvisioningSnapshot(
                    false,
                    true,
                    false,
                    "正在创建，请稍候…",
                    false,
                    "状态：正在创建隐私空间",
                    phase + "；已用时 " + formatElapsed(elapsed)
                            + "。完成后会自动进入管理页。",
                    Color.rgb(25, 120, 72));
        }

        if (recordedState == AppSettings.PROVISIONING_TIMEOUT_UNKNOWN) {
            return new ProvisioningSnapshot(
                    false,
                    true,
                    false,
                    "结果未知，请先检查系统状态",
                    true,
                    "状态：创建超时，结果未知",
                    "超过 15 分钟仍未建立管理连接。程序不会自动重复创建，以免生成第二个残留空间。",
                    Color.rgb(150, 92, 24));
        }

        if (!provisioningAllowed) {
            String detail = additionalProfiles > 0
                    ? "检测到其他已启用资料，但无法确认它是否由本程序创建。"
                    : "系统当前不允许创建；可能是厂商限制或存在不可见残留。";
            return new ProvisioningSnapshot(
                    false,
                    true,
                    false,
                    null,
                    true,
                    "状态：当前无法创建",
                    detail,
                    Color.rgb(150, 92, 24));
        }

        return new ProvisioningSnapshot(
                false,
                true,
                true,
                null,
                false,
                "状态：可以创建隐私空间",
                "点击一次创建并完成 Android 系统确认；之后程序会自动监控并连接管理页。",
                Color.rgb(120, 77, 20));
    }

    private int countAdditionalProfiles() {
        UserManager userManager = getSystemService(UserManager.class);
        if (userManager == null) {
            return -1;
        }
        try {
            return Math.max(0, userManager.getUserProfiles().size() - 1);
        } catch (RuntimeException error) {
            return -1;
        }
    }

    private boolean isExpectedProfileAbsent(int expectedUserId, int additionalProfiles) {
        if (expectedUserId == AppSettings.USER_ID_UNKNOWN) {
            return additionalProfiles == 0;
        }
        UserManager userManager = getSystemService(UserManager.class);
        if (userManager == null) {
            return false;
        }
        try {
            for (UserHandle profile : userManager.getUserProfiles()) {
                if (profile.hashCode() == expectedUserId) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private String formatElapsed(long elapsedMillis) {
        long totalSeconds = elapsedMillis / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + " 分 " + seconds + " 秒";
    }

    private void confirmOpenProfileSettings() {
        new AlertDialog.Builder(this)
                .setTitle("检查系统残留")
                .setMessage(
                        "Android 标准流程会自动删除创建失败的工作资料。若厂商系统留下了本程序无法访问的半成品，"
                                + "普通应用没有权限强制删除，只能在系统账号/工作资料设置中移除，或使用 ADB 清理。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开系统设置", (dialog, which) -> openProfileSettings())
                .show();
    }

    private void openProfileSettings() {
        Intent settings = new Intent(Settings.ACTION_SYNC_SETTINGS);
        if (settings.resolveActivity(getPackageManager()) == null) {
            settings = new Intent(Settings.ACTION_SETTINGS);
        }
        try {
            startActivity(settings);
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法打开系统设置", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasManagedProfileTarget() {
        CrossProfileApps crossProfileApps = getSystemService(CrossProfileApps.class);
        if (crossProfileApps == null) {
            return false;
        }
        try {
            if (crossProfileApps.getTargetUserProfiles().isEmpty()) {
                return false;
            }
            Intent managerIntent = new Intent(AppContract.ACTION_OPEN_MANAGER)
                    .addCategory(Intent.CATEGORY_DEFAULT);
            return CrossProfileNavigator.canStart(this, managerIntent);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private int getManagedProfileCandidateUserId() {
        CrossProfileApps crossProfileApps = getSystemService(CrossProfileApps.class);
        if (crossProfileApps == null) {
            return AppSettings.USER_ID_UNKNOWN;
        }
        try {
            List<UserHandle> targets = crossProfileApps.getTargetUserProfiles();
            return targets.isEmpty()
                    ? AppSettings.USER_ID_UNKNOWN
                    : targets.get(0).hashCode();
        } catch (RuntimeException error) {
            return AppSettings.USER_ID_UNKNOWN;
        }
    }

    /**
     * Recovers a profile that Android created and enabled but whose DPC callback never ran.
     * CrossProfileApps can launch only this app's launcher component in the target profile;
     * that profile-side entry then performs the normal idempotent owner configuration.
     */
    private boolean tryStartProfileBootstrap() {
        CrossProfileApps crossProfileApps = getSystemService(CrossProfileApps.class);
        if (crossProfileApps == null) {
            return false;
        }

        try {
            List<UserHandle> targets = crossProfileApps.getTargetUserProfiles();
            if (targets.isEmpty()) {
                return false;
            }

            Intent managerIntent = new Intent(AppContract.ACTION_OPEN_MANAGER)
                    .addCategory(Intent.CATEGORY_DEFAULT);
            if (CrossProfileNavigator.canStart(this, managerIntent)) {
                return false;
            }

            crossProfileApps.startMainActivity(launcherComponent, targets.get(0));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private int getProfileUserId(Intent intent) {
        UserHandle user;
        if (Build.VERSION.SDK_INT >= 33) {
            user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle.class);
        } else {
            user = intent.getParcelableExtra(Intent.EXTRA_USER);
        }
        if (user != null) {
            return user.hashCode();
        }
        return intent.getIntExtra(
                "android.intent.extra.user_handle",
                AppSettings.USER_ID_UNKNOWN);
    }

    private boolean isManagedProfileProvisioningAllowed() {
        if (policyManager == null) {
            return false;
        }
        try {
            return policyManager.isProvisioningAllowed(
                    DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean maybeOpenRequestedDestination(Intent intent) {
        if (intent == null) {
            return false;
        }
        if (intent.getBooleanExtra(AppContract.EXTRA_OPEN_HOME, false)) {
            intent.removeExtra(AppContract.EXTRA_OPEN_HOME);
            if (openPrivateHome()) {
                finish();
                return true;
            }
        } else if (intent.getBooleanExtra(AppContract.EXTRA_OPEN_MANAGER, false)) {
            intent.removeExtra(AppContract.EXTRA_OPEN_MANAGER);
            if (openManagedProfile()) {
                finish();
                return true;
            }
        }
        return false;
    }

    private boolean openPrivateHome() {
        Intent openHome = new Intent(AppContract.ACTION_OPEN_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (CrossProfileNavigator.start(this, openHome)) {
            return true;
        }

        Intent repairAndOpen = new Intent(AppContract.ACTION_OPEN_MANAGER)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(AppContract.EXTRA_OPEN_HOME, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (CrossProfileNavigator.start(this, repairAndOpen)) {
            return true;
        }

        Toast.makeText(
                this,
                "无法打开隐私空间，请确认工作资料已开启",
                Toast.LENGTH_LONG).show();
        return false;
    }

    private boolean openManagedProfile() {
        return openManagedProfile(true);
    }

    private boolean openManagedProfile(boolean showError) {
        long attemptId = AppSettings.getProfileProvisioningAttemptId(this);
        long connectionNonce = AppSettings.needsManagerConnectionVerification(this)
                ? AppSettings.getOrCreateManagerConnectionNonce(this)
                : 0L;
        Intent openManager = new Intent(AppContract.ACTION_OPEN_MANAGER)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (attemptId > 0L) {
            openManager.putExtra(AppContract.EXTRA_PROVISIONING_ATTEMPT_ID, attemptId);
        }
        if (connectionNonce != 0L) {
            openManager.putExtra(
                    AppContract.EXTRA_MANAGER_CONNECTION_NONCE,
                    connectionNonce);
        }
        if (!CrossProfileNavigator.start(this, openManager)) {
            if (showError) {
                Toast.makeText(
                        this,
                        "无法打开隐私空间，请确认工作资料已开启",
                        Toast.LENGTH_LONG).show();
            }
            return false;
        }
        return true;
    }

    private void configurePersonalComponents() {
        setComponentState(PrivateHomeActivity.class, false);
        setComponentState(ManagerActivity.class, false);
        setComponentState(ManagedInstallActivity.class, false);
        setComponentState(ManagedApkInstallActivity.class, false);
        setComponentState(ManagedRemoveActivity.class, false);
        setComponentState(ProvisioningStatusReceiver.class, true);
        setComponentState(ProvisioningReadyActivity.class, true);
        setComponentState(ParentInstallActivity.class, true);
        setComponentState(ParentRemoveActivity.class, true);
        setComponentState(PersonalApkImportActivity.class, true);
        setComponentState(ParentApkCleanupActivity.class, true);
    }

    private void setComponentState(Class<?> componentClass, boolean enabled) {
        getPackageManager().setComponentEnabledSetting(
                new ComponentName(this, componentClass),
                enabled
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void showPersonalApplicationPicker() {
        if (!hasManagedProfileTarget()) {
            Toast.makeText(
                    this,
                    "隐私空间未开启，请先创建或恢复工作资料",
                    Toast.LENGTH_LONG).show();
            return;
        }

        List<TransferApps.Entry> applications =
                TransferApps.listTransferableLauncherApps(this);
        if (applications.isEmpty()) {
            Toast.makeText(this, "没有可放入的普通应用", Toast.LENGTH_LONG).show();
            return;
        }

        String[] labels = new String[applications.size()];
        for (int index = 0; index < applications.size(); index++) {
            TransferApps.Entry entry = applications.get(index);
            labels[index] = entry.label + "\n" + entry.packageName;
        }

        new AlertDialog.Builder(this)
                .setTitle("选择要放入隐私空间的应用")
                .setItems(labels, (dialog, which) ->
                        startInstallInManaged(applications.get(which)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void openPersonalApkImporter() {
        if (!hasManagedProfileTarget()) {
            Toast.makeText(
                    this,
                    "隐私空间未开启，请先创建或恢复工作资料",
                    Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, PersonalApkImportActivity.class));
    }

    private void startInstallInManaged(TransferApps.Entry application) {
        Intent transfer = new Intent(AppContract.ACTION_INSTALL_IN_MANAGED)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .putExtra(AppContract.EXTRA_PACKAGE_NAME, application.packageName)
                .putExtra(AppContract.EXTRA_APP_LABEL, application.label);
        if (!CrossProfileNavigator.start(this, transfer)) {
            Toast.makeText(
                    this,
                    "无法打开隐私空间。若刚升级，请先进入隐私空间管理页一次完成初始化",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveSecretCode() {
        String value = secretCodeInput.getText().toString().trim();
        if (!AppSettings.isValidSecretCode(value)) {
            Toast.makeText(this, "暗码必须至少包含 1 位数字", Toast.LENGTH_SHORT).show();
            return;
        }

        AppSettings.setSecretCode(this, value);
        setLauncherEntryEnabled(true);
        renderContent();
        Toast.makeText(
                this,
                "暗码已保存。桌面入口已恢复，请先测试暗码再隐藏。",
                Toast.LENGTH_LONG).show();
    }

    private void toggleLauncherEntry() {
        setLauncherEntryEnabled(!isLauncherEntryEnabled());
        refreshLauncherState();
    }

    private void setLauncherEntryEnabled(boolean enabled) {
        getPackageManager().setComponentEnabledSetting(
                launcherComponent,
                enabled
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void refreshLauncherState() {
        boolean enabled = isLauncherEntryEnabled();
        launcherStatusView.setText(enabled ? "桌面入口：显示" : "桌面入口：已隐藏");
        launcherVisibilityButton.setText(enabled ? "隐藏桌面入口" : "恢复桌面入口");
    }

    private boolean isLauncherEntryEnabled() {
        int state = getPackageManager().getComponentEnabledSetting(launcherComponent);
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void addSectionTitle(LinearLayout root, String text, int topMarginDp) {
        TextView view = textView(text, 19, Color.rgb(25, 28, 35));
        addWithTopMargin(root, view, topMarginDp);
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

    private static final class ProvisioningSnapshot {
        final boolean profileAvailable;
        final boolean featureSupported;
        final boolean provisioningAllowed;
        final String blockedActionLabel;
        final boolean showCleanupSettings;
        final String title;
        final String detail;
        final int color;

        ProvisioningSnapshot(
                boolean profileAvailable,
                boolean featureSupported,
                boolean provisioningAllowed,
                String blockedActionLabel,
                boolean showCleanupSettings,
                String title,
                String detail,
                int color) {
            this.profileAvailable = profileAvailable;
            this.featureSupported = featureSupported;
            this.provisioningAllowed = provisioningAllowed;
            this.blockedActionLabel = blockedActionLabel;
            this.showCleanupSettings = showCleanupSettings;
            this.title = title;
            this.detail = detail;
            this.color = color;
        }
    }
}
