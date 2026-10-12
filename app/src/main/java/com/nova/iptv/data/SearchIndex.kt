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
            idx.movies.forEach { m -> w.write("M\t${m.streamId}\t${clean(m.name)}\t${clean(m.icon)}\t${m.categoryId}\t${clean(m.rating)}\t${m.extension}\t${m.added}\t${m.year ?: ""}\n") }
            idx.series.forEach { s -> w.write("S\t${s.seriesId}\t${clean(s.name)}\t${clean(s.cover)}\t${s.categoryId}\t${clean(s.rating)}\t${s.added}\t${s.year ?: ""}\n") }
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
                    "M" -> movies += Movie(accountId, p[1], p[2], p[3].ifEmpty { null }, p[4], p[5].ifEmpty { null }, p[6], p[7].toLongOrNull() ?: 0, p[8].toIntOrNull())
                    "S" -> series += Series(accountId, p[1], p[2], p[3].ifEmpty { null }, p[4], null, p[5].ifEmpty { null }, p[6].toLongOrNull() ?: 0, p[7].toIntOrNull())
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
        // Accounts removed: forget them.
        data.keys.filter { id -> accounts.none { it.id == id } }.forEach { data.remove(it) }
        status.value = status.value.filterKeys { id -> accounts.any { it.id == id } }
    }

    fun needsUpdate(acc: Account): Boolean = data[acc.id]?.let { System.currentTimeMillis() - it.builtAt > MAX_AGE } ?: true

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
