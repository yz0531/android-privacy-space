package com.example.privateentry;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Desktop-like entry for the apps protected inside the managed profile. */
public final class PrivateHomeActivity extends Activity {
    private static final int COLOR_BACKGROUND_TOP = Color.rgb(14, 21, 38);
    private static final int COLOR_BACKGROUND_BOTTOM = Color.rgb(27, 41, 66);
    private static final int COLOR_PRIMARY_TEXT = Color.rgb(247, 249, 252);
    private static final int COLOR_SECONDARY_TEXT = Color.rgb(174, 184, 204);

    private DevicePolicyManager policyManager;
    private ComponentName adminComponent;
    private boolean rehideBlocked;

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

        PrivacyAdminReceiver.configureManagedProfile(this);
        configureSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing() || !PrivacyAdminReceiver.isProfileOwner(this)) {
            return;
        }
        restorePendingHiddenState();
        removeMissingTrackedPackages();
        ensureTrackedPackagesHidden();
        rehideBlocked = AppSettings.getPendingRehide(this) != null;
        renderContent();
    }

    private void configureSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BACKGROUND_TOP);
        window.setNavigationBarColor(COLOR_BACKGROUND_BOTTOM);
        window.getDecorView().setSystemUiVisibility(0);
    }

    private void renderContent() {
        List<HomeApp> applications = loadApplications();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{COLOR_BACKGROUND_TOP, COLOR_BACKGROUND_BOTTOM}));

        root.addView(buildHeader(applications.size()), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout content = new FrameLayout(this);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
        root.addView(content, contentParams);

        if (applications.isEmpty()) {
            content.addView(buildEmptyState(), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            GridView grid = new GridView(this);
            grid.setNumColumns(getResources().getConfiguration().smallestScreenWidthDp >= 600
                    ? 6
                    : 4);
            grid.setHorizontalSpacing(dp(6));
            grid.setVerticalSpacing(dp(16));
            grid.setPadding(dp(12), dp(12), dp(12), dp(28));
            grid.setClipToPadding(false);
            grid.setVerticalScrollBarEnabled(false);
            grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
            grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
            grid.setAdapter(new AppGridAdapter(applications));
            content.addView(grid, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets systemBars = insets.getInsets(
                        WindowInsets.Type.systemBars());
                topInset = systemBars.top;
                bottomInset = systemBars.bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, topInset, 0, bottomInset);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    private View buildHeader(int applicationCount) {
        FrameLayout header = new FrameLayout(this);
        header.setPadding(dp(24), dp(24), dp(16), dp(18));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView title = textView("隐私空间", 27, COLOR_PRIMARY_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titles.addView(title, wrapContent());

        TextView subtitle = textView(
                applicationCount == 0
                        ? "应用入口已锁定"
                        : "已保护 " + applicationCount + " 个应用 · 点击图标打开",
                13,
                COLOR_SECONDARY_TEXT);
        LinearLayout.LayoutParams subtitleParams = wrapContent();
        subtitleParams.topMargin = dp(5);
        titles.addView(subtitle, subtitleParams);

        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        titleParams.rightMargin = dp(64);
        header.addView(titles, titleParams);

        ImageButton settings = new ImageButton(this);
        settings.setImageResource(R.drawable.ic_settings_white_24);
        settings.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        settings.setScaleType(ImageView.ScaleType.CENTER);
        settings.setPadding(dp(12), dp(12), dp(12), dp(12));
        settings.setBackground(circleRipple(
                Color.argb(34, 255, 255, 255),
                Color.argb(70, 255, 255, 255)));
        settings.setContentDescription("进入隐私空间设置");
        settings.setOnClickListener(view -> openSettings());
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(
                dp(48),
                dp(48),
                Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(settings, settingsParams);
        return header;
    }

    private View buildEmptyState() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(32), dp(24), dp(32), dp(48));

        ImageView lock = new ImageView(this);
        lock.setImageResource(R.drawable.ic_lock_white_48);
        lock.setImageTintList(ColorStateList.valueOf(Color.argb(150, 255, 255, 255)));
        empty.addView(lock, new LinearLayout.LayoutParams(dp(54), dp(54)));

        TextView title = textView("空间里还没有应用", 20, COLOR_PRIMARY_TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = wrapContent();
        titleParams.topMargin = dp(18);
        empty.addView(title, titleParams);

        TextView detail = textView(
                "请从主空间添加应用，或点击右上角进入设置。",
                14,
                COLOR_SECONDARY_TEXT);
        detail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = wrapContent();
        detailParams.topMargin = dp(9);
        empty.addView(detail, detailParams);
        return empty;
    }

    private List<HomeApp> loadApplications() {
        List<HomeApp> applications = new ArrayList<>();
        for (String packageName : AppSettings.getHiddenPackages(this)) {
            if (!isTrackedPackagePresent(packageName)) {
                continue;
            }
            applications.add(new HomeApp(
                    packageName,
                    AppSettings.getLabel(this, packageName),
                    TransferApps.loadIcon(this, packageName)));
        }

        Collator collator = Collator.getInstance(Locale.getDefault());
        applications.sort((left, right) -> {
            int labelResult = collator.compare(left.label, right.label);
            return labelResult != 0
                    ? labelResult
                    : left.packageName.compareTo(right.packageName);
        });
        return applications;
    }

    private void openApplication(HomeApp application) {
        if (rehideBlocked) {
            Toast.makeText(
                    this,
                    "上一个应用尚未重新隐藏，请进入设置后重试",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!AppSettings.getHiddenPackages(this).contains(application.packageName)) {
            Toast.makeText(this, "该应用已不在隐私空间列表中", Toast.LENGTH_SHORT).show();
            renderContent();
            return;
        }

        if (!setPackageHidden(application.packageName, false)) {
            Toast.makeText(this, "暂时无法打开该应用", Toast.LENGTH_LONG).show();
            return;
        }

        Intent launchIntent = buildLaunchIntent(application.packageName);
        if (launchIntent == null) {
            setPackageHidden(application.packageName, true);
            Toast.makeText(this, "该应用没有可打开的桌面入口", Toast.LENGTH_LONG).show();
            return;
        }

        AppSettings.setPendingRehide(this, application.packageName);
        try {
            startActivity(launchIntent);
        } catch (RuntimeException error) {
            AppSettings.clearPendingRehide(this);
            setPackageHidden(application.packageName, true);
            Toast.makeText(this, "无法启动该应用", Toast.LENGTH_LONG).show();
        }
    }

    private Intent buildLaunchIntent(String packageName) {
        Intent query = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName);
        List<android.content.pm.ResolveInfo> matches =
                getPackageManager().queryIntentActivities(query, 0);
        if (matches.isEmpty() || matches.get(0).activityInfo == null) {
            return null;
        }

        android.content.pm.ResolveInfo match = matches.get(0);
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(
                        match.activityInfo.packageName,
                        match.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private void openSettings() {
        startActivity(new Intent(this, ManagerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void restorePendingHiddenState() {
        String pendingPackage = AppSettings.getPendingRehide(this);
        if (pendingPackage == null) {
            return;
        }
        if (!isTrackedPackagePresent(pendingPackage)) {
            removeTrackedPackage(pendingPackage);
            AppSettings.clearPendingRehide(this);
            return;
        }
        if (setPackageHidden(pendingPackage, true)) {
            AppSettings.clearPendingRehide(this);
        } else {
            Toast.makeText(
                    this,
                    "未能重新隐藏 " + AppSettings.getLabel(this, pendingPackage)
                            + "，请进入设置重试",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void ensureTrackedPackagesHidden() {
        String pendingPackage = AppSettings.getPendingRehide(this);
        for (String packageName : AppSettings.getHiddenPackages(this)) {
            if (isPolicyHidden(packageName)) {
                if (packageName.equals(pendingPackage)) {
                    AppSettings.clearPendingRehide(this);
                    pendingPackage = null;
                }
                continue;
            }
            if (setPackageHidden(packageName, true) && packageName.equals(pendingPackage)) {
                AppSettings.clearPendingRehide(this);
                pendingPackage = null;
            }
        }
    }

    private void removeMissingTrackedPackages() {
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        Set<String> retainedPackages = new HashSet<>();
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

    private void removeTrackedPackage(String packageName) {
        Set<String> hiddenPackages = AppSettings.getHiddenPackages(this);
        hiddenPackages.remove(packageName);
        AppSettings.setHiddenPackages(this, hiddenPackages);
        AppSettings.removeLabel(this, packageName);
    }

    private boolean isTrackedPackagePresent(String packageName) {
        return TransferApps.isInstalledForCurrentUser(this, packageName)
                || isPolicyHidden(packageName);
    }

    private boolean isPolicyHidden(String packageName) {
        try {
            return policyManager != null
                    && policyManager.isApplicationHidden(adminComponent, packageName);
        } catch (SecurityException | IllegalArgumentException error) {
            return false;
        }
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

    private Drawable roundedRipple(int fillColor, int rippleColor, float radiusDp) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(fillColor);
        content.setCornerRadius(dp(radiusDp));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    private Drawable circleRipple(int fillColor, int rippleColor) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.OVAL);
        content.setColor(fillColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    private TextView textView(String text, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AppGridAdapter extends BaseAdapter {
        private final List<HomeApp> applications;

        AppGridAdapter(List<HomeApp> applications) {
            this.applications = applications;
        }

        @Override
        public int getCount() {
            return applications.size();
        }

        @Override
        public HomeApp getItem(int position) {
            return applications.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            HomeApp application = getItem(position);

            LinearLayout tile = new LinearLayout(PrivateHomeActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            tile.setPadding(dp(4), dp(10), dp(4), dp(8));
            tile.setMinimumHeight(dp(116));
            tile.setClickable(true);
            tile.setFocusable(true);
            tile.setBackground(roundedRipple(
                    Color.TRANSPARENT,
                    Color.argb(45, 255, 255, 255),
                    18));
            tile.setContentDescription("打开 " + application.label);
            tile.setOnClickListener(view -> openApplication(application));

            FrameLayout iconSurface = new FrameLayout(PrivateHomeActivity.this);
            iconSurface.setBackground(roundedRipple(
                    Color.argb(25, 255, 255, 255),
                    Color.argb(30, 255, 255, 255),
                    19));
            LinearLayout.LayoutParams surfaceParams = new LinearLayout.LayoutParams(
                    dp(68),
                    dp(68));
            tile.addView(iconSurface, surfaceParams);

            ImageView icon = new ImageView(PrivateHomeActivity.this);
            icon.setImageDrawable(application.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    dp(54),
                    dp(54),
                    Gravity.CENTER);
            iconSurface.addView(icon, iconParams);

            TextView label = textView(application.label, 13, COLOR_PRIMARY_TEXT);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(8);
            tile.addView(label, labelParams);
            return tile;
        }
    }

    private static final class HomeApp {
        final String packageName;
        final String label;
        final Drawable icon;

        HomeApp(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }
}
