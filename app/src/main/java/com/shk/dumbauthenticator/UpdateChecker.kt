package com.shk.dumbauthenticator

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val OWNER = "skohn123"
    private const val REPO = "Dumb-Authenticator"
    private const val API_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val RELEASES_URL =
        "https://github.com/$OWNER/$REPO/releases/latest"

    fun currentVersion(ctx: Context): String {
        return try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    fun check(ctx: Context) {
        Toast.makeText(ctx, "Checking for updates…", Toast.LENGTH_SHORT).show()
        val ui = Handler(Looper.getMainLooper())

        Thread {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val status = conn.responseCode
                if (status != 200) {
                    showToast(ui, ctx, "Update check failed ($status)")
                    return@Thread
                }
                val body = InputStreamReader(conn.inputStream, Charsets.UTF_8).use { it.readText() }
                val obj = JSONObject(body)
                val tag = obj.optString("tag_name", "").trim()
                val latest = stripV(tag)
                val current = currentVersion(ctx)
                val apkUrl = pickApkUrl(obj.optJSONArray("assets"))

                if (latest.isEmpty()) {
                    showToast(ui, ctx, "No release found")
                    return@Thread
                }

                if (compare(latest, current) <= 0) {
                    showToast(ui, ctx, "You're on the latest version ($current)")
                    return@Thread
                }

                ui.post { promptInstall(ctx, latest, apkUrl) }
            } catch (e: Exception) {
                showToast(ui, ctx, "Update check failed: ${e.message}")
            }
        }.start()
    }

    private fun pickApkUrl(assets: JSONArray?): String? {
        if (assets == null) return null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            if (name.lowercase().endsWith(".apk")) {
                val url = a.optString("browser_download_url", "")
                return if (url.isEmpty()) null else url
            }
        }
        return null
    }

    private fun showToast(ui: Handler, ctx: Context, msg: String) {
        ui.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
    }

    private fun promptInstall(ctx: Context, latest: String, apkUrl: String?) {
        if (apkUrl == null) {
            AlertDialog.Builder(ctx, R.style.Theme_DA_Dialog)
                .setTitle("Update available")
                .setMessage("Version $latest is available, but no APK was attached. Open the release page?")
                .setPositiveButton("Open") { _, _ ->
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        AlertDialog.Builder(ctx, R.style.Theme_DA_Dialog)
            .setTitle("Update to $latest?")
            .setMessage("A new version is available. Download and install now?")
            .setPositiveButton("Install") { _, _ -> download(ctx, apkUrl, latest) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(ctx: Context, apkUrl: String, latest: String) {
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Authenticator $latest")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setMimeType("application/vnd.android.package-archive")
            val fileName = "Authenticator-$latest.apk"
            req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, fileName)
            val id = dm.enqueue(req)

            Toast.makeText(ctx, "Downloading update…", Toast.LENGTH_SHORT).show()

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    val got = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (got != id) return
                    try { c.unregisterReceiver(this) } catch (_: Exception) {}
                    val query = DownloadManager.Query().setFilterById(id)
                    dm.query(query)?.use { cur ->
                        if (cur.moveToFirst()) {
                            val statusIdx = cur.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (cur.getInt(statusIdx) != DownloadManager.STATUS_SUCCESSFUL) {
                                Toast.makeText(c, "Download failed", Toast.LENGTH_LONG).show()
                                return
                            }
                            val uriIdx = cur.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val localUri = cur.getString(uriIdx) ?: return
                            val path = Uri.parse(localUri).path ?: return
                            launchInstaller(c, File(path))
                        }
                    }
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(onComplete, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(onComplete, filter)
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchInstaller(ctx: Context, apk: File) {
        try {
            val staged = File(ctx.cacheDir, "update.apk")
            apk.copyTo(staged, overwrite = true)

            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                staged
            )
            val install = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(install)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Install launch failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stripV(s: String): String =
        if (s.startsWith("v", ignoreCase = true)) s.substring(1) else s

    private fun compare(a: String, b: String): Int {
        val pa = a.split(".")
        val pb = b.split(".")
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrNull(i)?.let(::safeInt) ?: 0
            val y = pb.getOrNull(i)?.let(::safeInt) ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    private fun safeInt(s: String): Int {
        val sb = StringBuilder()
        for (c in s) if (c.isDigit()) sb.append(c) else break
        return if (sb.isEmpty()) 0 else sb.toString().toIntOrNull() ?: 0
    }
}
