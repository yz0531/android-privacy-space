package com.example.privateentry;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
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

import java.io.File;
import java.io.IOException;

/** Selects and privately stages one APK in the personal profile. */
public final class PersonalApkImportActivity extends Activity {
    private static final int REQUEST_PICK_APK = 2301;
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    private boolean pickerOpened;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (PrivacyAdminReceiver.isProfileOwner(this)) {
            finish();
            return;
        }
        ApkTempFiles.cleanupStale(this);
        pickerOpened = savedInstanceState != null
                && savedInstanceState.getBoolean("picker_opened", false);
        renderReady(null);
        if (savedInstanceState == null) {
            openPicker();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("picker_opened", pickerOpened);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_APK) {
            return;
        }
        pickerOpened = false;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            renderReady("没有选择 APK 文件。");
            return;
        }

        Uri source = data.getData();
        if (!"content".equals(source.getScheme())) {
            renderReady("文件选择器没有提供安全的 content URI，请换一个文件管理器重试。");
            return;
        }
        stageAndForward(source);
    }

    private void openPicker() {
        if (pickerOpened) {
            return;
        }
        Intent probe = new Intent(AppContract.ACTION_INSTALL_APK_IN_MANAGED)
                .addCategory(Intent.CATEGORY_DEFAULT);
        if (!CrossProfileNavigator.canStart(this, probe)) {
            renderReady("无法连接隐私空间。请确认空间已创建、已开启，并先打开一次管理页。");
            return;
        }

        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(APK_MIME_TYPE);
        try {
            pickerOpened = true;
            startActivityForResult(picker, REQUEST_PICK_APK);
        } catch (ActivityNotFoundException | SecurityException error) {
            pickerOpened = false;
            renderReady("系统没有可用的文件选择器。");
        }
    }

    private void stageAndForward(Uri source) {
        renderProgress("正在安全复制所选 APK…");
        String token = ApkTempFiles.createToken();
        Thread worker = new Thread(() -> {
            try {
                File staged = ApkTempFiles.copyFromUri(this, source, token);
                ApkTempFiles.ArchiveInfo archive = ApkTempFiles.inspectArchive(this, staged);
                runOnUiThread(() -> forwardToManagedProfile(token, staged, archive));
            } catch (IOException | RuntimeException error) {
                ApkTempFiles.delete(this, token);
                runOnUiThread(() -> renderReady(readableCopyError(error)));
            }
        }, "PrivateEntry-apk-source-copy");
        worker.start();
    }

    private void forwardToManagedProfile(
            String token,
            File staged,
            ApkTempFiles.ArchiveInfo archive) {
        if (isFinishing() || isDestroyed()) {
            ApkTempFiles.delete(this, token);
            return;
        }
        Uri contentUri = ApkTempFiles.uriForToken(this, token);
        Intent transfer = new Intent(AppContract.ACTION_INSTALL_APK_IN_MANAGED)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(AppContract.EXTRA_APK_TOKEN, token)
                .putExtra(AppContract.EXTRA_APK_DISPLAY_NAME, archive.label + ".apk")
                .putExtra(AppContract.EXTRA_APK_SIZE, staged.length());
        transfer.setClipData(ClipData.newRawUri("APK", contentUri));
        if (!CrossProfileNavigator.start(this, transfer)) {
            ApkTempFiles.delete(this, token);
            renderReady("无法把 APK 交给隐私空间。临时副本已清理，请确认隐私空间处于开启状态。");
            return;
        }

        renderTransferred(
                "已将 " + archive.label + " 交给隐私空间。"
                        + "隐私空间完成接收后会立即删除主空间临时副本；"
                        + "异常残留超过 6 小时后会在下次启动时清理。");
    }

    private String readableCopyError(Throwable error) {
        String message = error.getMessage();
        if (message != null && message.contains("1 GiB")) {
            return "所选 APK 超过 1 GiB，未进行安装，临时文件已清理。";
        }
        if (message != null && message.contains("Split APK")) {
            return "目前只支持完整的单个 APK，不支持拆分 APK、APKS 或 XAPK。临时文件已清理。";
        }
        return "无法读取或识别所选 APK，临时文件已清理。";
    }

    private void renderReady(String message) {
        LinearLayout root = baseLayout();
        addTitle(root, "从 APK 安装");
        TextView explanation = textView(
                "选择一个完整的 .apk 文件。文件会通过一次性只读授权送入隐私空间，"
                        + "安装结束后两边的临时副本都会被删除。",
                14,
                Color.rgb(70, 75, 86));
        explanation.setGravity(Gravity.CENTER);
        addWithTopMargin(root, explanation, 18);
        if (message != null) {
            TextView status = textView(message, 14, Color.rgb(145, 70, 42));
            status.setGravity(Gravity.CENTER);
            addWithTopMargin(root, status, 16);
        }
        Button choose = button("选择 APK 文件");
        choose.setOnClickListener(view -> openPicker());
        addWithTopMargin(root, choose, 24);
        Button close = button("关闭");
        close.setOnClickListener(view -> finish());
        addWithTopMargin(root, close, 10);
        setContentView(wrap(root));
    }

    private void renderProgress(String message) {
        LinearLayout root = baseLayout();
        addTitle(root, "准备 APK");
        TextView status = textView(message, 15, Color.rgb(25, 79, 170));
        status.setGravity(Gravity.CENTER);
        addWithTopMargin(root, status, 24);
        TextView note = textView("请保持此页面打开。", 13, Color.rgb(82, 87, 98));
        note.setGravity(Gravity.CENTER);
        addWithTopMargin(root, note, 10);
        setContentView(wrap(root));
    }

    private void renderTransferred(String message) {
        LinearLayout root = baseLayout();
        addTitle(root, "已转入隐私空间");
        TextView status = textView(message, 15, Color.rgb(25, 120, 72));
        status.setGravity(Gravity.CENTER);
        addWithTopMargin(root, status, 24);
        Button done = button("完成");
        done.setOnClickListener(view -> finish());
        addWithTopMargin(root, done, 24);
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

    private void addTitle(LinearLayout root, String titleText) {
        TextView title = textView(titleText, 25, Color.rgb(25, 28, 35));
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
