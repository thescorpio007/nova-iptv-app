package com.nova.iptv.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Web tab: bookmarks and browser settings. Saved on the device and, after every change, exported to
 * Download/NovaIPTV/Browser/browser-latest.novaweb (+ the last 5 dated copies), so a new installation can take them back.
 * Never exported: history, cookies, passwords typed into websites.
 */
object WebStore {
    data class Bookmark(val title: String, val url: String)

    const val FOLDER = "Browser"
    const val LATEST = "browser-latest.novaweb"
    private const val KEEP = 5

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("web", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var exportJob: Job? = null

    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    /** "maximum", "strong", "normal" or "off". */
    val adLevel = MutableStateFlow("maximum")
    /** Kids protection: "off", "adult" (adult & gambling sites blocked) or "bookmarks" (only the saved sites). */
    val kidsMode = MutableStateFlow("adult")

    /** On / off switches (see [FLAG_DEFAULTS]). */
    val flags = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val FLAG_DEFAULTS = linkedMapOf(
        "popups" to true, "redirects" to true, "appJumps" to true, "siteDownloads" to true, "blockMsg" to true,
        "safeBrowsing" to true, "denyPerms" to true, "cookies" to true, "privateMode" to false, "javascript" to true,
        "blockImages" to false, "pinOpen" to false, "pinSettings" to true, "hideAddress" to false,
        "normalBrowser" to true, "desktop" to false, "dark" to false,
    )
    fun flag(k: String): Boolean = flags.value[k] ?: FLAG_DEFAULTS[k] ?: false
    fun setFlag(k: String, v: Boolean) { flags.value = flags.value + (k to v); save() }
    fun setKidsMode(v: String) { kidsMode.value = v; save() }
    val findVideos = MutableStateFlow(true)
    /** "bookmarks", "last" or "search". */
    val startPage = MutableStateFlow("bookmarks")
    /** Sites where blocking is off ("example.com"). */
    val allowSites = MutableStateFlow<Set<String>>(emptySet())
    /** A browser file from an earlier installation could be restored (ask the person once). */
    val restoreOffer = MutableStateFlow(false)
    /** A web address to open in the Web tab (from "Open with", Share or the welcome screen). */
    val pendingUrl = MutableStateFlow<String?>(null)
    val lastExport = MutableStateFlow(0L)

    var lastUrl: String
        get() = prefs.getString("last_url", "") ?: ""
        set(v) = prefs.edit().putString("last_url", v).apply()

    val treeUri: Uri? get() = prefs.getString("tree", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun init(context: Context) {
        app = context.applicationContext
        readPrefs()
        scope.launch {
            // A new installation (nothing saved yet): take the exported file back if it can be read.
            if (!prefs.getBoolean("has_data", false)) {
                val text = readExported()
                if (text != null) { runCatching { apply(text) }; Log.i("NovaTest", "WEB restored from $FOLDER/$LATEST") }
                else if (!prefs.getBoolean("offer_done", false)) restoreOffer.value = true
            }
        }
    }

    private fun readPrefs() {
        bookmarks.value = runCatching {
            val a = JSONArray(prefs.getString("bookmarks", "[]"))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Bookmark(o.optString("t"), o.optString("u")) } }
        }.getOrDefault(emptyList())
        adLevel.value = prefs.getString("ad_level", "maximum") ?: "maximum"
        kidsMode.value = prefs.getString("kids_mode", "adult") ?: "adult"
        flags.value = FLAG_DEFAULTS.keys.associateWith { prefs.getBoolean("f_$it", FLAG_DEFAULTS[it]!!) }
        findVideos.value = prefs.getBoolean("find_videos", true)
        startPage.value = prefs.getString("start_page", "bookmarks") ?: "bookmarks"
        allowSites.value = prefs.getStringSet("allow_sites", emptySet())?.toSet() ?: emptySet()
    }

    private fun save() {
        prefs.edit()
            .putString("bookmarks", JSONArray(bookmarks.value.map { JSONObject().put("t", it.title).put("u", it.url) }).toString())
            .putString("ad_level", adLevel.value)
            .putBoolean("find_videos", findVideos.value)
            .putString("start_page", startPage.value)
            .putStringSet("allow_sites", allowSites.value)
            .putString("kids_mode", kidsMode.value)
            .apply { flags.value.forEach { (k, v) -> putBoolean("f_$k", v) } }
            .putBoolean("has_data", true)
            .apply()
        exportSoon()
    }

    // ------------------------------------------------------------ changes

    fun isBookmarked(url: String) = bookmarks.value.any { it.url == url }
    fun toggleBookmark(title: String, url: String) {
        bookmarks.value = if (isBookmarked(url)) bookmarks.value.filterNot { it.url == url }
        else bookmarks.value + Bookmark(title.ifBlank { Uri.parse(url).host ?: url }, url)
        save()
    }
    fun removeBookmark(b: Bookmark) { bookmarks.value = bookmarks.value - b; save() }
    fun moveBookmark(b: Bookmark, delta: Int) {
        val l = bookmarks.value.toMutableList(); val i = l.indexOf(b); val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.removeAt(i); l.add(j, b); bookmarks.value = l; save()
    }
    fun setAdLevel(v: String) { adLevel.value = v; save() }
    fun setFindVideos(v: Boolean) { findVideos.value = v; save() }
    fun setStartPage(v: String) { startPage.value = v; save() }
    fun toggleAllowSite(site: String) {
        allowSites.value = if (site in allowSites.value) allowSites.value - site else allowSites.value + site
        save()
    }

    // ------------------------------------------------------------ export / import

    fun toText(): String = JSONObject()
        .put("format", "NOVAWEB1")
        .put("exportedAt", System.currentTimeMillis())
        .put("bookmarks", JSONArray(bookmarks.value.map { JSONObject().put("t", it.title).put("u", it.url) }))
        .put("adLevel", adLevel.value)
        .put("findVideos", findVideos.value)
        .put("startPage", startPage.value)
        .put("allowSites", JSONArray(allowSites.value.toList()))
        .put("kidsMode", kidsMode.value)
        .put("flags", JSONObject(flags.value as Map<*, *>))
        .toString(2)

    /** Applies an exported file (replaces bookmarks and settings). */
    fun apply(text: String): Int {
        val o = JSONObject(text)
        require(o.optString("format").startsWith("NOVAWEB")) { "not a Nova browser file" }
        val a = o.optJSONArray("bookmarks") ?: JSONArray()
        bookmarks.value = (0 until a.length()).map { a.getJSONObject(it).let { b -> Bookmark(b.optString("t"), b.optString("u")) } }
            .filter { it.url.isNotBlank() }
        adLevel.value = o.optString("adLevel", "maximum")
        kidsMode.value = o.optString("kidsMode", "adult")
        o.optJSONObject("flags")?.let { f -> flags.value = FLAG_DEFAULTS.keys.associateWith { f.optBoolean(it, FLAG_DEFAULTS[it]!!) } }
        findVideos.value = o.optBoolean("findVideos", true)
        startPage.value = o.optString("startPage", "bookmarks")
        val s = o.optJSONArray("allowSites") ?: JSONArray()
        allowSites.value = (0 until s.length()).map { s.getString(it) }.toSet()
        restoreOffer.value = false
        prefs.edit().putBoolean("offer_done", true).apply()
        save()
        return bookmarks.value.size
    }

    private fun folder(): File = Folders.keepDir(app, FOLDER).apply { mkdirs() }

    private fun exportSoon() {
        exportJob?.cancel()
        exportJob = scope.launch { delay(1500); runCatching { export() } }
    }

    /** Writes browser-latest.novaweb and a dated copy (the 5 newest are kept). */
    fun export(): String {
        val text = toText()
        var where = ""
        val dir = runCatching { folder() }.getOrNull()
        if (dir != null) {
            runCatching {
                val tmp = File(dir, "$LATEST.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(File(dir, LATEST))) { File(dir, LATEST).writeText(text); tmp.delete() }
                where = "Download/NovaIPTV/$FOLDER/$LATEST"
            }
            runCatching {
                File(dir, "browser-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".novaweb").writeText(text)
                dir.listFiles { f -> f.name.startsWith("browser-2") && f.name.endsWith(".novaweb") }
                    ?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
            }
        }
        // A file left by an earlier installation can't be overwritten directly: write it through the chosen folder.
        if (where.isEmpty() || treeUri != null) runCatching { if (writeTree(text)) where = "Download/NovaIPTV/$FOLDER/$LATEST" }
        if (where.isNotEmpty()) lastExport.value = System.currentTimeMillis()
        return where
    }

    /** The exported file, read directly or through the folder chosen once with Android's folder picker. */
    private fun readExported(): String? {
        runCatching { File(folder(), LATEST).takeIf { it.canRead() }?.readText() }.getOrNull()?.let { return it }
        return runCatching { readTree() }.getOrNull()
    }

    // ------------------------------------------------------------ the folder chosen once (kept permission)

    fun setTree(uri: Uri): Int? {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().putString("tree", uri.toString()).putBoolean("offer_done", true).apply()
        restoreOffer.value = false
        val text = runCatching { readTree() }.getOrNull() ?: return null
        return runCatching { apply(text) }.getOrNull()
    }

    fun dismissOffer() {
        restoreOffer.value = false
        prefs.edit().putBoolean("offer_done", true).apply()
    }

    private data class Doc(val id: String, val name: String, val dir: Boolean)

    private fun children(tree: Uri, docId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val out = ArrayList<Doc>()
        app.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) out += Doc(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) }
        return out
    }

    /** The "Browser" folder: the chosen folder itself, or its "Browser" sub-folder (created when [create]). */
    private fun browserDirId(tree: Uri, create: Boolean): String? {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val rootName = root.substringAfterLast('/').substringAfterLast(':')
        if (rootName.equals(FOLDER, ignoreCase = true)) return root
        children(tree, root).firstOrNull { it.dir && it.name.equals(FOLDER, ignoreCase = true) }?.let { return it.id }
        if (!create) return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, root)
        return DocumentsContract.createDocument(app.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, FOLDER)
            ?.let { DocumentsContract.getDocumentId(it) }
    }

    private fun readTree(): String? {
        val tree = treeUri ?: return null
        val dirId = browserDirId(tree, create = false) ?: return null
        val doc = children(tree, dirId).filter { !it.dir && it.name.endsWith(".novaweb") }
            .sortedWith(compareByDescending<Doc> { it.name == LATEST }.thenByDescending { it.name })
            .firstOrNull() ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)
        return app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    }

    private fun writeTree(text: String): Boolean {
        val tree = treeUri ?: return false
        val dirId = browserDirId(tree, create = true) ?: return false
        val existing = children(tree, dirId).firstOrNull { it.name == LATEST }
        val uri = existing?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
            ?: DocumentsContract.createDocument(
                app.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, dirId), "application/octet-stream", LATEST,
            ) ?: return false
        app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: return false
        return true
    }
}
