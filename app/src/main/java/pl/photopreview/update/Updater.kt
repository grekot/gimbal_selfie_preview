package pl.photopreview.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Self-update from the public GitHub Releases of this repo. */
object Updater {
    private const val LATEST_URL =
        "https://api.github.com/repos/grekot/gimbal_selfie_preview/releases/latest"

    data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String?)

    /** Fetch the latest release (blocking — call on Dispatchers.IO). */
    fun fetchLatest(): ReleaseInfo? = try {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PhotoPreview")
            connectTimeout = 15000
            readTimeout = 15000
        }
        if (conn.responseCode != 200) {
            null
        } else {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(text)
            val tag = o.optString("tag_name").removePrefix("v")
            val notes = o.optString("body")
            var apkUrl: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            ReleaseInfo(tag, notes, apkUrl)
        }
    } catch (e: Exception) {
        null
    }

    /** True if [latest] is a higher version than [current] (dot/dash-separated integers). */
    fun isNewer(latest: String, current: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").split(Regex("[._-]")).mapNotNull { it.toIntOrNull() }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Download the APK (blocking — call on Dispatchers.IO). Throws on failure so the caller can
     * surface the reason. Follows redirects manually (more reliable across hosts on old Android)
     * and writes to external cache so old installers can read a file:// URI.
     */
    fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): File {
        var current = url
        var redirects = 0
        var conn: HttpURLConnection
        while (true) {
            conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20000
                readTimeout = 60000
                setRequestProperty("User-Agent", "PhotoPreview")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null) throw IOException("Brak Location w przekierowaniu")
                if (++redirects > 6) throw IOException("Za dużo przekierowań")
                current = location
                continue
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            break
        }
        val total = conn.contentLength
        val dir = context.externalCacheDir ?: context.cacheDir
        val file = File(dir, "update.apk")
        conn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var sum = 0L
                while (input.read(buf).also { read = it } >= 0) {
                    output.write(buf, 0, read)
                    sum += read
                    if (total > 0) onProgress(((sum * 100) / total).toInt())
                }
            }
        }
        return file
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    fun installApk(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            @Suppress("DEPRECATION")
            intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
        }
        context.startActivity(intent)
    }
}
