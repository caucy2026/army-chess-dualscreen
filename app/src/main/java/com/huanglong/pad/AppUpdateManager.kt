package com.huanglong.pad

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 对接 KEMI 应用商店公开更新接口。
 * 优先跳转商城详情；商城不可用时下载、校验 APK 并交由系统安装器安装。
 */
internal class AppUpdateManager(private val activity: MainActivity) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-update-worker")
    }
    private val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var receiverRegistered = false
    private var checkStarted = false
    private var pendingDownloadId = NO_DOWNLOAD
    private var pendingDownloadFile: File? = null
    private var pendingInstallFile: File? = null
    private var pendingSha256 = ""

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, NO_DOWNLOAD)
            if (downloadId != pendingDownloadId) return
            handleCompletedDownload(downloadId)
        }
    }

    fun start() {
        registerDownloadReceiver()
        checkForUpdate()
    }

    fun onResume() {
        val apkFile = pendingInstallFile ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = null
            openSystemInstaller(apkFile)
        }
    }

    fun destroy() {
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(downloadReceiver) }
            receiverRegistered = false
        }
        executor.shutdownNow()
    }

    private fun checkForUpdate() {
        if (checkStarted) return
        checkStarted = true
        executor.execute {
            val update = runCatching { requestUpdate() }
                .onFailure { Log.w(TAG, "Update check failed", it) }
                .getOrNull()
                ?: return@execute
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) showUpdateDialog(update)
            }
        }
    }

    private fun requestUpdate(): UpdateInfo? {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.getPackageInfo(
                activity.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }
        val localVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        val packageName = URLEncoder.encode(activity.packageName, Charsets.UTF_8.name())
        val endpoint = "$UPDATE_CHECK_URL?package_name=$packageName&version_code=$localVersionCode"
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "${activity.packageName}/$localVersionCode")
            if (connection.responseCode !in 200..299) return null

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(response)
            if (root.optInt("status") != 200) return null
            val data = root.optJSONObject("data") ?: return null
            val remoteVersionCode = data.optLong("version_code", 0L)
            if (!data.optBoolean("has_update") || remoteVersionCode <= localVersionCode) return null

            val downloadUrl = data.optString("download_url")
                .ifBlank { data.optString("apk_url") }
            if (downloadUrl.isBlank() || Uri.parse(downloadUrl).scheme != "https") return null

            UpdateInfo(
                versionName = data.optString("version_name").ifBlank { remoteVersionCode.toString() },
                versionCode = remoteVersionCode,
                downloadUrl = downloadUrl,
                sha256 = data.optString("apk_sha256").lowercase(),
                forceUpdate = data.optBoolean("force_update"),
                deepLink = data.optString("deeplink"),
                releaseNotes = data.optString("release_notes")
                    .ifBlank { data.optString("short_desc") }
                    .take(MAX_RELEASE_NOTES_LENGTH),
                fileSize = data.optString("file_size")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun showUpdateDialog(update: UpdateInfo) {
        val details = buildString {
            append("发现新版本 ").append(update.versionName)
            formatFileSize(update.fileSize)?.let { append("（").append(it).append("）") }
            if (update.releaseNotes.isNotBlank()) append("\n\n").append(update.releaseNotes)
            if (update.forceUpdate) append("\n\n此版本为必须更新，更新后才能继续使用。")
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("应用更新")
            .setMessage(details)
            .setPositiveButton("立即更新") { _, _ -> beginUpdate(update) }
            .apply {
                if (!update.forceUpdate) setNegativeButton("稍后", null)
            }
            .setCancelable(!update.forceUpdate)
            .create()
        dialog.setCanceledOnTouchOutside(!update.forceUpdate)
        dialog.show()
    }

    private fun beginUpdate(update: UpdateInfo) {
        if (update.deepLink.isNotBlank() && openStoreDeepLink(update.deepLink)) return
        downloadApk(update)
    }

    private fun openStoreDeepLink(deepLink: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).apply {
            setPackage(STORE_PACKAGE_NAME)
        }
        if (intent.resolveActivity(activity.packageManager) == null) return false
        return runCatching {
            activity.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "Unable to open KEMI store", it)
            false
        }
    }

    private fun downloadApk(update: UpdateInfo) {
        val downloadDirectory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDirectory == null) {
            activity.showToast("无法访问更新下载目录")
            return
        }
        val fileName = "army_chess_${update.versionCode}.apk"
        val apkFile = File(downloadDirectory, fileName)
        if (apkFile.exists() && !apkFile.delete()) {
            activity.showToast("无法清理旧的更新安装包")
            return
        }

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("正在下载陆战棋 ${update.versionName}")
            .setDescription("下载完成后将打开系统安装页面")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)

        pendingDownloadFile = apkFile
        pendingSha256 = update.sha256
        pendingDownloadId = runCatching { downloadManager.enqueue(request) }
            .onFailure {
                pendingDownloadFile = null
                activity.showToast("更新下载启动失败")
                Log.w(TAG, "Unable to enqueue update download", it)
            }
            .getOrDefault(NO_DOWNLOAD)
        if (pendingDownloadId != NO_DOWNLOAD) activity.showToast("更新包开始下载")
    }

    private fun handleCompletedDownload(downloadId: Long) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val successful = downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            statusIndex >= 0 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
        } ?: false
        pendingDownloadId = NO_DOWNLOAD

        val apkFile = pendingDownloadFile
        if (!successful || apkFile == null || !apkFile.isFile) {
            pendingDownloadFile = null
            activity.showToast("更新包下载失败，请稍后重试")
            return
        }

        val expectedSha256 = pendingSha256
        executor.execute {
            val isValid = expectedSha256.isBlank() ||
                (expectedSha256.matches(SHA256_PATTERN) && sha256Hex(apkFile) == expectedSha256)
            activity.runOnUiThread {
                if (!isValid) {
                    apkFile.delete()
                    pendingDownloadFile = null
                    activity.showToast("更新包完整性校验失败，已删除")
                } else {
                    pendingDownloadFile = null
                    requestInstall(apkFile)
                }
            }
        }
    }

    private fun requestInstall(apkFile: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = apkFile
            AlertDialog.Builder(activity)
                .setTitle("允许安装更新")
                .setMessage("请允许陆战棋安装来自本应用的更新包，授权后将自动继续安装。")
                .setPositiveButton("去授权") { _, _ ->
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                    activity.startActivity(settingsIntent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        pendingInstallFile = null
        openSystemInstaller(apkFile)
    }

    private fun openSystemInstaller(apkFile: File) {
        val contentUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(PLATFORM_INSTALLER_PACKAGE)
        }
        if (intent.resolveActivity(activity.packageManager) == null) intent.setPackage(null)

        val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            activity.packageManager.queryIntentActivities(intent, 0)
        }
        handlers.forEach { handler ->
            activity.grantUriPermission(
                handler.activityInfo.packageName,
                contentUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (handlers.isEmpty()) {
            activity.showToast("未找到系统安装器")
            return
        }
        runCatching { activity.startActivity(intent) }
            .onFailure {
                activity.showToast("无法打开系统安装页面")
                Log.w(TAG, "Unable to open package installer", it)
            }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerDownloadReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(downloadReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatFileSize(raw: String): String? {
        val bytes = raw.toLongOrNull() ?: return raw.takeIf { it.isNotBlank() }
        return when {
            bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val downloadUrl: String,
        val sha256: String,
        val forceUpdate: Boolean,
        val deepLink: String,
        val releaseNotes: String,
        val fileSize: String
    )

    private companion object {
        const val TAG = "AppUpdateManager"
        const val UPDATE_CHECK_URL = "https://kemi.newlinksz.com/kd-api/api/store/update/check"
        const val STORE_PACKAGE_NAME = "com.newlink.featuredapps"
        const val PLATFORM_INSTALLER_PACKAGE = "com.android.packageinstaller"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val NO_DOWNLOAD = -1L
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
        const val MAX_RELEASE_NOTES_LENGTH = 800
        val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
