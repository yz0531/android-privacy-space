package com.example.privateentry;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Private, short-lived APK staging files shared only through one-time URI grants. */
final class ApkTempFiles {
    private static final String DIRECTORY_NAME = "apk_imports";
    private static final long MAX_APK_BYTES = 1024L * 1024L * 1024L;
    private static final long STALE_FILE_MILLIS = 6L * 60L * 60L * 1000L;

    private ApkTempFiles() {
    }

    static String createToken() {
        return UUID.randomUUID().toString();
    }

    static boolean isValidToken(String token) {
        return token != null && token.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }

    static File copyFromUri(Context context, Uri source, String token) throws IOException {
        if (source == null || !isValidToken(token)) {
            throw new IOException("Invalid APK source");
        }
        File directory = directory(context);
        File destination = fileForToken(context, token);
        File partial = partialFileForToken(context, token);
        deleteFile(destination);
        deleteFile(partial);

        long copied = 0L;
        try (InputStream rawInput = context.getContentResolver().openInputStream(source)) {
            if (rawInput == null) {
                throw new IOException("Unable to open selected APK");
            }
            try (BufferedInputStream input = new BufferedInputStream(rawInput);
                    FileOutputStream fileOutput = new FileOutputStream(partial);
                    BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    copied += read;
                    if (copied > MAX_APK_BYTES) {
                        throw new IOException("APK exceeds the 1 GiB limit");
                    }
                    output.write(buffer, 0, read);
                }
                output.flush();
                fileOutput.getFD().sync();
            }
            if (copied <= 0L) {
                throw new IOException("Selected APK is empty");
            }
            if (!partial.renameTo(destination)) {
                throw new IOException("Unable to finish APK staging");
            }
            destination.setReadable(false, false);
            destination.setReadable(true, true);
            return destination;
        } catch (IOException | RuntimeException error) {
            deleteFile(partial);
            deleteFile(destination);
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Unable to copy selected APK", error);
        }
    }

    static ArchiveInfo inspectArchive(Context context, File apkFile) throws IOException {
        if (apkFile == null || !apkFile.isFile()) {
            throw new IOException("APK staging file is missing");
        }
        PackageManager packageManager = context.getPackageManager();
        PackageInfo packageInfo = packageManager.getPackageArchiveInfo(
                apkFile.getAbsolutePath(),
                PackageManager.GET_META_DATA);
        if (packageInfo == null
                || packageInfo.applicationInfo == null
                || !TransferApps.isValidPackageName(packageInfo.packageName)
                || context.getPackageName().equals(packageInfo.packageName)) {
            throw new IOException("The selected file is not a supported APK");
        }

        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        if (packageInfo.splitNames != null && packageInfo.splitNames.length > 0) {
            throw new IOException("Split APK files are not supported");
        }
        applicationInfo.sourceDir = apkFile.getAbsolutePath();
        applicationInfo.publicSourceDir = apkFile.getAbsolutePath();
        CharSequence loadedLabel = applicationInfo.loadLabel(packageManager);
        String label = loadedLabel == null ? packageInfo.packageName : loadedLabel.toString().trim();
        if (label.isEmpty()) {
            label = packageInfo.packageName;
        } else if (label.length() > 100) {
            label = label.substring(0, 100);
        }
        return new ArchiveInfo(packageInfo.packageName, label, packageInfo.getLongVersionCode());
    }

    static Uri uriForToken(Context context, String token) {
        if (!isValidToken(token)) {
            throw new IllegalArgumentException("Invalid APK token");
        }
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + ".apkfiles")
                .appendPath(token)
                .build();
    }

    static File fileForToken(Context context, String token) {
        if (!isValidToken(token)) {
            throw new IllegalArgumentException("Invalid APK token");
        }
        return new File(directory(context), token + ".apk");
    }

    static void delete(Context context, String token) {
        if (!isValidToken(token)) {
            return;
        }
        deleteFile(fileForToken(context, token));
        deleteFile(partialFileForToken(context, token));
    }

    static void cleanupStale(Context context) {
        File[] files;
        try {
            files = directory(context).listFiles();
        } catch (RuntimeException error) {
            return;
        }
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - STALE_FILE_MILLIS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                deleteFile(file);
            }
        }
    }

    private static File directory(Context context) {
        File directory = new File(context.getCacheDir(), DIRECTORY_NAME);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Unable to create APK staging directory");
        }
        return directory;
    }

    private static File partialFileForToken(Context context, String token) {
        return new File(directory(context), token + ".part");
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) {
            if (!file.delete()) {
                // Make a failed delete immediately eligible for the next startup cleanup pass.
                file.setLastModified(0L);
            }
        }
    }

    static final class ArchiveInfo {
        final String packageName;
        final String label;
        final long versionCode;

        ArchiveInfo(String packageName, String label, long versionCode) {
            this.packageName = packageName;
            this.label = label;
            this.versionCode = versionCode;
        }
    }
}
