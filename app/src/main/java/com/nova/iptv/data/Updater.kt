package com.nova.iptv.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.nova.iptv.NovaApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * ⬆ Updates from GitHub Releases (thescorpio007/nova-iptv-app): daily check + manual button, one tap to install.
 * Every APK is checked against the release's SHA-256 file and must be signed with the same key as this Nova.
 */
object Updater {
    private const val API = "https://api.github.com/repos/thescorpio007/nova-iptv-app/releases/latest"
    private const val DAY = 24L * 3600_000L

    data class Release(val build: Int, val tag: String, val apkName: String, val apkUrl: String, val shaUrl: String?,
                       val sizeBytes: Long, val publishedAt: String, val notes: String)

    /** The newer release found (null = up to date / not checked). */
    val available = MutableStateFlow<Release?>(null)
    /** Last answer of the check (latest seen, even when not newer). */
    val latest = MutableStateFlow<Release?>(null)
    val lastCheck = MutableStateFlow(0L)
    /** Download progress 0..1 (null = not downloading), and the last problem. */
    val progress = MutableStateFlow<Float?>(null)
    val problem = MutableStateFlow<String?>(null)
    /** The note at the top right was already shown for this build. */
    val noteShown = MutableStateFlow(false)

    private fun prefs(c: Context) = c.getSharedPreferences("nova_update", Context.MODE_PRIVATE)
    fun daily(c: Context) = prefs(c).getBoolean("daily", true)
    fun setDaily(c: Context, on: Boolean) = prefs(c).edit().putBoolean("daily", on).apply()

    /** This APK's build number (versionCode 400000 + build). */
    fun currentBuild(c: Context): Int = runCatching {
        val info = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        code - 400000
    }.getOrDefault(0)

    /** The Android 5 / 6 build (older tablets) only takes "Android5.0" APKs. */
    private val legacy get() = Build.VERSION.SDK_INT < 24

    /** Once a day by itself (when switched on). */
    suspend fun checkDaily(c: Context) {
        lastCheck.value = prefs(c).getLong("last", 0L)
        if (!daily(c) || System.currentTimeMillis() - lastCheck.value < DAY) {
            // Still show what an earlier check found.
            if (available.value == null) runCatching { check(c, quiet = true) }
            return
        }
        check(c, quiet = true)
    }

    /** Asks GitHub for the latest release. Returns a problem text, or null. */
    suspend fun check(c: Context, quiet: Boolean = false): String? = withContext(Dispatchers.IO) {
        val app = c.applicationContext as NovaApp
        val client = app.http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        val err = runCatching {
            client.newCall(Request.Builder().url(API).header("Accept", "application/vnd.github+json").header("User-Agent", "NovaIPTV").build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use "GitHub answered ${r.code}"
                    val o = JSONObject(r.body?.string().orEmpty())
                    val tag = o.optString("tag_name")
                    val build = Regex("""build-(\d+)""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@use "No build number in $tag"
                    val assets = o.optJSONArray("assets") ?: return@use "No files in the release"
                    val list = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    val apk = list.firstOrNull { a ->
                        val n = a.optString("name")
                        n.endsWith(".apk") && (n.contains("Android5.0") == legacy)
                    } ?: return@use if (legacy) "No Android 5.0 version in the latest release" else "No APK in the latest release"
                    val name = apk.optString("name")
                    val sha = list.firstOrNull { it.optString("name") == "$name.sha256" }?.optString("browser_download_url")
                    val rel = Release(build, tag, name, apk.optString("browser_download_url"), sha, apk.optLong("size"),
                        o.optString("published_at"), o.optString("body"))
                    latest.value = rel
                    available.value = if (build > currentBuild(c)) rel else null
                    null
                }
        }.getOrElse { "Couldn't reach GitHub (${it.message ?: "no answer"})" }
        val now = System.currentTimeMillis()
        prefs(c).edit().putLong("last", now).apply()
        lastCheck.value = now
        if (err != null) Log.i("NovaTest", "UPDATE check: $err") else Log.i("NovaTest", "UPDATE check: latest ${latest.value?.build}, have ${currentBuild(c)}")
        err
    }

    /** Downloads, checks (SHA-256 + same signature) and opens Android's install screen. */
    suspend fun install(c: Context, rel: Release) {
        problem.value = null
        // First time: Android must allow Nova to install apps.
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            problem.value = "Allow Nova to “install unknown apps”, press Back, then Update again."
            runCatching {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val app = c.applicationContext as NovaApp
        val dir = File(c.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, rel.apkName)
        progress.value = 0f
        val err = withContext(Dispatchers.IO) {
            runCatching {
                val client = app.http.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
                val expected = rel.shaUrl?.let { u ->
                    client.newCall(Request.Builder().url(u).header("User-Agent", "NovaIPTV").build()).execute().use { r ->
                        if (!r.isSuccessful) null else r.body?.string()?.trim()?.substringBefore(' ')?.lowercase()
                    }
                } ?: return@runCatching "The checksum file of this release is missing — not installed."
                val md = MessageDigest.getInstance("SHA-256")
                client.newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "NovaIPTV").build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching "GitHub answered ${r.code}"
                    val total = r.body?.contentLength()?.takeIf { it > 0 } ?: rel.sizeBytes
                    val ins = r.body?.byteStream() ?: return@runCatching "Empty download"
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (isActive) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); md.update(buf, 0, n); done += n
                            if (total > 0) progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (got != expected) { file.delete(); return@runCatching "The download is damaged or changed (checksum doesn't match) — not installed." }
                if (!sameSignature(c, file)) { file.delete(); return@runCatching "This APK isn't signed like your Nova — not installed." }
                null
            }.getOrElse { "Download failed (${it.message ?: "no answer"})" }
        }
        progress.value = null
        if (err != null) { problem.value = err; return }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", file)
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { problem.value = "Android couldn't open the installer (${it.message})" }
    }

    /** The downloaded APK is Nova, signed with the same key as this Nova (else Android would refuse the update anyway). */
    @Suppress("DEPRECATION")
    private fun sameSignature(c: Context, apk: File): Boolean = runCatching {
        val pm = c.packageManager
        val mine = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNATURES).signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val info = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES) ?: return false
        if (info.packageName != c.packageName) return false
        val other = info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        // Some Android versions don't read an archive's signature: then Android's own install check decides.
        mine.isNotEmpty() && (other.isEmpty() || mine == other)
    }.getOrDefault(false)
}
