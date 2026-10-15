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
    @Volatile var level = 5; private set
    @Volatile var source = "rules"; private set
    @Volatile var hideUnknown = false; private set
    @Volatile var liveMode = "name"; private set
    @Volatile var allowedGroups: Set<String> = emptySet(); private set
    @Volatile private var tmdbKey = ""
    /** Titles allowed anyway (exceptions): "movie|acc|id" … */
    @Volatile var exceptions: Set<String> = emptySet(); private set
    /** Word groups switched on, with their words. */
    @Volatile private var activeWords: List<Pair<WordGroup, Regex>> = emptyList()

    /** One of the two filters (age, words) is on. */
    val active: Boolean get() = level != 0 || activeWords.isNotEmpty()

    /** The key used: yours, or the one built into Nova. */
    val defaultKey: String get() = com.nova.iptv.BuildConfig.TMDB_KEY
    fun effectiveKey(): String = tmdbKey.ifBlank { defaultKey }

    // ---- TMDB diagnosis of the last check ----
    val found = java.util.concurrent.atomic.AtomicInteger(0)
    val notFound = java.util.concurrent.atomic.AtomicInteger(0)
    val failed = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var lastError: String? = null
    val diagnosis = MutableStateFlow("")

    // ------------------------------------------------------------------ words

    class WordGroup(val id: String, val title: String, val defaultOn: Boolean, val defaults: List<String>)

    val WORD_GROUPS = listOf(
        WordGroup("romance", "💋 Romance & kissing", true, listOf(
            "kiss", "kisses", "kissing", "love", "lover", "lovers", "romance", "romantic", "passion", "desire", "seduction", "seduce",
            "temptation", "flirt", "crush", "valentine", "honeymoon", "affair", "mistress", "unfaithful", "adultery",
            "amour", "amours", "amoureux", "amoureuse", "amant", "amante", "baiser", "bisou", "désir", "séduction", "séducteur",
            "tentation", "passionnément", "saint-valentin", "lune de miel", "liaison", "maîtresse", "infidèle", "adultère", "coup de foudre",
        )),
        WordGroup("adult", "🔞 Sexual & adult", true, listOf(
            "sex", "sexy", "sexual", "nude", "naked", "erotic", "lust", "sensual", "seductive", "stripper", "striptease", "escort",
            "call girl", "gigolo", "prostitute", "lingerie", "fetish", "bdsm", "kamasutra", "fifty shades", "playboy", "xxx", "porn",
            "18+", "uncensored", "unrated", "sexe", "sexuel", "sexuelle", "nu", "nue", "nues", "érotique", "sensuel", "sensuelle",
            "désirs", "prostituée", "fétiche", "cinquante nuances", "interdit aux mineurs", "non censuré", "-18",
        )),
        WordGroup("horror", "🩸 Horror & violence", false, listOf(
            "horror", "blood", "bloody", "killer", "kill", "massacre", "slasher", "gore", "zombie", "demon", "devil", "exorcist",
            "possession", "haunted", "evil", "cannibal", "chainsaw", "psycho", "nightmare", "horreur", "sang", "sanglant", "tueur",
            "tueuse", "démon", "diable", "exorciste", "hanté", "hantée", "maléfique", "cannibale", "cauchemar", "terreur", "épouvante",
        )),
        WordGroup("drugs", "💊 Drugs & crime", false, listOf(
            "drug", "drugs", "cocaine", "heroin", "narcos", "cartel", "gangster", "mafia", "drogue", "cocaïne", "héroïne", "gang", "braquage",
        )),
        WordGroup("own", "✏️ My own words", true, emptyList()),
    )

    /** Lower case, no accents, "_" and "." as spaces: "FR_Amoür.Fou" → "fr amour fou". */
    // Text patterns are made once (making them again for every title used up the memory and closed Nova).
    private val MARKS = Regex("""\p{Mn}+""")
    private val YEAR = Regex("""\b(19[2-9]\d|20[0-4]\d)\b""")
    private val BRACKETS = Regex("""\[[^]]*]""")
    private val PARENS = Regex("""\(([^)]*)\)""")
    private val SPACES = Regex("""\s+""")
    private val patternCache = ConcurrentHashMap<List<String>, Regex>()

    fun normalize(text: String): String {
        var t = text.lowercase()
        if (t.any { it.code > 127 }) t = MARKS.replace(java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD), "")
        return t.replace('_', ' ').replace('.', ' ').replace('’', '\'')
    }

    /** One word list → one pattern (whole words, with plurals: love → loves). */
    fun wordsPattern(words: List<String>): Regex? {
        patternCache[words]?.let { return it }
        return wordsPatternNow(words)?.also { if (patternCache.size > 50) patternCache.clear(); patternCache[words] = it }
    }

    private fun wordsPatternNow(words: List<String>): Regex? {
        val parts = words.map { normalize(it).trim() }.filter { it.isNotEmpty() }.distinct()
            .map { Regex.escape(it).replace(" ", """\E\s+\Q""") }
        if (parts.isEmpty()) return null
        return Regex("""(?<![\p{L}\p{N}])(""" + parts.joinToString("|") + """)(s|es|x)?(?![\p{L}\p{N}])""")
    }

    fun wordsOf(s: Storage, g: WordGroup): List<String> = s.ageWords(g.id) ?: g.defaults
    fun groupOn(s: Storage, g: WordGroup): Boolean = s.ageWordGroupsOn?.contains(g.id) ?: g.defaultOn

    /** The first word of the switched-on groups found in the name, or null. */
    fun wordIn(name: String): String? {
        val words = activeWords
        if (words.isEmpty()) return null
        wordCache[name]?.let { return it.ifEmpty { null } }
        val n = normalize(name)
        var found: String? = null
        for ((_, re) in words) { re.find(n)?.let { found = it.groupValues[1] }; if (found != null) break }
        if (wordCache.size < 200_000) wordCache[name] = found.orEmpty()
        return found
    }

    /** How many titles of the index these words would hide, with 2 examples (for "Add a word" and the group counts). */
    fun countWord(word: String, accounts: List<Account>): Pair<Int, List<String>> = countWords(listOf(word), accounts)

    fun countWords(list: List<String>, accounts: List<Account>): Pair<Int, List<String>> {
        val re = wordsPattern(list) ?: return 0 to emptyList()
        var n = 0
        val examples = ArrayList<String>()
        accounts.forEach { a ->
            val idx = SearchIndex.get(a.id) ?: return@forEach
            (idx.movies.map { it.name } + idx.series.map { it.name }).forEach { t ->
                if (re.containsMatchIn(normalize(t))) { n++; if (examples.size < 2) examples += t }
            }
        }
        return n to examples
    }

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
        val ex = s.ageAllowed
        val words = if (!s.ageWordsOn) emptyList()
            else WORD_GROUPS.filter { groupOn(s, it) }.mapNotNull { g -> wordsPattern(wordsOf(s, g))?.let { g to it } }
        val wordsChanged = words.map { it.second.pattern } != activeWords.map { it.second.pattern }
        val exChanged = ex != exceptions
        exceptions = ex
        if (wordsChanged) wordCache.clear()
        activeWords = words
        if (changed || wordsChanged || exChanged) bump()
    }

    private fun bump() {
        reasonCache.clear()
        version.value = version.value + 1
    }

    /** Results already worked out (lists are redrawn often): cleared whenever something changes. */
    private val reasonCache = ConcurrentHashMap<String, String>()
    private val ruleCache = ConcurrentHashMap<String, Int>()
    private val wordCache = ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------------ rules

    private val adultWords = Regex("""(?i)(\bxxx\b|\badult(e|es|s)?\b|\bporn\w*|\berotic\w*|érotique|\berotique\b|\bsexy\b|\b18\s*\+|\+\s*18\b|\bhot\s*club\b|\bplayboy\b|\bhustler\b|\bbrazzers\b|\bredlight\b|\bdorcel\b)""")
    private val horrorWords = Regex("""(?i)(horror|horreur|épouvante|epouvante|\bgore\b|slasher|terreur|\bzombie)""")
    private val toughWords = Regex("""(?i)(thriller|crime|criminal|policier|guerre|\bwar\b|\bmafia\b)""")
    private val kidsWords = Regex("""(?i)(\bkids?\b|enfant|jeunesse|junior|cartoon|dessins?\s*anim|\bdisney|pixar|famil|\bbaby\b|\btoon)""")

    /** Rating guessed from the name and the group: 0 when nothing is clear. */
    fun ruleRating(name: String, group: String?): Int {
        val text = name + " " + group.orEmpty()
        ruleCache[text]?.let { return it }
        return ruleRatingNow(text, group).also { if (ruleCache.size < 200_000) ruleCache[text] = it }
    }

    private fun ruleRatingNow(text: String, group: String?): Int {
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

    /** Why this film / series is hidden ("R", "no age found", "word: kiss"…), or null when it is shown. Exceptions not counted. */
    fun reason(code: String, key: String, name: String, group: String?): String? {
        val lv = level
        if (lv == 0 && activeWords.isEmpty()) return null
        if (code != "movie" && code != "series") return null
        val ck = "$code|$key|${group.orEmpty()}"
        reasonCache[ck]?.let { return it.ifEmpty { null } }
        return reasonNow(code, key, name, group, lv).also { if (reasonCache.size < 300_000) reasonCache[ck] = it.orEmpty() }
    }

    private fun reasonNow(code: String, key: String, name: String, group: String?, lv: Int): String? {
        // Age filter OFF: only the word filter (a separate filter).
        if (lv == 0) return wordIn(name)?.let { "word: $it" }
        val rule = ruleRating(name, group)
        val r = ratingOf(code, key, name, group)
        val fromRule = r == rule && (source == "rules" || items[if (code == "series") "series|$key" else "movie|$key"].let { it == null || it == 0 })
        if (r > lv) return ratingLabel(r) + if (fromRule) " · name rule" else ""
        wordIn(name)?.let { return "word: $it" }
        if (r == 0 && hideUnknown) return "no age found"
        return null
    }

    fun ratingLabel(r: Int): String = if (r >= ADULT) "18+" else LEVELS.getOrElse(r) { "?" }

    /** True when this film / series is hidden (above the level, a word, or unknown with "hide them"), unless allowed anyway. */
    fun blocked(code: String, key: String, name: String, group: String?): Boolean {
        if (level == 0 && activeWords.isEmpty()) return false
        if (code == "live") return liveBlocked(key, name, group)
        if ("$code|$key" in exceptions) return false
        return reason(code, key, name, group) != null
    }

    /** Live channels: hidden by name (adult always; horror below R), or only the groups you allow. */
    fun liveBlocked(key: String, name: String, group: String?, groupKey: String? = null): Boolean =
        level != 0 && "live|$key" !in exceptions && liveReason(name, group, groupKey) != null

    fun liveReason(name: String, group: String?, groupKey: String? = null): String? {
        val lv = level
        if (lv == 0) return null
        if (liveMode == "allow" && groupKey != null) return if (groupKey !in allowedGroups) "group not allowed" else null
        val text = name + " " + group.orEmpty()
        if (adultWords.containsMatchIn(text)) return "name rule: adult"
        if (lv < 4 && horrorWords.containsMatchIn(text)) return "name rule: horror"
        return null
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
        val year = knownYear ?: YEAR.findAll(s).lastOrNull()?.value?.toIntOrNull()
        s = s.replace(BRACKETS, " ").replace(PARENS, " ")
        s = s.replace(YEAR, " ")
        s = s.replace(junk, " ")
        s = s.replace(SPACES, " ").trim(' ', '-', ':', '|', ',')
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

    /** Download/NovaIPTV/Filter: the ratings found, the filter settings and the list of what is hidden. */
    fun folder(context: Context): File = Folders.keepDir(context, "Filter").also { it.mkdirs() }

    private fun file(context: Context): File {
        val f = File(folder(context), "ratings.gz")
        if (!f.exists()) runCatching {
            val old = File(Folders.keepDir(context, "Index"), "ages.gz")
            if (old.exists()) old.copyTo(f, overwrite = true).also { old.delete() }
        }
        return f
    }

    /** Saves the filter settings (sealed) next to the ratings. */
    fun saveSettings(context: Context, s: Storage) {
        runCatching { File(folder(context), "filter-settings.txt").writeText(s.exportFilter()) }
    }

    /** New install / cleared data: takes the filter back from the folder (only if nobody changed the file by hand). */
    fun restoreSettings(context: Context, s: Storage): Boolean = runCatching {
        if (!s.filterUnset) return false
        val f = File(folder(context), "filter-settings.txt")
        f.exists() && s.importFilter(f.readText()).also { Log.i(TAG, "FILTER settings restored: $it") }
    }.getOrDefault(false)

    /** hidden-list.txt: every hidden film, series and channel with the reason, easy to read. */
    fun writeHiddenList(context: Context, accounts: List<Account>, names: Map<String, String>) {
        runCatching {
            val lists = hiddenList(accounts, names)
            val stamp = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val accName = accounts.associate { it.id to it.name }
            val sb = StringBuilder("Nova IPTV — hidden by the age filter (${LEVELS.getOrElse(level) { "" }}) · $stamp\n")
            sb.append("☑ hidden · ☐ allowed anyway (exception)\n")
            listOf("movie" to "FILMS", "series" to "SERIES", "live" to "CHANNELS").forEach { (k, title) ->
                val rows = lists[k].orEmpty()
                sb.append("\n==== $title (${rows.size}) ====\n")
                rows.forEach { r ->
                    sb.append(if (r.exKey in exceptions) "☐ " else "☑ ").append(r.name).append("  —  ").append(r.reason)
                    if (accounts.size > 1) sb.append("  ·  ").append(accName[r.accountId].orEmpty())
                    sb.append('\n')
                }
            }
            File(folder(context), "hidden-list.txt").writeText(sb.toString())
        }
    }

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

    private val nf0 = java.text.NumberFormat.getIntegerInstance()

    private fun ago(t: Long): String {
        val m = (System.currentTimeMillis() - t) / 60_000
        return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
    }

    /** Count shown in Settings: "5 230 checked · 1 146 above PG hidden". */
    class HiddenRow(val code: String, val key: String, val name: String, val accountId: String, val reason: String) {
        val exKey: String get() = "$code|$key"
    }

    /** Everything the filter hides (exceptions included, so they can be ticked again): films, series, channels. */
    fun hiddenList(accounts: List<Account>, groupNames: Map<String, String>): Map<String, List<HiddenRow>> {
        val films = ArrayList<HiddenRow>(); val series = ArrayList<HiddenRow>(); val live = ArrayList<HiddenRow>()
        if (level == 0 && activeWords.isEmpty()) return mapOf("movie" to films, "series" to series, "live" to live)
        accounts.forEach { a ->
            val idx = SearchIndex.get(a.id) ?: return@forEach
            idx.movies.forEach { m ->
                val g = groupNames["movie|${a.id}|${m.categoryId}"]
                reason("movie", m.key, m.name, g)?.let { films += HiddenRow("movie", m.key, m.name, a.id, it) }
            }
            idx.series.forEach { x ->
                val g = groupNames["series|${a.id}|${x.categoryId}"]
                reason("series", x.key, x.name, g)?.let { series += HiddenRow("series", x.key, x.name, a.id, it) }
            }
            idx.live.forEach { c ->
                val gk = if (c.categoryId.isBlank()) null else "${a.id}|${c.categoryId}"
                val g = gk?.let { groupNames["live|$it"] }
                liveReason(c.name, g, gk)?.let { live += HiddenRow("live", c.key, c.name, a.id, it) }
            }
        }
        val byName = compareBy<HiddenRow> { normalize(it.name) }
        return mapOf("movie" to films.sortedWith(byName), "series" to series.sortedWith(byName), "live" to live.sortedWith(byName))
    }

    fun summary(accounts: List<Account>): String {
        if (level == 0 && activeWords.isEmpty()) return "Age filter OFF · word filter OFF — nothing hidden by the filters"
        var total = 0; var byAge = 0; var byWords = 0; var unknown = 0
        fun count(code: String, key: String, name: String) {
            total++
            if (level != 0 && ratingOf(code, key, name, null) == 0) unknown++
            if ("$code|$key" in exceptions) return
            val r = reason(code, key, name, null) ?: return
            if (r.startsWith("word:")) byWords++ else byAge++
        }
        accounts.forEach { a ->
            val idx = SearchIndex.get(a.id) ?: return@forEach
            idx.movies.forEach { m -> count("movie", m.key, m.name) }
            idx.series.forEach { s -> count("series", s.key, s.name) }
        }
        if (total == 0) return "The search index is not ready yet — the filters start after it."
        val nf = java.text.NumberFormat.getIntegerInstance()
        val age = if (level == 0) "Age filter OFF" else "🔞 Hidden by age: ${nf.format(byAge)} (above ${LEVELS[level]}" +
            (if (hideUnknown) " or no age found" else "") + ") · ${nf.format(unknown)} without age" +
            (if (source == "tmdb" && checkedAt > 0) " · checked ${ago(checkedAt)}" else "")
        val words = if (activeWords.isEmpty()) "word filter OFF" else "🔤 hidden by words: ${nf.format(byWords)}"
        return "✓ ${nf.format(total)} films & series · $age · $words"
    }

    /** Looks up the US rating of every film and series of the search index that is not known yet. */
    suspend fun check(context: Context, http: OkHttpClient, accounts: List<Account>) = withContext(Dispatchers.IO) {
        load(context)
        if (level == 0 || source != "tmdb") { status.value = summary(accounts); return@withContext }
        val key = effectiveKey()
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
        val gate = Semaphore(6)
        var lastBump = System.currentTimeMillis()
        found.set(0); notFound.set(0); failed.set(0); lastError = null
        fun diag() {
            diagnosis.value = "🔎 TMDB: ${nf0.format(found.get())} found · ${nf0.format(notFound.get())} not found · ${nf0.format(failed.get())} failed" +
                (if (failed.get() > 0 && lastError != null) " — $lastError" else "") +
                (if (failed.get() > (found.get() + notFound.get()) && failed.get() > 20)
                    " (your internet may block themoviedb.org: try the VPN in Settings › 1 Account)" else "")
        }
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
                                    lastError = when (e) {
                                        is java.net.UnknownHostException -> "no connection to TMDB (name not found)"
                                        is java.net.SocketTimeoutException -> "no answer from TMDB (timeout)"
                                        is javax.net.ssl.SSLException -> "secure connection to TMDB refused"
                                        is java.net.ConnectException -> "no connection to TMDB"
                                        else -> e.message ?: e.javaClass.simpleName
                                    }
                                    null // network problem: try again next time
                                }
                                when {
                                    r == null -> failed.incrementAndGet()
                                    r > 0 -> found.incrementAndGet()
                                    else -> notFound.incrementAndGet()
                                }
                                if (r != null) items["${j.code}|${j.key}"] = r
                                val n = done.incrementAndGet()
                                if (n % 25 == 0) diag()
                                if (n % 25 == 0) status.value = "Checking ages… ${nf.format(n)} / ${nf.format(todo.size)}"
                            }
                        }
                    }.awaitAll()
                    checkedAt = System.currentTimeMillis()
                    // Lists are redrawn at most every 20 s while checking (redrawing costs time on a TV box).
                    if (System.currentTimeMillis() - lastBump > 20_000) {
                        save(context)
                        bump()
                        lastBump = System.currentTimeMillis()
                    }
                }
            }
            diag()
            status.value = summary(accounts)
            Log.i(TAG, "AGES checked ${done.get()}: ${diagnosis.value}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            runCatching { save(context) }
            diag()
            status.value = "Age check stopped: ${e.message ?: e.javaClass.simpleName}"
            Log.i(TAG, "AGES failed: $e")
        } finally {
            runCatching { save(context) }
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
