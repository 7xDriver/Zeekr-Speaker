package app.zeekrspeaker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** GitHub updater with a persistent APK and PackageInstaller-based installation. */
object GitHubUpdater {
    private const val OWNER = "7xDriver"
    private const val REPO = "Zeekr-Speaker"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30"
    private const val USER_AGENT = "ZEEKR-Speaker-Android"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 20_000
    private const val MAX_JSON_BYTES = 2 * 1024 * 1024
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val PREFS = "update_state"
    private const val KEY_APK = "apk_path"
    private const val KEY_VERSION = "apk_version"
    private const val KEY_NAME = "apk_name"
    private val main = Handler(Looper.getMainLooper())

    data class Release(
        val name: String, val tag: String, val version: String, val apkName: String,
        val apkUrl: String, val apkBytes: Long, val digest: String, val notes: String
    )

    data class PendingUpdate(val file: File, val version: String, val name: String)

    fun pendingUpdate(context: Context): PendingUpdate? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_APK, null) ?: return null
        val file = File(path)
        val version = prefs.getString(KEY_VERSION, null) ?: return null
        val name = prefs.getString(KEY_NAME, file.name) ?: file.name
        if (!file.isFile || file.length() <= 0L) {
            clearPending(context)
            return null
        }
        val installed = installedVersion(context)
        if (compareVersions(installed, version) >= 0) {
            clearPending(context)
            return null
        }
        return PendingUpdate(file, version, name)
    }

    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_APK).remove(KEY_VERSION).remove(KEY_NAME).apply()
    }

    fun check(context: Context, onResult: (String, Release?) -> Unit) {
        Thread({
            try {
                val json = getText(API)
                val releases = JSONArray(json)
                var latest: Release? = null
                for (i in 0 until releases.length()) {
                    val item = releases.optJSONObject(i) ?: continue
                    if (item.optBoolean("draft", false)) continue
                    val tag = item.optString("tag_name", "").trim()
                    val version = extractVersion(tag) ?: continue
                    if (!isStableOrBeta(version)) continue
                    val assets = item.optJSONArray("assets") ?: continue
                    var chosen: JSONObject? = null
                    for (j in 0 until assets.length()) {
                        val asset = assets.optJSONObject(j) ?: continue
                        val filename = asset.optString("name", "")
                        val url = asset.optString("browser_download_url", "")
                        if (filename.endsWith(".apk", true) && url.startsWith("https://")) {
                            if (chosen == null || filename.contains("universal", true)) chosen = asset
                            if (filename.contains("universal", true)) break
                        }
                    }
                    val asset = chosen ?: continue
                    val candidate = Release(
                        item.optString("name", "").ifBlank { tag }, tag, version,
                        asset.optString("name", "update.apk"),
                        asset.optString("browser_download_url", ""),
                        asset.optLong("size", 0L), asset.optString("digest", ""),
                        item.optString("body", "").trim()
                    )
                    if (latest == null || compareVersions(candidate.version, latest!!.version) > 0) latest = candidate
                }
                val installed = installedVersion(context)
                val found = latest
                val newer = found != null && compareVersions(found.version, installed) > 0
                val message = when {
                    found == null -> "No published stable or beta GitHub release with an APK was found."
                    newer -> "Update ${found.version} is available."
                    else -> "You are up to date. Latest release: ${found.version}"
                }
                main.post { onResult(message, if (newer) found else null) }
            } catch (e: Exception) {
                main.post { onResult("Update check failed: ${e.message ?: e.javaClass.simpleName}", null) }
            }
        }, "github-update-check").start()
    }

    fun download(context: Context, release: Release, onResult: (String, PendingUpdate?) -> Unit) {
        Thread({
            val directory = File(context.filesDir, "updates")
            val partial = File(directory, "${release.version}.apk.part")
            try {
                if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create update storage")
                if (partial.exists()) partial.delete()
                val safeName = release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "update.apk" }
                val completed = File(directory, "${release.version}_$safeName")
                downloadVerified(release, partial)
                validateApk(context, partial, release.version)
                if (completed.exists() && !completed.delete()) throw IOException("Could not replace previous APK")
                if (!partial.renameTo(completed)) throw IOException("Could not finalise downloaded APK")
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_APK, completed.absolutePath)
                    .putString(KEY_VERSION, release.version)
                    .putString(KEY_NAME, completed.name).commit()
                val pending = PendingUpdate(completed, release.version, completed.name)
                main.post { onResult("Downloaded and verified ${release.version}. Starting installation…", pending) }
            } catch (e: Exception) {
                partial.delete()
                main.post { onResult("Download failed: ${e.message ?: e.javaClass.simpleName}", pendingUpdate(context)) }
            }
        }, "github-update-download").start()
    }

    /** Creates a PackageInstaller session directly, avoiding unreliable generic APK file handlers. */
    fun install(context: Context, update: PendingUpdate, onResult: (String) -> Unit) {
        Thread({
            try {
                if (!update.file.isFile || update.file.length() <= 0L) throw IOException("Downloaded APK is no longer available")
                validateApk(context, update.file, update.version)
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                }
                val sessionId = installer.createSession(params)
                try {
                    installer.openSession(sessionId).use { session ->
                        FileInputStream(update.file).use { input ->
                            session.openWrite("base.apk", 0, update.file.length()).use { output ->
                                input.copyTo(output, 64 * 1024)
                                session.fsync(output)
                            }
                        }
                        val callback = Intent(context, UpdateInstallReceiver::class.java)
                            .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)
                            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                        val pending = PendingIntent.getBroadcast(
                            context, sessionId, callback,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                        )
                        session.commit(pending.intentSender)
                    }
                } catch (e: Exception) {
                    try { installer.abandonSession(sessionId) } catch (_: Exception) {}
                    throw e
                }
                main.post { onResult("Installation request sent to Android. Confirm the installation if prompted.") }
            } catch (e: Exception) {
                main.post { onResult("Installation could not be started: ${e.message ?: e.javaClass.simpleName}. The downloaded APK has been kept; you can retry.") }
            }
        }, "github-update-install").start()
    }

    private fun validateApk(context: Context, file: File, expectedVersion: String) {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: throw IOException("Downloaded file is not a readable Android APK")
        if (info.packageName != context.packageName) throw IOException("APK package name does not match ${context.packageName}")
        val apkVersion = info.versionName ?: throw IOException("APK has no version name")
        if (compareVersions(apkVersion, expectedVersion) != 0) throw IOException("APK version $apkVersion does not match release $expectedVersion")
        val current = packageInfo(context)
        val currentSigners = current.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        val newSigners = info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        if (currentSigners.isNotEmpty() && newSigners.isNotEmpty() && currentSigners.intersect(newSigners).isEmpty()) {
            throw IOException("APK signing certificate does not match the installed app")
        }
        if (info.longVersionCode <= current.longVersionCode) throw IOException("APK version code must be higher than the installed version code")
    }

    private fun downloadVerified(release: Release, target: File) {
        val expectedDigest = release.digest.removePrefix("sha256:")
        if (!expectedDigest.matches(Regex("[0-9a-fA-F]{64}"))) throw IOException("GitHub did not provide a valid SHA-256 digest; refusing an unverified download")
        val connection = open(release.apkUrl, "application/octet-stream")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("APK download returned HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            if (total <= 0L || total > MAX_APK_BYTES) throw IOException("APK size is missing or exceeds the safety limit")
            if (release.apkBytes > 0 && total != release.apkBytes) throw IOException("APK size does not match GitHub release metadata")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input -> target.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    received += count
                    if (received > MAX_APK_BYTES) throw IOException("APK exceeded the maximum allowed size")
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            if (received != total) throw IOException("Download was incomplete ($received of $total bytes)")
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expectedDigest, true)) throw IOException("Downloaded APK SHA-256 does not match GitHub")
        } finally { connection.disconnect() }
    }

    private fun getText(url: String): String {
        val connection = open(url, "application/vnd.github+json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub returned HTTP ${connection.responseCode}")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    if (bytes.size() + count > MAX_JSON_BYTES) throw IOException("GitHub response exceeded size limit")
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        } finally { connection.disconnect() }
    }

    private fun open(value: String, accept: String): HttpURLConnection {
        var next = URL(value)
        if (next.protocol != "https" || next.userInfo != null) throw IOException("Update URLs must use HTTPS")
        for (redirect in 0..MAX_REDIRECTS) {
            val connection = next.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT; connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"; connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", accept); connection.instanceFollowRedirects = false
            val code = connection.responseCode
            if (code !in listOf(301, 302, 303, 307, 308)) return connection
            val location = connection.getHeaderField("Location") ?: run { connection.disconnect(); throw IOException("Redirect did not include a destination") }
            next = URL(next, location); connection.disconnect()
            if (next.protocol != "https" || next.userInfo != null) throw IOException("GitHub redirected to a non-HTTPS URL")
        }
        throw IOException("Too many GitHub download redirects")
    }

    private fun packageInfo(context: Context): android.content.pm.PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun installedVersion(context: Context): String = packageInfo(context).versionName ?: "0.2.0"
    private fun extractVersion(value: String): String? = Regex("(?i)v?(\\d+\\.\\d+(?:\\.\\d+)?(?:-[0-9A-Za-z.-]+)?)").find(value)?.groupValues?.get(1)
    private fun isStableOrBeta(version: String): Boolean = version.substringAfter('-', "").let { it.isEmpty() || it.startsWith("beta", true) }
    private fun compareVersions(a: String, b: String): Int {
        fun split(value: String): Pair<List<Int>, String> { val clean = value.trim().removePrefix("v").removePrefix("V"); return clean.substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 } to clean.substringAfter('-', "") }
        val (aa, asuf) = split(a); val (bb, bsuf) = split(b)
        for (i in 0 until maxOf(aa.size, bb.size)) { val compared = aa.getOrElse(i) { 0 }.compareTo(bb.getOrElse(i) { 0 }); if (compared != 0) return compared }
        if (asuf.isEmpty() != bsuf.isEmpty()) return if (asuf.isEmpty()) 1 else -1
        return asuf.compareTo(bsuf, ignoreCase = true)
    }
}
