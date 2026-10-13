package com.nova.iptv.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Parental age filter with the USA ratings: OFF · G · PG · PG-13 · R · NC-17 (the highest rating allowed).
 * Films use the MPA rating, series the US TV rating (TV-Y/Y7/G = G, TV-PG = PG, TV-14 = PG-13, TV-MA = R).
 * Ratings come from the TMDB film database (by the film's TMDB number, or by its cleaned name + year),
 * or from simple genre / name rules. They are kept in Download/NovaIPTV/Index/ages.gz.
 */
object AgeFilter {
    private const val TAG = "NovaTest"

    /** Levels and ratings: 0 = OFF / unknown, 1 G, 2 PG, 3 PG-13, 4 R, 5 NC-17, 6 adult (18+ / X). */
    val LEVELS = listOf("OFF", "G", "PG", "PG-13", "R", "NC-17")
    val LEVEL_NOTES = listOf(
        "No age filter",
        "General audiences — all ages",
        "Parental guidance suggested",
        "Not for under 13",
        "Restricted — under 17 with a parent",
        "Adults only (only 18+ / X is hidden)",
    )
    const val ADULT = 6

    // ---- settings (copied from Storage by [configure]) ----
    @Volatile var level = 0; private set
    @Volatile var source = "tmdb"; private set
    @Volatile var hideUnknown = true; private set
    @Volatile var liveMode = "name"; private set
    @Volatile var allowedGroups: Set<String> = emptySet(); private set
    @Volatile private var tmdbKey = ""

    /** Changes each time something that changes the lists changes (screens recompute with it). */
    val version = MutableStateFlow(0)
    /** "5 230 checked · 1 146 above PG hidden · updated 2 h ago" / "checking 1 200 / 5 230…" */
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)

    /** "movie|acc|id" / "series|acc|id" → rating (0 = no rating found). */
    private val items = ConcurrentHashMap<String, Int>()
    /** TMDB answers shared by all accounts: "m:603" / "t:1399" / "mn:the matrix|1999" → rating. */
    private val answers = ConcurrentHashMap<String, Int>()
    @Volatile private var checkedAt = 0L
    @Volatile private var loaded = false

    fun configure(s: Storage) {
        val changed = level != s.ageLevel || source != s.ageSource || hideUnknown != s.ageHideUnknown ||
            liveMode != s.ageLiveMode || allowedGroups != s.ageAllowedGroups
        level = s.ageLevel
        source = s.ageSource
        hideUnknown = s.ageHideUnknown
        liveMode = s.ageLiveMode
        allowedGroups = s.ageAllowedGroups
        tmdbKey = s.tmdbKey.trim()
        if (changed) bump()
    }

    private fun bump() { version.value = version.value + 1 }

    // ------------------------------------------------------------------ rules

    private val adultWords = Regex("""(?i)(\bxxx\b|\badult(e|es|s)?\b|\bporn\w*|\berotic\w*|érotique|\berotique\b|\bsexy\b|\b18\s*\+|\+\s*18\b|\bhot\s*club\b|\bplayboy\b|\bhustler\b|\bbrazzers\b|\bredlight\b|\bdorcel\b)""")
    private val horrorWords = Regex("""(?i)(horror|horreur|épouvante|epouvante|\bgore\b|slasher|terreur|\bzombie)""")
    private val toughWords = Regex("""(?i)(thriller|crime|criminal|policier|guerre|\bwar\b|\bmafia\b)""")
    private val kidsWords = Regex("""(?i)(\bkids?\b|enfant|jeunesse|junior|cartoon|dessins?\s*anim|\bdisney|pixar|famil|\bbaby\b|\btoon)""")

    /** Rating guessed from the name and the group: 0 when nothing is clear. */
    fun ruleRating(name: String, group: String?): Int {
        val text = name + " " + group.orEmpty()
        return when {
            adultWords.containsMatchIn(text) -> ADULT
            horrorWords.containsMatchIn(text) -> 4
            group != null && kidsWords.containsMatchIn(group) -> 1
            toughWords.containsMatchIn(text) -> 3
            else -> 0
        }
    }

    // ------------------------------------------------------------------ decision

    fun ratingOf(code: String, key: String, name: String, group: String?): Int {
        val rule = ruleRating(name, group)
        if (rule == ADULT) return ADULT
        if (source == "rules") return rule
        val r = items[if (code == "series") "series|$key" else "movie|$key"]
        return if (r != null && r > 0) r else rule
    }

    /** True when this film / series is above the chosen level (or unknown and "hide them" is chosen). */
    fun blocked(code: String, key: String, name: String, group: String?): Boolean {
        val lv = level
        if (lv == 0) return false
        if (code == "live") return liveBlocked(key, name, group)
        if (code != "movie" && code != "series") return false
        val r = ratingOf(code, key, name, group)
        return if (r == 0) hideUnknown else r > lv
    }

    /** Live channels: hidden by name (adult always; horror below R), or only the groups you allow. */
    fun liveBlocked(key: String, name: String, group: String?, groupKey: String? = null): Boolean {
        val lv = level
        if (lv == 0) return false
        if (liveMode == "allow" && groupKey != null) return groupKey !in allowedGroups
        val text = name + " " + group.orEmpty()
        if (adultWords.containsMatchIn(text)) return true
        if (lv < 4 && horrorWords.containsMatchIn(text)) return true
        return false
    }

    /** Groups (categories): an adult group is hidden; with "only groups I allow", the others too. */
    fun groupBlocked(code: String, groupKey: String, groupName: String): Boolean {
        val lv = level
        if (lv == 0) return false
        if (code == "live" && liveMode == "allow") return groupKey !in allowedGroups
        if (adultWords.containsMatchIn(groupName)) return lv < ADULT
        if (code == "live" && lv < 4 && horrorWords.containsMatchIn(groupName)) return true
        return false
    }

    // ------------------------------------------------------------------ names

    private val tagPrefix = Regex("""^(\s*([|\[(][^|\])]{1,14}[|\])]|[A-Z]{2,5}\s*[-:|_]|[A-Z]{2,4}\s+-)\s*)+""")
    private val junk = Regex(
        """(?i)\b(4k|uhd|fhd|hd|sd|hq|1080p?|720p?|2160p?|480p|hevc|x264|x265|h\.?264|h\.?265|hdr|hdr10|10bit|web|webrip|web-dl|bluray|brrip|bdrip|dvdrip|remux|imax|3d|vf|vff|vfq|vfi|vo|vost|vostfr|multi|truefrench|french|subfrench|stfr|dual|ar|en|fr|eng|fre)\b"""
    )

    /** "FR_Le Roi Lion_4K (2019) VF" → ("le roi lion", 2019). */
    fun cleanName(raw: String, knownYear: Int?): Pair<String, Int?> {
        var s = raw.replace(tagPrefix, "")
        s = s.replace('_', ' ').replace('.', ' ')
        val year = knownYear ?: Regex("""\b(19[2-9]\d|20[0-4]\d)\b""").findAll(s).lastOrNull()?.value?.toIntOrNull()
        s = s.replace(Regex("""\[[^]]*]"""), " ").replace(Regex("""\(([^)]*)\)"""), " ")
        s = s.replace(Regex("""\b(19[2-9]\d|20[0-4]\d)\b"""), " ")
        s = s.replace(junk, " ")
        s = s.replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '|', ',')
        return s.lowercase() to year
    }

    // ------------------------------------------------------------------ TMDB

    private fun usMovie(cert: String): Int = when (cert.trim().uppercase()) {
        "G" -> 1; "PG" -> 2; "PG-13" -> 3; "R" -> 4; "NC-17" -> 5; "X", "XXX" -> ADULT
        else -> 0
    }

    private fun usTv(cert: String): Int = when (cert.trim().uppercase()) {
        "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G" -> 1; "TV-PG" -> 2; "TV-14" -> 3; "TV-MA" -> 4
        else -> 0
    }

    private class Tmdb(val http: OkHttpClient, val key: String) {
        fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject? {
            val bearer = key.length > 40
            val q = (query + if (bearer) emptyMap() else mapOf("api_key" to key))
                .entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }
            val b = Request.Builder().url("https://api.themoviedb.org/3$path" + (if (q.isEmpty()) "" else "?$q"))
            if (bearer) b.header("Authorization", "Bearer $key")
            b.header("Accept", "application/json")
            for (attempt in 0..2) {
                http.newCall(b.build()).execute().use { r ->
                    when {
                        r.code == 429 -> Thread.sleep(1500L * (attempt + 1))
                        r.code == 401 -> throw IllegalStateException("the TMDB key is not accepted")
                        r.code == 404 -> return null
                        !r.isSuccessful -> throw java.io.IOException("TMDB HTTP ${r.code}")
                        else -> return JSONObject(r.body?.string() ?: return null)
                    }
                }
            }
            return null
        }

        fun movieRating(id: String): Int {
            val o = get("/movie/$id/release_dates") ?: return -1
            val res = o.optJSONArray("results") ?: return 0
            for (i in 0 until res.length()) {
                val c = res.optJSONObject(i) ?: continue
                if (c.optString("iso_3166_1") != "US") continue
                val dates = c.optJSONArray("release_dates") ?: continue
                var best = 0
                for (j in 0 until dates.length()) {
                    val r = usMovie(dates.optJSONObject(j)?.optString("certification").orEmpty())
                    if (r > best) best = r
                }
                return best
            }
            return 0
        }

        fun tvRating(id: String): Int {
            val o = get("/tv/$id/content_ratings") ?: return -1
            val res = o.optJSONArray("results") ?: return 0
            for (i in 0 until res.length()) {
                val c = res.optJSONObject(i) ?: continue
                if (c.optString("iso_3166_1") == "US") return usTv(c.optString("rating"))
            }
            return 0
        }

        /** Finds the title by name (French or original) and year; returns its TMDB number, or "adult". */
        fun search(tv: Boolean, name: String, year: Int?): String? {
            if (name.length < 2) return null
            fun pick(withYear: Boolean, lang: String): String? {
                val q = mutableMapOf("query" to name, "language" to lang, "include_adult" to "true")
                if (withYear && year != null) q[if (tv) "first_air_date_year" else "year"] = year.toString()
                val res = get(if (tv) "/search/tv" else "/search/movie", q)?.optJSONArray("results") ?: return null
                if (res.length() == 0) return null
                var chosen: JSONObject? = null
                for (i in 0 until minOf(res.length(), 8)) {
                    val r = res.optJSONObject(i) ?: continue
                    val y = r.optString(if (tv) "first_air_date" else "release_date").take(4).toIntOrNull()
                    if (year == null || y == null || kotlin.math.abs(y - year) <= 1) { chosen = r; break }
                }
                val r = chosen ?: return null
                if (r.optBoolean("adult")) return "adult"
                return r.optLong("id").takeIf { it > 0 }?.toString()
            }
            return pick(true, "fr-FR") ?: pick(true, "en-US") ?: if (year != null) pick(false, "fr-FR") else null
        }
    }

    private fun file(context: Context) = File(Folders.keepDir(context, "Index").also { it.mkdirs() }, "ages.gz")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = file(context)
            if (!f.exists()) return
            GZIPInputStream(f.inputStream().buffered()).bufferedReader().useLines { lines ->
                lines.forEach { l ->
                    val p = l.split('\t')
                    when (p[0]) {
                        "AGES1" -> checkedAt = p.getOrNull(1)?.toLongOrNull() ?: 0
                        "I" -> if (p.size >= 3) items[p[1]] = p[2].toIntOrNull() ?: 0
                        "A" -> if (p.size >= 3) answers[p[1]] = p[2].toIntOrNull() ?: 0
                    }
                }
            }
            Log.i(TAG, "AGES loaded ${items.size} titles")
            bump()
        }.onFailure { Log.i(TAG, "AGES unreadable: $it") }
    }

    private fun save(context: Context) {
        runCatching {
            val f = file(context)
            val tmp = File(f.path + ".tmp")
            GZIPOutputStream(tmp.outputStream().buffered()).bufferedWriter().use { w ->
                w.write("AGES1\t$checkedAt\n")
                answers.forEach { (k, v) -> w.write("A\t${k.replace('\t', ' ')}\t$v\n") }
                items.forEach { (k, v) -> w.write("I\t$k\t$v\n") }
            }
            f.delete(); tmp.renameTo(f)
        }
    }

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
    }

    /** Count shown in Settings: "5 230 checked · 1 146 above PG hidden". */
    fun summary(accounts: List<Account>): String {
        if (level == 0) return "Age filter is OFF"
        var total = 0; var hidden = 0; var unknown = 0
        accounts.forEach { a ->
            val idx = SearchIndex.get(a.id) ?: return@forEach
            idx.movies.forEach { m -> total++; val r = ratingOf("movie", m.key, m.name, null); if (r == 0) unknown++; if (blocked("movie", m.key, m.name, null)) hidden++ }
            idx.series.forEach { s -> total++; val r = ratingOf("series", s.key, s.name, null); if (r == 0) unknown++; if (blocked("series", s.key, s.name, null)) hidden++ }
        }
        if (total == 0) return "The search index is not ready yet — the age check starts after it."
        val nf = java.text.NumberFormat.getIntegerInstance()
        return "✓ ${nf.format(total)} films & series · ${nf.format(hidden)} hidden (above ${LEVELS[level]}" +
            (if (hideUnknown) " or no age found" else "") + ") · ${nf.format(unknown)} without age" +
            (if (source == "tmdb" && checkedAt > 0) " · checked ${ago(checkedAt)}" else "")
    }

    /** Looks up the US rating of every film and series of the search index that is not known yet. */
    suspend fun check(context: Context, http: OkHttpClient, accounts: List<Account>) = withContext(Dispatchers.IO) {
        load(context)
        if (level == 0 || source != "tmdb") { status.value = summary(accounts); return@withContext }
        val key = tmdbKey
        if (key.isBlank()) { status.value = "Add your free TMDB key to check the ages."; return@withContext }
        if (busy.value) return@withContext
        busy.value = true
        val api = Tmdb(http, key)
        class Job(val code: String, val key: String, val name: String, val year: Int?, val tmdb: String?)
        val todo = ArrayList<Job>()
        accounts.forEach { a ->
            val idx = SearchIndex.get(a.id) ?: return@forEach
            idx.movies.forEach { m -> if (!items.containsKey("movie|${m.key}")) todo += Job("movie", m.key, m.name, m.year, m.tmdb) }
            idx.series.forEach { s -> if (!items.containsKey("series|${s.key}")) todo += Job("series", s.key, s.name, s.year, s.tmdb) }
        }
        Log.i(TAG, "AGES to check: ${todo.size}")
        if (todo.isEmpty()) { busy.value = false; status.value = summary(accounts); return@withContext }
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = Semaphore(4)
        val nf = java.text.NumberFormat.getIntegerInstance()
        try {
            coroutineScope {
                todo.chunked(200).forEach { chunk ->
                    chunk.map { j ->
                        async {
                            gate.withPermit {
                                val tv = j.code == "series"
                                val r = try {
                                    var rating = -1
                                    if (!j.tmdb.isNullOrBlank() && j.tmdb.all(Char::isDigit) && j.tmdb != "0") {
                                        val ak = (if (tv) "t:" else "m:") + j.tmdb
                                        rating = answers[ak] ?: (if (tv) api.tvRating(j.tmdb) else api.movieRating(j.tmdb)).also { if (it >= 0) answers[ak] = it }
                                    }
                                    if (rating < 0) {
                                        val (clean, year) = cleanName(j.name, j.year)
                                        val nk = (if (tv) "tn:" else "mn:") + clean + "|" + (year ?: "")
                                        rating = answers[nk] ?: run {
                                            val found = api.search(tv, clean, year)
                                            when (found) {
                                                null -> 0
                                                "adult" -> ADULT
                                                else -> (if (tv) api.tvRating(found) else api.movieRating(found)).coerceAtLeast(0)
                                            }
                                        }.also { answers[nk] = it }
                                    }
                                    rating.coerceAtLeast(0)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: IllegalStateException) {
                                    throw e
                                } catch (e: Exception) {
                                    null // network problem: try again next time
                                }
                                if (r != null) items["${j.code}|${j.key}"] = r
                                val n = done.incrementAndGet()
                                if (n % 25 == 0) status.value = "Checking ages… ${nf.format(n)} / ${nf.format(todo.size)}"
                            }
                        }
                    }.awaitAll()
                    checkedAt = System.currentTimeMillis()
                    save(context)
                    bump()
                }
            }
            status.value = summary(accounts)
            Log.i(TAG, "AGES checked ${done.get()}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            save(context)
            status.value = "Age check stopped: ${e.message}"
            Log.i(TAG, "AGES failed: $e")
        } finally {
            busy.value = false
            bump()
        }
    }

    /** Forget the saved answers (e.g. after changing the key). */
    fun forget(context: Context) {
        items.clear(); answers.clear(); checkedAt = 0
        runCatching { file(context).delete() }
        bump()
    }
}
