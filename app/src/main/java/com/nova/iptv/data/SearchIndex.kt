package com.nova.iptv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Search index: every channel, film and series of every account, kept in Download/NovaIPTV/Index
 * (one file per account) and in memory, so searching is instant. Updated quietly about once a day.
 * Holds names, groups and picture links only — never usernames or passwords.
 */
object SearchIndex {
    private const val MAX_AGE = 24 * 3600_000L
    private const val TAG = "NovaTest"

    /** accountId → its index (in memory). */
    private val data = ConcurrentHashMap<String, AccountIndex>()

    class AccountIndex(
        val builtAt: Long,
        val live: List<LiveChannel>,
        val movies: List<Movie>,
        val series: List<Series>,
    )

    /** What Search shows under the box: accountId → "updated 3 h ago" / "updating 40 %" / "failed: …". */
    val status = MutableStateFlow<Map<String, String>>(emptyMap())
    val busy = MutableStateFlow(false)

    fun get(accountId: String): AccountIndex? = data[accountId]

    private fun dir(context: Context): File = Folders.keepDir(context, "Index").also { it.mkdirs() }

    private fun fileFor(context: Context, acc: Account): File {
        val safe = acc.name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "account" }
        return File(dir(context), "$safe-${acc.id.take(6)}.index")
    }

    private fun clean(s: String?) = s.orEmpty().replace('\t', ' ').replace('\n', ' ')

    private fun write(file: File, idx: AccountIndex) {
        val tmp = File(file.path + ".tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).bufferedWriter().use { w ->
            w.write("NOVAINDEX1\t${idx.builtAt}\n")
            idx.live.forEach { c -> w.write("L\t${c.streamId}\t${clean(c.name)}\t${clean(c.icon)}\t${c.categoryId}\t${c.num}\t${if (c.hasArchive) 1 else 0}\t${c.added}\n") }
            idx.movies.forEach { m -> w.write("M\t${m.streamId}\t${clean(m.name)}\t${clean(m.icon)}\t${m.categoryId}\t${clean(m.rating)}\t${m.extension}\t${m.added}\t${m.year ?: ""}\t${m.tmdb.orEmpty()}\n") }
            idx.series.forEach { s -> w.write("S\t${s.seriesId}\t${clean(s.name)}\t${clean(s.cover)}\t${s.categoryId}\t${clean(s.rating)}\t${s.added}\t${s.year ?: ""}\t${s.tmdb.orEmpty()}\n") }
        }
        file.delete()
        tmp.renameTo(file)
    }

    private fun read(file: File, accountId: String): AccountIndex? = runCatching {
        val live = ArrayList<LiveChannel>()
        val movies = ArrayList<Movie>()
        val series = ArrayList<Series>()
        var builtAt = 0L
        GZIPInputStream(file.inputStream().buffered()).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val p = line.split('\t')
                when (p[0]) {
                    "NOVAINDEX1" -> builtAt = p.getOrNull(1)?.toLongOrNull() ?: 0
                    "L" -> live += LiveChannel(accountId, p[1], p[5].toIntOrNull() ?: 0, p[2], p[3].ifEmpty { null }, p[4], p[6] == "1", p[7].toLongOrNull() ?: 0)
                    "M" -> movies += Movie(accountId, p[1], p[2], p[3].ifEmpty { null }, p[4], p[5].ifEmpty { null }, p[6], p[7].toLongOrNull() ?: 0, p[8].toIntOrNull(), p.getOrNull(9)?.ifEmpty { null })
                    "S" -> series += Series(accountId, p[1], p[2], p[3].ifEmpty { null }, p[4], null, p[5].ifEmpty { null }, p[6].toLongOrNull() ?: 0, p[7].toIntOrNull(), p.getOrNull(8)?.ifEmpty { null })
                }
            }
        }
        AccountIndex(builtAt, live, movies, series)
    }.onFailure { Log.i(TAG, "INDEX unreadable ${file.name}: $it") }.getOrNull()

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when {
            m < 1 -> "updated just now"
            m < 60 -> "updated $m min ago"
            m < 48 * 60 -> "updated ${m / 60} h ago"
            else -> "updated ${m / 1440} days ago"
        }
    }

    private fun setStatus(acc: Account, text: String) {
        status.value = status.value + (acc.id to text)
    }

    /** Loads the saved files (fast, no internet). */
    fun load(context: Context, accounts: List<Account>) {
        accounts.forEach { acc ->
            if (data.containsKey(acc.id)) return@forEach
            val f = fileFor(context, acc)
            if (f.exists()) read(f, acc.id)?.let { idx ->
                data[acc.id] = idx
                setStatus(acc, ago(idx.builtAt))
                Log.i(TAG, "INDEX loaded ${acc.name}: ${idx.live.size} channels, ${idx.movies.size} films, ${idx.series.size} series")
            }
        }
        // New install (or account added again): pick up an exported index of the same server from the Index folder.
        val missing = accounts.filter { !data.containsKey(it.id) }
        if (missing.isNotEmpty()) adoptFromExports(context, missing)
        // Accounts removed: forget them.
        data.keys.filter { id -> accounts.none { it.id == id } }.forEach { data.remove(it) }
        status.value = status.value.filterKeys { id -> accounts.any { it.id == id } }
    }

    // ======================================================== portable export / import (.novaindex)
    //
    // One gzip text file holding the index of one or more servers. Version 2 format:
    //   NOVAINDEX2 <tab> exportedAt <tab> appVersion
    //   A <tab> server label <tab> host <tab> builtAt        (starts a server section)
    //   L / M / S lines, same columns as the per-account .index files
    // No usernames, passwords or stream links: a section is linked to an account by its host.

    const val EXT = "novaindex"
    const val LATEST = "index-latest.$EXT"
    private const val KEEP_DATED = 5

    /** Download/NovaIPTV/Index (or the chosen storage folder). */
    fun folder(context: Context): File = dir(context)

    /** "http://S4.360iptv.net:8000/" → "s4.360iptv.net:8000". */
    fun hostOf(server: String): String =
        server.trim().lowercase().substringAfter("://").substringBefore('/').trimEnd('.')

    class Section(val name: String, val host: String, val index: AccountIndex)
    class Portable(val exportedAt: Long, val sections: List<Section>)

    /** What an export file holds, for the import list. */
    class FileInfo(val file: File, val exportedAt: Long, val servers: List<String>, val items: Int)

    private fun writeLines(w: java.io.Writer, idx: AccountIndex) {
        idx.live.forEach { c -> w.write("L\t${c.streamId}\t${clean(c.name)}\t${clean(c.icon)}\t${c.categoryId}\t${c.num}\t${if (c.hasArchive) 1 else 0}\t${c.added}\n") }
        idx.movies.forEach { m -> w.write("M\t${m.streamId}\t${clean(m.name)}\t${clean(m.icon)}\t${m.categoryId}\t${clean(m.rating)}\t${m.extension}\t${m.added}\t${m.year ?: ""}\t${m.tmdb.orEmpty()}\n") }
        idx.series.forEach { s -> w.write("S\t${s.seriesId}\t${clean(s.name)}\t${clean(s.cover)}\t${s.categoryId}\t${clean(s.rating)}\t${s.added}\t${s.year ?: ""}\t${s.tmdb.orEmpty()}\n") }
    }

    private fun parseItem(p: List<String>, accountId: String, live: MutableList<LiveChannel>, movies: MutableList<Movie>, series: MutableList<Series>) {
        when (p[0]) {
            "L" -> live += LiveChannel(accountId, p[1], p[5].toIntOrNull() ?: 0, p[2], p[3].ifEmpty { null }, p[4], p[6] == "1", p[7].toLongOrNull() ?: 0)
            "M" -> movies += Movie(accountId, p[1], p[2], p[3].ifEmpty { null }, p[4], p[5].ifEmpty { null }, p[6], p[7].toLongOrNull() ?: 0, p[8].toIntOrNull(), p.getOrNull(9)?.ifEmpty { null })
            "S" -> series += Series(accountId, p[1], p[2], p[3].ifEmpty { null }, p[4], null, p[5].ifEmpty { null }, p[6].toLongOrNull() ?: 0, p[7].toIntOrNull(), p.getOrNull(8)?.ifEmpty { null })
        }
    }

    /** Format version of an export file ("NOVAINDEX2" → 2), or null when it isn't one. */
    fun headerVersion(bytes: ByteArray): Int? = runCatching {
        GZIPInputStream(bytes.inputStream()).bufferedReader().use { r ->
            val first = r.readLine()?.substringBefore('\t') ?: return@use null
            if (!first.startsWith("NOVAINDEX")) null else first.removePrefix("NOVAINDEX").toIntOrNull()
        }
    }.getOrNull()

    /** What an export of [accounts] would hold now (server + update time): unchanged = no need to write. */
    fun stamp(accounts: List<Account>): String =
        accounts.mapNotNull { a -> data[a.id]?.let { hostOf(a.server) + "@" + it.builtAt } }.sorted().joinToString(",")

    /** Accounts of [accounts] that have an index in memory right now. */
    fun exportable(accounts: List<Account>): List<Account> = accounts.filter { data.containsKey(it.id) }

    /** Number of channels + films + series indexed for [acc] (0 = none yet). */
    fun itemCount(acc: Account): Int = data[acc.id]?.let { it.live.size + it.movies.size + it.series.size } ?: 0

    /** Writes the index of [accounts] to [out] (gzip). Returns the number of items written. */
    fun exportTo(out: java.io.OutputStream, accounts: List<Account>, appVersion: String): Int {
        var n = 0
        GZIPOutputStream(out.buffered()).bufferedWriter().use { w ->
            w.write("NOVAINDEX2\t${System.currentTimeMillis()}\t${clean(appVersion)}\n")
            accounts.forEach { acc ->
                val idx = data[acc.id] ?: return@forEach
                w.write("A\t${clean(acc.name)}\t${hostOf(acc.server)}\t${idx.builtAt}\n")
                writeLines(w, idx)
                n += idx.live.size + idx.movies.size + idx.series.size
            }
        }
        return n
    }

    /**
     * Saves an export file in the Index folder: index-latest.novaindex when [latest], otherwise a dated
     * copy (index_2026-10-14.novaindex, or index_<server>_2026-10-14.novaindex for one server).
     * Old dated copies are trimmed to the newest five.
     */
    fun exportFile(context: Context, accounts: List<Account>, appVersion: String, latest: Boolean): Pair<File, Int>? {
        val list = exportable(accounts)
        if (list.isEmpty()) return null
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val name = when {
            latest -> LATEST
            list.size == 1 -> "index_${Folders.safeFileName(list[0].name).replace(' ', '-')}_$day.$EXT"
            else -> "index_$day.$EXT"
        }
        val file = File(dir(context), name)
        val tmp = File(file.path + ".tmp")
        val n = tmp.outputStream().use { exportTo(it, list, appVersion) }
        file.delete()
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        if (!latest) trimDated(context)
        Log.i(TAG, "INDEX exported ${file.name}: ${list.size} servers, $n items")
        return file to n
    }

    private fun trimDated(context: Context) {
        dir(context).listFiles { f -> f.name.startsWith("index_") && f.name.endsWith(".$EXT") }
            ?.sortedByDescending { it.lastModified() }?.drop(KEEP_DATED)?.forEach { it.delete() }
    }

    /** Reads an export file (any location). Null when it isn't a Nova index. */
    fun readPortable(input: java.io.InputStream): Portable? = runCatching {
        val sections = ArrayList<Section>()
        var exportedAt = 0L
        var name = ""; var host = ""; var builtAt = 0L
        var live = ArrayList<LiveChannel>(); var movies = ArrayList<Movie>(); var series = ArrayList<Series>()
        var started = false
        fun close() { if (started) sections += Section(name, host, AccountIndex(builtAt, live, movies, series)) }
        GZIPInputStream(input.buffered()).bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, line ->
                val p = line.split('\t')
                if (i == 0) {
                    require(p[0] == "NOVAINDEX2") { "not a Nova index file" }
                    exportedAt = p.getOrNull(1)?.toLongOrNull() ?: 0
                    return@forEachIndexed
                }
                if (p[0] == "A") {
                    close()
                    name = p.getOrElse(1) { "" }; host = p.getOrElse(2) { "" }; builtAt = p.getOrNull(3)?.toLongOrNull() ?: 0
                    live = ArrayList(); movies = ArrayList(); series = ArrayList(); started = true
                } else if (started) parseItem(p, "", live, movies, series)
            }
        }
        close()
        Portable(exportedAt, sections)
    }.onFailure { Log.i(TAG, "INDEX export unreadable: $it") }.getOrNull()

    /** Export files in the Index folder, index-latest first, then newest first. */
    fun exportFiles(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.endsWith(".$EXT", ignoreCase = true) }
            ?.sortedWith(compareByDescending<File> { it.name == LATEST }.thenByDescending { it.lastModified() }).orEmpty()

    fun describe(file: File): FileInfo? = file.inputStream().use { readPortable(it) }?.let { p ->
        FileInfo(file, p.exportedAt, p.sections.map { it.name }, p.sections.sumOf { it.index.live.size + it.index.movies.size + it.index.series.size })
    }

    private fun rebind(idx: AccountIndex, accountId: String) = AccountIndex(
        idx.builtAt,
        idx.live.map { it.copy(accountId = accountId) },
        idx.movies.map { it.copy(accountId = accountId) },
        idx.series.map { it.copy(accountId = accountId) },
    )

    class ImportResult(val linked: List<String>, val waiting: List<String>, val items: Int)

    /**
     * Imports [p]: each server section goes to the account with the same host (replacing its index).
     * Sections with no matching account wait in the Index folder and are linked when that account is added.
     */
    fun importPortable(context: Context, p: Portable, accounts: List<Account>): ImportResult {
        val linked = ArrayList<String>(); val waiting = ArrayList<String>(); var items = 0
        p.sections.forEach { sec ->
            val matches = accounts.filter { hostOf(it.server) == sec.host }
            if (matches.isEmpty()) { waiting += sec.name; return@forEach }
            matches.forEach { acc ->
                val idx = rebind(sec.index, acc.id)
                data[acc.id] = idx
                runCatching { write(fileFor(context, acc), idx) }
                setStatus(acc, ago(idx.builtAt) + " · imported")
                linked += acc.name
                items += idx.live.size + idx.movies.size + idx.series.size
            }
        }
        Log.i(TAG, "INDEX imported: linked ${linked.joinToString()} · waiting ${waiting.joinToString()}")
        return ImportResult(linked, waiting, items)
    }

    /** Keeps a copy of an imported file in the Index folder, so its waiting servers can be linked later. */
    fun keepCopy(context: Context, bytes: ByteArray): File? = runCatching {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())
        File(dir(context), "index_imported_$day.$EXT").also { it.writeBytes(bytes) }
    }.getOrNull()

    /** Looks through the export files (newest first) for the servers of [accounts] that have no index yet. */
    private fun adoptFromExports(context: Context, accounts: List<Account>) {
        var left = accounts
        for (f in exportFiles(context)) {
            if (left.isEmpty()) break
            val p = runCatching { f.inputStream().use { readPortable(it) } }.getOrNull() ?: continue
            val hosts = p.sections.map { it.host }.toSet()
            val here = left.filter { hostOf(it.server) in hosts }
            if (here.isEmpty()) continue
            importPortable(context, p, here)
            Log.i(TAG, "INDEX adopted from ${f.name}: ${here.joinToString { it.name }}")
            left = left - here.toSet()
        }
    }

    fun needsUpdate(acc: Account, maxAge: Long = MAX_AGE): Boolean = data[acc.id]?.let { System.currentTimeMillis() - it.builtAt > maxAge } ?: true

    /**
     * (Re)builds the index of [acc]: the full lists, or group by group when a list is too big for the
     * server. Keeps the old index until the new one is complete.
     */
    suspend fun update(context: Context, client: OkHttpClient, userAgent: () -> String, acc: Account) {
        // Big providers need time to produce their full lists.
        val slow = client.newBuilder().readTimeout(150, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
        val api = XtreamApi(slow, userAgent)
        try {
            setStatus(acc, "updating 0 %")
            Log.i(TAG, "INDEX updating ${acc.name}")
            suspend fun <T> all(type: ContentType, step: Int, whole: suspend () -> List<T>, byGroup: suspend (String) -> List<T>): List<T> =
                try {
                    whole()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.i(TAG, "INDEX ${acc.name} ${type.name.lowercase()}: full list failed ($e), group by group")
                    val groups = api.categories(acc, type)
                    val out = ArrayList<T>()
                    groups.forEachIndexed { i, g ->
                        out += runCatching { byGroup(g.id) }.getOrDefault(emptyList())
                        setStatus(acc, "updating ${step * 33 + (i + 1) * 33 / groups.size.coerceAtLeast(1)} %")
                    }
                    out
                }
            val live = all(ContentType.LIVE, 0, { api.liveStreams(acc, null) }, { api.liveStreams(acc, it) })
            setStatus(acc, "updating 33 %")
            val movies = all(ContentType.MOVIE, 1, { api.movies(acc, null) }, { api.movies(acc, it) })
            setStatus(acc, "updating 66 %")
            val series = all(ContentType.SERIES, 2, { api.series(acc, null) }, { api.series(acc, it) })
            if (live.isEmpty() && movies.isEmpty() && series.isEmpty()) throw IllegalStateException("the server sent empty lists")
            val idx = AccountIndex(System.currentTimeMillis(), live, movies, series)
            data[acc.id] = idx
            runCatching { write(fileFor(context, acc), idx) }.onFailure { Log.i(TAG, "INDEX could not save ${acc.name}: $it") }
            setStatus(acc, ago(idx.builtAt))
            Log.i(TAG, "INDEX built ${acc.name}: ${live.size} channels, ${movies.size} films, ${series.size} series")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val old = data[acc.id]
            setStatus(acc, (if (old != null) ago(old.builtAt) + " · " else "") + "update failed: ${e.message ?: e.javaClass.simpleName}")
            Log.i(TAG, "INDEX failed ${acc.name}: $e")
        }
    }
}
