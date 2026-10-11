package com.nova.iptv.data

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional password protection for backups and accounts lists: AES-256-GCM with a key from the password
 * (PBKDF2, 120 000 rounds). A protected file is text starting with "NOVAENC1:".
 */
object NovaCrypto {
    private const val PREFIX = "NOVAENC1:"
    private const val ROUNDS = 120_000

    fun isProtected(text: String) = text.trimStart().startsWith(PREFIX)

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ROUNDS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(text: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val out = salt + iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Throws IllegalArgumentException("Wrong password") when the password doesn't open it. */
    fun decrypt(text: String, password: String): String {
        val raw = runCatching { Base64.decode(text.trim().removePrefix(PREFIX), Base64.DEFAULT) }.getOrNull()
            ?: throw IllegalArgumentException("This protected file is damaged")
        if (raw.size < 29) throw IllegalArgumentException("This protected file is damaged")
        val salt = raw.copyOfRange(0, 16)
        val iv = raw.copyOfRange(16, 28)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            String(c.doFinal(raw.copyOfRange(28, raw.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("Wrong password")
        }
    }
}

/** Downloads a small text file (backup, accounts list) from any web link, including share pages. */
object LinkFetcher {
    private const val MAX_BYTES = 20 * 1024 * 1024

    /** Links to try for [link]: the share page's direct download first, then the link itself. */
    fun candidates(link: String): List<String> {
        val url = link.trim()
        val lower = url.lowercase()
        val out = mutableListOf<String>()
        when {
            "1drv.ms" in lower || "onedrive.live.com" in lower || "sharepoint.com" in lower -> {
                val enc = Base64.encodeToString(url.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
                out += "https://api.onedrive.com/v1.0/shares/u!$enc/root/content"
                out += if ("?" in url) "$url&download=1" else "$url?download=1"
            }
            "drive.google.com" in lower || "docs.google.com" in lower -> {
                val id = Regex("""/d/([\w-]{10,})""").find(url)?.groupValues?.get(1)
                    ?: Regex("""[?&]id=([\w-]{10,})""").find(url)?.groupValues?.get(1)
                if (id != null) out += "https://drive.google.com/uc?export=download&id=$id"
            }
            "dropbox.com" in lower -> {
                out += when {
                    "dl=0" in url -> url.replace("dl=0", "dl=1")
                    "dl=1" in url -> url
                    "?" in url -> "$url&dl=1"
                    else -> "$url?dl=1"
                }
            }
        }
        out += url
        return out.distinct()
    }

    private fun isOneDrive(link: String) = link.lowercase().let { "1drv.ms" in it || "onedrive.live.com" in it }

    /**
     * Personal OneDrive (new "1drv.ms/u/c/…" links): get a guest token like OneDrive's own web page does,
     * then ask OneDrive for the file's direct download address.
     */
    private fun oneDriveDirect(client: OkHttpClient, link: String, userAgent: String): String {
        val tokenReq = Request.Builder()
            .url("https://api-badgerp.svc.ms/v1.0/token")
            .header("User-Agent", userAgent)
            .post("""{"appId":"5cbed6ac-a083-4e14-b191-b4ba07653de2"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val token = client.newCall(tokenReq).execute().use { r ->
            if (!r.isSuccessful) throw IOException("OneDrive guest access answered HTTP ${r.code}")
            org.json.JSONObject(r.body?.string().orEmpty()).getString("token")
        }
        val enc = "u!" + Base64.encodeToString(link.trim().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val metaReq = Request.Builder()
            .url("https://my.microsoftpersonalcontent.com/_api/v2.0/shares/$enc/driveitem?\$select=id,name,size,@content.downloadUrl")
            .header("User-Agent", userAgent)
            .header("Authorization", "Badger $token")
            .header("Prefer", "autoredeem")
            .build()
        return client.newCall(metaReq).execute().use { r ->
            if (r.code == 404) throw IOException("OneDrive says this file doesn't exist (or isn't shared)")
            if (!r.isSuccessful) throw IOException("OneDrive answered HTTP ${r.code}")
            val json = org.json.JSONObject(r.body?.string().orEmpty())
            if (json.has("folder")) throw IOException("this is a link to a folder: share the file itself")
            json.optString("@content.downloadUrl").ifBlank { throw IOException("OneDrive gave no download address") }
        }
    }

    /** Blocking (call from a background thread). Throws IOException with a clear message. */
    fun fetchText(client: OkHttpClient, link: String, userAgent: String): String {
        if (!link.trim().startsWith("http://", true) && !link.trim().startsWith("https://", true)) {
            throw IOException("This is not a web link (it must start with http:// or https://)")
        }
        var last: Exception? = null
        val tries = mutableListOf<String>()
        if (isOneDrive(link)) {
            try {
                tries += oneDriveDirect(client, link, userAgent)
            } catch (e: Exception) {
                last = e
            }
        }
        tries += candidates(link)
        for (u in tries.distinct()) {
            try {
                val req = Request.Builder().url(u).header("User-Agent", userAgent).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("the server answered HTTP ${r.code}")
                    val body = r.body ?: throw IOException("empty answer")
                    val bytes = body.byteStream().use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            buf.write(chunk, 0, n)
                            if (buf.size() > MAX_BYTES) throw IOException("the file is too big")
                        }
                        buf.toByteArray()
                    }
                    val text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
                    // A share page (HTML) instead of the file: try the next way.
                    if (text.trimStart().startsWith("<")) throw IOException("the link opens a web page, not the file")
                    return text
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Could not download the file: ${last?.message ?: "unknown error"}. Check that the link is shared as “anyone with the link”.")
    }
}

/** One account read from your accounts list. */
data class ListedAccount(val name: String, val server: String, val username: String, val password: String)

/**
 * Accounts list format (a plain text file you keep up to date):
 *   Name | server | username | password
 *   Name | http://server:port/get.php?username=…&password=…   (a full Xtream / M3U link)
 *   # lines starting with # are notes
 */
object AccountsList {
    fun parse(text: String): List<ListedAccount> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('|').map { it.trim() }
            when {
                parts.size >= 4 && parts[1].isNotBlank() && parts[2].isNotBlank() && parts[3].isNotBlank() ->
                    ListedAccount(parts[0].ifBlank { parts[1] }, XtreamApi.normalizeServer(parts[1]), parts[2], parts[3])
                parts.size >= 2 -> XtreamApi.parseLink(parts[1])?.let { (srv, u, p) ->
                    ListedAccount(parts[0].ifBlank { srv }, XtreamApi.normalizeServer(srv), u, p)
                }
                parts.size == 1 -> XtreamApi.parseLink(parts[0])?.let { (srv, u, p) ->
                    val n = srv.substringAfter("://").substringBefore('/')
                    ListedAccount(n, XtreamApi.normalizeServer(srv), u, p)
                }
                else -> null
            }
        }
        .distinctBy { it.name.lowercase() }
        .toList()

    /** Your accounts as a list file (to put on OneDrive etc.). */
    fun write(accounts: List<Account>): String =
        "# Nova IPTV accounts — one per line: Name | server | username | password\n" +
            accounts.joinToString("\n") { "${it.name} | ${it.server} | ${it.username} | ${it.password}" } + "\n"
}
