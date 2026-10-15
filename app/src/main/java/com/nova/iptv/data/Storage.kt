package com.nova.iptv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Accounts, settings, favorites, history and resume positions, kept in SharedPreferences. */
class Storage(context: Context) {

    private val prefs = context.getSharedPreferences("nova_iptv", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val accountListSer = ListSerializer(Account.serializer())
    private val savedListSer = ListSerializer(SavedItem.serializer())

    // ---------- accounts ----------

    var accounts: List<Account>
        get() = prefs.getString("accounts", null)
            ?.let { runCatching { json.decodeFromString(accountListSer, it) }.getOrNull() }
            ?: emptyList()
        set(value) = prefs.edit().putString("accounts", json.encodeToString(accountListSer, value)).apply()

    var activeAccountId: String?
        get() = prefs.getString("active_account", null)
        set(value) = prefs.edit().putString("active_account", value).apply()

    fun activeAccount(): Account? {
        val id = activeAccountId ?: return null
        return accounts.firstOrNull { it.id == id }
    }

    // ---------- settings ----------

    /** "ts" (MPEG-TS) or "m3u8" (HLS) for live channels. */
    var liveFormat: String
        get() = prefs.getString("live_format", "ts") ?: "ts"
        set(value) = prefs.edit().putString("live_format", value).apply()

    /** Seconds of video collected before live playback starts (the safety cushion). */
    var cushionSeconds: Int
        get() = prefs.getInt("cushion_seconds", 15)
        set(value) = prefs.edit().putInt("cushion_seconds", value).apply()

    /** Maximum seconds held ahead of the playback position. */
    var maxBufferSeconds: Int
        get() = prefs.getInt("max_buffer_seconds", 60)
        set(value) = prefs.edit().putInt("max_buffer_seconds", value).apply()

    /** Disk space for the rolling movie / episode cache, in MB. */
    var vodCacheMb: Int
        get() = prefs.getInt("vod_cache_mb", 1024)
        set(value) = prefs.edit().putInt("vod_cache_mb", value).apply()

    var userAgent: String
        get() = prefs.getString("user_agent", DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT
        set(value) = prefs.edit().putString("user_agent", value.ifBlank { DEFAULT_USER_AGENT }).apply()

    /** Keep rolling buffers / caches in Download/NovaIPTV instead of the app's hidden folder. */
    var usePublicFolder: Boolean
        get() = prefs.getBoolean("use_public_folder", false)
        set(value) = prefs.edit().putBoolean("use_public_folder", value).apply()

    /** Live TV buffer on disk (allows pause and rewind of live TV) instead of memory only. */
    var liveDiskBuffer: Boolean
        get() = prefs.getBoolean("live_disk_buffer", true)
        set(value) = prefs.edit().putBoolean("live_disk_buffer", value).apply()

    /**
     * Live disk buffer length in minutes. The play point stays in the middle: what you watched (for rewind)
     * is kept as long as the cushion ahead of you, everything older is deleted.
     */
    var liveDiskMinutes: Int
        get() = prefs.getInt("live_disk_minutes", 15)
        set(value) = prefs.edit().putInt("live_disk_minutes", value).apply()

    /** Disk space for the rolling live buffer, in MB (earlier versions; now only a safety limit). */
    var liveDiskMb: Int
        get() = prefs.getInt("live_disk_mb", 600)
        set(value) = prefs.edit().putInt("live_disk_mb", value).apply()

    /** Live TV: while watching, play 4 % slower until you are this many seconds behind live (0 = off). */
    var liveGrowTarget: Int
        get() = prefs.getInt("live_grow_target", 120)
        set(value) = prefs.edit().putInt("live_grow_target", value).apply()

    /** Search index: also save index-latest.novaindex in Download/NovaIPTV/Index after every update. */
    var autoExportIndex: Boolean
        get() = prefs.getBoolean("auto_export_index", true)
        set(value) = prefs.edit().putBoolean("auto_export_index", value).apply()

    /** The Index folder chosen with Android's folder picker (permission kept), or null. */
    var indexTreeUri: String?
        get() = prefs.getString("index_tree_uri", null)
        set(value) = prefs.edit().putString("index_tree_uri", value).apply()
    /** What the last export held (server + update time), to skip writing the same again. */
    var indexExportStamp: String
        get() = prefs.getString("index_export_stamp", "") ?: ""
        set(value) = prefs.edit().putString("index_export_stamp", value).apply()
    /** The "Import existing index?" offer was answered. */
    var indexOfferDone: Boolean
        get() = prefs.getBoolean("index_offer_done", false)
        set(value) = prefs.edit().putBoolean("index_offer_done", value).apply()

    /** When the search index was last exported (0 = never). */
    var indexExportedAt: Long
        get() = prefs.getLong("index_exported_at", 0L)
        set(value) = prefs.edit().putLong("index_exported_at", value).apply()

    /** Live TV: when the line stays slower than the channel needs, switch to the same channel on your other server. */
    var liveSwitchWhenSlow: Boolean
        get() = liveSlowAction == "switch"
        set(value) { liveSlowAction = if (value) "switch" else "stay" }

    /** When the line is too slow for a channel: "switch" (other server), "similar" (popup, same server) or "stay". */
    var liveSlowAction: String
        get() = prefs.getString("live_slow_action", null)
            ?: if (prefs.contains("live_switch_slow")) (if (prefs.getBoolean("live_switch_slow", false)) "switch" else "stay") else "similar"
        set(value) = prefs.edit().putString("live_slow_action", value).apply()

    /** Jump to the next channel automatically when a live channel doesn't work. */
    /** Status dots on channels, films and episodes (quiet checks in the background). */
    /** Word filter (hide films & series by words in the name): its own switch, independent of the age level. */
    var ageWordsOn: Boolean
        get() = prefs.getBoolean("age_words_on", true)
        set(value) = prefs.edit().putBoolean("age_words_on", value).apply()

    var statusDots: Boolean
        get() = prefs.getBoolean("status_dots", true)
        set(value) = prefs.edit().putBoolean("status_dots", value).apply()

    var autoSkip: Boolean
        get() = prefs.getBoolean("auto_skip", true)
        set(value) = prefs.edit().putBoolean("auto_skip", value).apply()

    /** Seconds a channel may take to start before it counts as "not working". */
    var skipTimeoutSec: Int
        get() = prefs.getInt("skip_timeout", 8)
        set(value) = prefs.edit().putInt("skip_timeout", value).apply()

    /** Folder used for downloads, recordings and buffers (e.g. on a USB drive). Null = Download/NovaIPTV. */
    var storageRoot: String?
        get() = prefs.getString("storage_root", null)
        set(value) = prefs.edit().putString("storage_root", value).apply()

    /** Tunneled playback: video and audio go through the chip's hardware path together (Amlogic boxes). */
    var tunneling: Boolean
        get() = prefs.getBoolean("tunneling", false)
        set(value) = prefs.edit().putBoolean("tunneling", value).apply()

    /** Clock on screen: "off", "tl" (top-left), "tr", "bl", "br". */
    var clockPosition: String
        get() = prefs.getString("clock_position", "bl") ?: "bl"
        set(value) = prefs.edit().putString("clock_position", value).apply()

    /** Phones: keep the group list on the left side (like on TV) instead of behind a button. */
    var groupsAlwaysLeft: Boolean
        get() = prefs.getBoolean("groups_always_left", false)
        set(value) = prefs.edit().putBoolean("groups_always_left", value).apply()

    /** Open a group's channels as soon as it is highlighted with the remote (no OK needed). */
    var openGroupOnFocus: Boolean
        get() = prefs.getBoolean("open_group_on_focus", true)
        set(value) = prefs.edit().putBoolean("open_group_on_focus", value).apply()

    /** Sort order per zone ("live", "movie", "series"): custom, added, year, name, rating, number. */
    fun sortFor(zone: String): String = prefs.getString("sort_$zone", if (zone == "movie") "rating" else "custom")
        ?: "custom"
    fun setSortFor(zone: String, value: String) = prefs.edit().putString("sort_$zone", value).apply()

    /** Poster columns for films / series: 0 = automatic. Separate values for phones and big screens. */
    var posterColumnsPhone: Int
        get() = prefs.getInt("poster_cols_phone", 0)
        set(value) = prefs.edit().putInt("poster_cols_phone", value).apply()

    var posterColumnsTv: Int
        get() = prefs.getInt("poster_cols_tv", 0)
        set(value) = prefs.edit().putInt("poster_cols_tv", value).apply()

    /** "small", "medium", "large" (used when the columns are automatic). */
    var posterSize: String
        get() = prefs.getString("poster_size", "medium") ?: "medium"
        set(value) = prefs.edit().putString("poster_size", value).apply()

    var posterTitles: Boolean
        get() = prefs.getBoolean("poster_titles", true)
        set(value) = prefs.edit().putBoolean("poster_titles", value).apply()

    /** Live TV rows: "compact", "normal", "large". */
    var liveRowSize: String
        get() = prefs.getString("live_row_size", "normal") ?: "normal"
        set(value) = prefs.edit().putString("live_row_size", value).apply()

    var liveLogos: Boolean
        get() = prefs.getBoolean("live_logos", true)
        set(value) = prefs.edit().putBoolean("live_logos", value).apply()

    /** Show the programme now on air under each channel name (asks the guide for each visible channel). */
    var liveNowLine: Boolean
        get() = prefs.getBoolean("live_now_line", false)
        set(value) = prefs.edit().putBoolean("live_now_line", value).apply()

    /** Channel list columns on big screens: 1 or 2. */
    var liveColumns: Int
        get() = prefs.getInt("live_columns", 1)
        set(value) = prefs.edit().putInt("live_columns", value).apply()

    /** Disk read-ahead for films & series in MB (0 = off): one connection fills segments ahead of the player. */
    var readAheadMb: Int
        get() = prefs.getInt("read_ahead_mb", 200)
        set(value) = prefs.edit().putInt("read_ahead_mb", value).apply()

    /** How see-through the overlays over live TV are (group list, recent bar), in %. */
    var overlayTransparency: Int
        get() = prefs.getInt("overlay_transparency", 25)
        set(value) = prefs.edit().putInt("overlay_transparency", value).apply()

    /** Storage locations per kind of file (JSON, see Locations). Null = defaults. */
    var locationsJson: String?
        get() = prefs.getString("locations_v1", null)
        set(value) = prefs.edit().putString("locations_v1", value).apply()

    /** Your names for drives: drive id -> name. */
    var driveNames: Map<String, String>
        get() = prefs.getString("drive_names", null)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                o.keys().asSequence().associateWith { o.getString(it) }
            }.getOrNull()
        }.orEmpty()
        set(value) = prefs.edit().putString("drive_names", JSONObject(value).toString()).apply()

    /** USB / SD drives in the order to use them (drive ids). */
    var driveOrder: List<String>
        get() = prefs.getString("drive_order", null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit().putString("drive_order", value.joinToString(",")).apply()

    /** Series downloads: "flat", "show" or "show_season". */
    var seriesLayout: String
        get() = prefs.getString("series_layout", "show_season") ?: "show_season"
        set(value) = prefs.edit().putString("series_layout", value).apply()

    /** Film downloads: "flat" or "folder" (one folder per film). */
    var filmLayout: String
        get() = prefs.getString("film_layout", "flat") ?: "flat"
        set(value) = prefs.edit().putString("film_layout", value).apply()

    /** Recordings: "flat" or "channel" (one folder per channel). */
    var recordingLayout: String
        get() = prefs.getString("recording_layout", "channel") ?: "channel"
        set(value) = prefs.edit().putString("recording_layout", value).apply()

    /** Live TV: ▲ = next channel, ▼ = previous (TV style). False = list style (▼ = next). */
    var tvArrows: Boolean
        get() = prefs.getBoolean("tv_arrows", true)
        set(value) = prefs.edit().putBoolean("tv_arrows", value).apply()

    /** Stream watchdog: notices a live channel that stops / freezes and reconnects by itself. */
    var watchdog: Boolean
        get() = prefs.getBoolean("watchdog", true)
        set(value) = prefs.edit().putBoolean("watchdog", value).apply()

    /** Seconds without progress before the watchdog acts (5 fast, 8 normal, 15 relaxed). */
    var watchdogSecs: Int
        get() = prefs.getInt("watchdog_secs", 8)
        set(value) = prefs.edit().putInt("watchdog_secs", value).apply()

    /** When a channel keeps failing, try the same channel on another account. */
    var watchdogOtherAccount: Boolean
        get() = prefs.getBoolean("watchdog_other_account", true)
        set(value) = prefs.edit().putBoolean("watchdog_other_account", value).apply()

    /** Show a small note on screen when the watchdog acts. */
    var watchdogNotes: Boolean
        get() = prefs.getBoolean("watchdog_notes", true)
        set(value) = prefs.edit().putBoolean("watchdog_notes", value).apply()

    /** Last 50 watchdog events, newest first ("time|channel|problem|action"). */
    var healthLog: List<String>
        get() = prefs.getString("health_log", null)?.let { raw ->
            runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
        }.orEmpty()
        set(value) = prefs.edit().putString("health_log", JSONArray(value.take(50)).toString()).apply()

    fun addHealthEvent(line: String) {
        healthLog = listOf(line) + healthLog
    }

    /**
     * "Reset all settings to Nova defaults": every setting goes back to the built-in configuration.
     * Kept: servers, favorites, history, your order / hidden items, PIN, downloads, resume positions, drive names.
     */
    fun has(key: String): Boolean = prefs.contains(key)

    fun markDone(key: String) = prefs.edit().putBoolean(key, true).apply()

    fun resetSettings() {
        val keep = setOf(
            "accounts", "active_account", "favorites_v2", "recent_v2", "org", "downloads",
            "pin_hash", "pin_salt", "drive_names", "drive_order", "health_log",
            "last_backup_link", "last_backup_link_at", "accounts_link", "accounts_link_pw", "accounts_link_checked", "accounts_link_note",
            "age_allowed",
        )
        val e = prefs.edit()
        prefs.all.keys.filter { k ->
            k !in keep && !k.startsWith("pos_") && !k.startsWith("fav_") && !k.startsWith("recent_")
        }.forEach { e.remove(it) }
        e.apply()
    }

    /** Customised remote buttons (JSON: action id -> key codes). Null = defaults. */
    var remoteKeyMap: String?
        get() = prefs.getString("remote_key_map", null)
        set(value) = prefs.edit().putString("remote_key_map", value).apply()

    /** Live TV: press Back twice to leave the player (the first press shows a hint). */
    var doubleBackLive: Boolean
        get() = prefs.getBoolean("double_back_live", true)
        set(value) = prefs.edit().putBoolean("double_back_live", value).apply()

    /** Films & series: press Back twice to stop the video (avoids leaving it by mistake). */
    var doubleBackVod: Boolean
        get() = prefs.getBoolean("double_back_vod", true)
        set(value) = prefs.edit().putBoolean("double_back_vod", value).apply()

    /** Main screen: press Back twice to close Nova. */
    var doubleBackExit: Boolean
        get() = prefs.getBoolean("double_back_exit", true)
        set(value) = prefs.edit().putBoolean("double_back_exit", value).apply()

    /** Number buttons type a channel number in live TV. */
    /** Films & series on TV: the remote stays inside the poster list; Back goes to the groups. */
    var stayInPosters: Boolean
        get() = prefs.getBoolean("stay_in_posters", true)
        set(value) = prefs.edit().putBoolean("stay_in_posters", value).apply()

    /** Live channel start: "fast" (start near live, build the cushion while watching) or "safe" (wait for the full cushion). */
    var liveStart: String
        get() = prefs.getString("live_start", "fast") ?: "fast"
        set(value) = prefs.edit().putString("live_start", value).apply()

    /** Weak signal (video still arriving, too slowly): pause and refill this many seconds before playing on. 0 = off. */
    var weakRefillSecs: Int
        get() = prefs.getInt("weak_refill_secs", 30)
        set(value) = prefs.edit().putInt("weak_refill_secs", value).apply()

    /** Watchdog style: "live" (only what the Live button does: jump to live in the disk buffer) or "full" (all repairs). */
    var watchdogMode: String
        get() = prefs.getString("watchdog_mode", "live") ?: "live"
        set(value) = prefs.edit().putString("watchdog_mode", value).apply()

    /** Parental age filter (USA ratings): 0 OFF, 1 G, 2 PG, 3 PG-13, 4 R, 5 NC-17 = the highest rating allowed. */
    var ageLevel: Int
        get() = prefs.getInt("age_level", 5)
        set(value) = prefs.edit().putInt("age_level", value).apply()
    /** "tmdb" (film database) or "rules" (genre & name). */
    var ageSource: String
        get() = prefs.getString("age_source", "rules") ?: "rules"
        set(value) = prefs.edit().putString("age_source", value).apply()
    var ageHideUnknown: Boolean
        get() = prefs.getBoolean("age_hide_unknown", false)
        set(value) = prefs.edit().putBoolean("age_hide_unknown", value).apply()
    /** Live channels: "name" (hide Adult, XXX, 18+, Horror…) or "allow" (only the groups you allow). */
    var ageLiveMode: String
        get() = prefs.getString("age_live_mode", "name") ?: "name"
        set(value) = prefs.edit().putString("age_live_mode", value).apply()
    var ageAllowedGroups: Set<String>
        get() = prefs.getString("age_allowed_groups", "")!!.split('\n').filter { it.isNotBlank() }.toSet()
        set(value) = prefs.edit().putString("age_allowed_groups", value.joinToString("\n")).apply()
    /** Your own TMDB key; empty = the default key built into Nova. */
    var tmdbKey: String
        get() = prefs.getString("tmdb_key", "") ?: ""
        set(value) = prefs.edit().putString("tmdb_key", value).apply()
    /** Titles allowed anyway (exceptions): "movie|acc|id", "series|acc|id", "live|acc|id". */
    var ageAllowed: Set<String>
        get() = prefs.getString("age_allowed", "")!!.split('\n').filter { it.isNotBlank() }.toSet()
        set(value) = prefs.edit().putString("age_allowed", value.joinToString("\n")).apply()
    /** Word groups switched on (null = the default ones). */
    var ageWordGroupsOn: Set<String>?
        get() = prefs.getString("age_word_groups_on", null)?.split(',')?.filter { it.isNotBlank() }?.toSet()
        set(value) = prefs.edit().putString("age_word_groups_on", value?.joinToString(",")).apply()
    /** The words of a group (null = Nova's list). */
    fun ageWords(group: String): List<String>? =
        prefs.getString("age_words_$group", null)?.split('\n')?.filter { it.isNotBlank() }
    fun setAgeWords(group: String, words: List<String>?) =
        prefs.edit().putString("age_words_$group", words?.joinToString("\n")).apply()

    // ---------- filter file (Download/NovaIPTV/Filter/filter-settings.txt) ----------

    private fun isFilterKey(k: String) = k.startsWith("age_") || k == "tmdb_key" || k == "pin_hash" || k == "pin_salt"
    private val filterSeal = "NovaIPTV-filter-seal-7Q2v"

    /** The filter settings (and the PIN) as text, sealed so a change by hand is noticed. */
    fun exportFilter(): String {
        val sb = StringBuilder("NOVAFILTER1\n")
        prefs.all.filterKeys { isFilterKey(it) }.toSortedMap().forEach { (k, v) ->
            val t = when (v) { is Int -> "i"; is Boolean -> "b"; is Long -> "l"; else -> "s" }
            sb.append(k).append('\t').append(t).append('\t').append(v.toString().replace("\\", "\\\\").replace("\n", "\\n")).append('\n')
        }
        val body = sb.toString()
        return body + "seal\t" + hash(filterSeal + body) + "\n"
    }

    /** Puts back the filter from the file; false when the file is missing, damaged or changed by hand. */
    fun importFilter(text: String): Boolean {
        val i = text.lastIndexOf("seal\t")
        if (!text.startsWith("NOVAFILTER1") || i < 0) return false
        val body = text.substring(0, i)
        if (text.substring(i + 5).trim() != hash(filterSeal + body)) return false
        val e = prefs.edit()
        body.lines().drop(1).filter { it.isNotBlank() }.forEach { l ->
            val p = l.split('\t', limit = 3)
            if (p.size < 3 || !isFilterKey(p[0])) return@forEach
            val v = p[2].replace(Regex("""\\(.)""")) { m -> if (m.groupValues[1] == "n") "\n" else m.groupValues[1] }
            when (p[1]) {
                "i" -> v.toIntOrNull()?.let { e.putInt(p[0], it) }
                "b" -> e.putBoolean(p[0], v == "true")
                "l" -> v.toLongOrNull()?.let { e.putLong(p[0], it) }
                else -> e.putString(p[0], v)
            }
        }
        e.apply()
        return true
    }

    /** True when this phone / box has never saved filter settings (new install or cleared data). */
    val filterUnset: Boolean get() = prefs.all.keys.none { it.startsWith("age_") }

    /** Downloads: connections (1–16) and speed limit (MB/s, 0 = none), for your IPTV servers and for other links. */
    var dlConnServers: Int
        get() = prefs.getInt("dl_conn_servers", 1)
        set(value) = prefs.edit().putInt("dl_conn_servers", value).apply()
    var dlConnLinks: Int
        get() = prefs.getInt("dl_conn_links", 4)
        set(value) = prefs.edit().putInt("dl_conn_links", value).apply()
    var dlSpeedServers: Int
        get() = prefs.getInt("dl_speed_servers", 0)
        set(value) = prefs.edit().putInt("dl_speed_servers", value).apply()
    var dlSpeedLinks: Int
        get() = prefs.getInt("dl_speed_links", 0)
        set(value) = prefs.edit().putInt("dl_speed_links", value).apply()

    /** How many connections each account allows (from the provider; 0 = unknown). */
    fun maxConnections(accountId: String): Int = prefs.getInt("maxconn_$accountId", 0)
    fun setMaxConnections(accountId: String, n: Int) = prefs.edit().putInt("maxconn_$accountId", n).apply()

    /** Extra details a website needs (Referer, User-Agent…), per site. */
    fun linkHeaders(host: String): Map<String, String> =
        prefs.getString("hdr_$host", null)?.lineSequence()?.mapNotNull { l ->
            val k = l.substringBefore(':', "").trim(); val v = l.substringAfter(':', "").trim()
            if (k.isEmpty() || v.isEmpty()) null else k to v
        }?.toMap().orEmpty()

    fun setLinkHeaders(host: String, headers: Map<String, String>) {
        val clean = headers.filter { it.key.isNotBlank() && it.value.isNotBlank() }
        prefs.edit().apply {
            if (clean.isEmpty()) remove("hdr_$host") else putString("hdr_$host", clean.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        }.apply()
    }

    /** "Open link" tab: the last links opened (newest first), each "time|url". */
    var recentLinks: List<String>
        get() = prefs.getString("recent_links", null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        set(value) = prefs.edit().putString("recent_links", value.take(15).joinToString("\n")).apply()

    /** Search: what to look for ("all", "live", "movie", "series") and in which account (null = all servers). */
    /** Where you were when a video started (tab, search words, film / series page), to come back there after it. */
    var returnPoint: String?
        get() = prefs.getString("return_point", null)
        set(value) = prefs.edit().putString("return_point", value).apply()

    var searchKind: String
        get() = prefs.getString("search_kind", "all") ?: "all"
        set(value) = prefs.edit().putString("search_kind", value).apply()
    var searchAccount: String?
        get() = prefs.getString("search_account", null)
        set(value) = prefs.edit().putString("search_account", value).apply()

    /** Restore from a link: the last link used and when. */
    var lastBackupLink: String?
        get() = prefs.getString("last_backup_link", null)
        set(value) = prefs.edit().putString("last_backup_link", value).apply()
    var lastBackupLinkAt: Long
        get() = prefs.getLong("last_backup_link_at", 0L)
        set(value) = prefs.edit().putLong("last_backup_link_at", value).apply()

    /** Accounts from a permanent link (a text file kept up to date by you). */
    var accountsLink: String?
        get() = prefs.getString("accounts_link", null)
        set(value) = prefs.edit().putString("accounts_link", value).apply()
    var accountsLinkPassword: String?
        get() = prefs.getString("accounts_link_pw", null)
        set(value) = prefs.edit().putString("accounts_link_pw", value).apply()
    var accountsLinkCheckedAt: Long
        get() = prefs.getLong("accounts_link_checked", 0L)
        set(value) = prefs.edit().putLong("accounts_link_checked", value).apply()
    var accountsLinkNote: String?
        get() = prefs.getString("accounts_link_note", null)
        set(value) = prefs.edit().putString("accounts_link_note", value).apply()

    /** Series: play the next episode by itself (per series; on unless switched off). */
    fun autoNext(seriesKey: String): Boolean = prefs.getBoolean("autonext_$seriesKey", true)
    fun setAutoNext(seriesKey: String, on: Boolean) = prefs.edit().putBoolean("autonext_$seriesKey", on).apply()

    var numberKeys: Boolean
        get() = prefs.getBoolean("number_keys", true)
        set(value) = prefs.edit().putBoolean("number_keys", value).apply()

    /** Start the last watched channel / video automatically when the app opens. */
    var autoPlayLast: Boolean
        get() = prefs.getBoolean("auto_play_last", false)
        set(value) = prefs.edit().putBoolean("auto_play_last", value).apply()

    /**
     * "builtin" (always the app's player), "fallback" (app's player, external app when a format
     * can't be played), "external_vod" (films & series in the external app) or "external_all".
     */
    var playerMode: String
        get() = prefs.getString("player_mode", "fallback") ?: "fallback"
        set(value) = prefs.edit().putString("player_mode", value).apply()

    /** Package of the external player app; null = let Android ask each time. */
    var externalPlayer: String?
        get() = prefs.getString("external_player", null)
        set(value) = prefs.edit().putString("external_player", value).apply()

    /** "device": the box decodes audio itself (keeps Dolby/DTS passthrough to a soundbar);
     *  "ffmpeg": the app's own decoders first (plays more audio formats, no passthrough). */
    var audioDecoder: String
        get() = prefs.getString("audio_decoder", "ffmpeg") ?: "ffmpeg"
        set(value) = prefs.edit().putString("audio_decoder", value).apply()

    /**
     * Player for each kind of content: "builtin", "ask" (Android's chooser) or an app package name.
     * Kinds: live, movie, series, catchup, recordings.
     */
    fun playerFor(kind: String): String {
        prefs.getString("player_$kind", null)?.let { return it }
        // Earlier versions had one setting for everything.
        val external = externalPlayer ?: "ask"
        return when (playerMode) {
            "external_all" -> external
            "external_vod" -> if (kind == "live") "builtin" else external
            else -> "builtin"
        }
    }

    fun setPlayerFor(kind: String, value: String) = prefs.edit().putString("player_$kind", value).apply()

    /** When the built-in player can't play a format, open the external player instead. */
    var externalOnError: Boolean
        get() = prefs.getBoolean("external_on_error", playerMode != "builtin")
        set(value) = prefs.edit().putBoolean("external_on_error", value).apply()

    /**
     * A channel / video that can't be played here: open it in another app.
     * "none" = don't, "ask" = Android's chooser, otherwise the app's package name.
     * Earlier versions: "When the built-in player can't play a format → Open the external player".
     */
    var failApp: String
        get() = prefs.getString("fail_app", null) ?: if (prefs.contains("external_on_error") && externalOnError) {
            playerFor("live").takeIf { it != "builtin" } ?: "ask"
        } else if (prefs.contains("external_on_error")) "none" else "ask"
        set(value) = prefs.edit().putString("fail_app", value).apply()

    /** When to open [failApp]: "format" (this device can't decode it) or "any" (any failure). */
    var failAppWhen: String
        get() = prefs.getString("fail_app_when", "format") ?: "format"
        set(value) = prefs.edit().putString("fail_app_when", value).apply()

    /** "hardware" (the box's decoders first) or "software" (the app's software decoders first). */
    var videoDecoder: String
        get() = prefs.getString("video_decoder", "hardware") ?: "hardware"
        set(value) = prefs.edit().putString("video_decoder", value).apply()

    /** Memory used for buffered video, in MB. */
    var bufferMb: Int
        get() = prefs.getInt("buffer_mb", 128)
        set(value) = prefs.edit().putInt("buffer_mb", value).apply()

    /** Hardware-accelerated audio (audio offload to the chip's audio DSP). */
    var audioOffload: Boolean
        get() = prefs.getBoolean("audio_offload", false)
        set(value) = prefs.edit().putBoolean("audio_offload", value).apply()

    /** Video surface: "surface" (standard, best for 4K/HDR) or "texture" (OpenGL texture). */
    var videoSurface: String
        get() = prefs.getString("video_surface", "surface") ?: "surface"
        set(value) = prefs.edit().putString("video_surface", value).apply()

    /** Refuse to play when no VPN is connected. */
    var requireVpn: Boolean
        get() = prefs.getBoolean("require_vpn", false)
        set(value) = prefs.edit().putBoolean("require_vpn", value).apply()

    var subtitleSize: Int
        get() = prefs.getInt("subtitle_size", 20)
        set(value) = prefs.edit().putInt("subtitle_size", value).apply()

    /** "white" or "yellow". */
    var subtitleColor: String
        get() = prefs.getString("subtitle_color", "white") ?: "white"
        set(value) = prefs.edit().putString("subtitle_color", value).apply()

    var subtitleBackground: Boolean
        get() = prefs.getBoolean("subtitle_background", false)
        set(value) = prefs.edit().putBoolean("subtitle_background", value).apply()

    // ---------- parental control ----------

    val hasPin: Boolean get() = !prefs.getString("pin_hash", null).isNullOrEmpty()

    fun setPin(pin: String?) {
        if (pin.isNullOrEmpty()) {
            prefs.edit().remove("pin_hash").remove("pin_salt").apply()
            return
        }
        val salt = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("pin_salt", salt).putString("pin_hash", hash(salt + pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString("pin_salt", null) ?: return false
        return prefs.getString("pin_hash", null) == hash(salt + pin)
    }

    private fun hash(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---------- backup (.bak) ----------

    /** Everything the app keeps (servers, settings, favorites, order, hidden items…) as text. */
    fun exportBackup(): String {
        val values = JSONObject()
        prefs.all.forEach { (key, value) ->
            val entry = JSONObject()
            when (value) {
                is String -> entry.put("t", "s").put("v", value)
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is Boolean -> entry.put("t", "b").put("v", value)
                is Set<*> -> entry.put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> return@forEach
            }
            values.put(key, entry)
        }
        return JSONObject()
            .put("app", "NovaIPTV")
            .put("version", 1)
            .put("created", System.currentTimeMillis())
            .put("prefs", values)
            .toString(2)
    }

    // ---------- lists backup (_list.bakL): order, favorites, hidden items only ----------

    private fun isListKey(k: String) = k == "org" || k == "favorites_v2" || k.startsWith("fav_")

    /** Only how the lists are arranged: group & item order, favorites, what you hid, open / closed sections. */
    fun exportLists(): String {
        val values = JSONObject()
        prefs.all.filterKeys { isListKey(it) }.forEach { (k, v) -> if (v is String) values.put(k, v) }
        return JSONObject().put("app", "NovaIPTV").put("kind", "lists").put("version", 1)
            .put("created", System.currentTimeMillis()).put("lists", values).toString(2)
    }

    /** What a lists backup holds: "24 favorites · 6 groups in your order · 3 hidden" ("" = empty). */
    fun listsSummary(text: String): String = runCatching {
        val v = JSONObject(text).optJSONObject("lists") ?: return ""
        summaryOf(v.keys().asSequence().associateWith { v.optString(it) })
    }.getOrDefault("")

    /** The same for what is in Nova now (to warn before saving nothing). */
    fun currentListsSummary(): String =
        summaryOf(prefs.all.filterKeys { isListKey(it) }.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap())

    private fun summaryOf(values: Map<String, String>): String {
        var favs = 0
        values.filterKeys { it == "favorites_v2" || it.startsWith("fav_") }.values.forEach { t ->
            favs += runCatching { JSONArray(t).length() }.getOrDefault(0)
        }
        val o = values["org"]?.let { runCatching { json.decodeFromString(OrgData.serializer(), it) }.getOrNull() }
        val ordered = o?.let { it.groupOrder.size + it.itemOrder.size } ?: 0
        val hidden = o?.let { it.hiddenGroups.size + it.hiddenItems.size } ?: 0
        return listOfNotNull(
            favs.takeIf { it > 0 }?.let { "$it favorites" },
            ordered.takeIf { it > 0 }?.let { "$it lists in your order" },
            hidden.takeIf { it > 0 }?.let { "$it hidden" },
        ).joinToString(" · ")
    }

    /** Puts back the lists only (servers and settings are not touched). */
    fun importLists(text: String) {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("This file is not a Nova lists backup") }
        if (root.optString("app") != "NovaIPTV" || root.optString("kind") != "lists") throw IllegalArgumentException("This file is not a Nova lists backup (.bakL)")
        val values = root.optJSONObject("lists") ?: throw IllegalArgumentException("The lists backup is empty")
        val e = prefs.edit()
        prefs.all.keys.filter { isListKey(it) }.forEach { e.remove(it) }
        values.keys().forEach { k -> if (isListKey(k)) e.putString(k, values.optString(k)) }
        e.apply()
    }

    /** Replaces all saved data with the backup. Throws IllegalArgumentException if the file is not a Nova backup. */
    fun importBackup(text: String) {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This file is not a Nova IPTV backup")
        }
        if (root.optString("app") != "NovaIPTV") throw IllegalArgumentException("This file is not a Nova IPTV backup")
        val values = root.optJSONObject("prefs") ?: throw IllegalArgumentException("The backup is empty")
        val editor = prefs.edit().clear()
        values.keys().forEach { key ->
            val e = values.optJSONObject(key) ?: return@forEach
            when (e.optString("t")) {
                "s" -> editor.putString(key, e.optString("v"))
                "i" -> editor.putInt(key, e.optInt("v"))
                "l" -> editor.putLong(key, e.optLong("v"))
                "f" -> editor.putFloat(key, e.optDouble("v").toFloat())
                "b" -> editor.putBoolean(key, e.optBoolean("v"))
                "ss" -> {
                    val arr = e.optJSONArray("v")
                    val set = mutableSetOf<String>()
                    if (arr != null) for (i in 0 until arr.length()) set += arr.optString(i)
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.commit()
    }

    // ---------- downloads & recordings ----------

    private val downloadListSer = ListSerializer(DownloadItem.serializer())

    var downloads: List<DownloadItem>
        get() = prefs.getString("downloads", null)
            ?.let { runCatching { json.decodeFromString(downloadListSer, it) }.getOrNull() }
            ?: emptyList()
        set(value) = prefs.edit().putString("downloads", json.encodeToString(downloadListSer, value)).apply()

    // ---------- favorites & recent (all accounts together) ----------

    private fun readList(key: String): List<SavedItem> =
        prefs.getString(key, null)
            ?.let { runCatching { json.decodeFromString(savedListSer, it) }.getOrNull() }
            ?: emptyList()

    private fun writeList(key: String, list: List<SavedItem>) =
        prefs.edit().putString(key, json.encodeToString(savedListSer, list)).apply()

    /** Earlier versions kept favorites per account; merge them once into the shared lists. */
    private fun migrateOldLists() {
        if (prefs.getBoolean("lists_v2", false)) return
        val favs = mutableListOf<SavedItem>()
        val recent = mutableListOf<SavedItem>()
        accounts.forEach { a ->
            favs += readList("fav_${a.id}").map { it.copy(accountId = a.id) }
            recent += readList("recent_${a.id}").map { it.copy(accountId = a.id) }
        }
        writeList("favorites_v2", favs)
        writeList("recent_v2", recent.sortedByDescending { it.savedAt }.take(80))
        prefs.edit().putBoolean("lists_v2", true).apply()
    }

    init {
        migrateOldLists()
    }

    var favorites: List<SavedItem>
        get() = readList("favorites_v2")
        set(value) = writeList("favorites_v2", value)

    fun isFavorite(item: SavedItem) = favorites.any { it.sameAs(item) }

    /** Returns true when the item is now a favorite. */
    fun toggleFavorite(item: SavedItem): Boolean {
        val list = favorites
        val exists = list.any { it.sameAs(item) }
        favorites = if (exists) list.filterNot { it.sameAs(item) }
        else list + item.copy(savedAt = System.currentTimeMillis())
        return !exists
    }

    val recent: List<SavedItem> get() = readList("recent_v2")

    fun addRecent(item: SavedItem) {
        val updated = listOf(item.copy(savedAt = System.currentTimeMillis())) + recent.filterNot { it.sameAs(item) }
        writeList("recent_v2", updated.take(80))
    }

    fun clearRecent() = prefs.edit().remove("recent_v2").apply()

    // ---------- organisation: hidden / reordered groups and channels ----------

    var org: OrgData
        get() = prefs.getString("org", null)
            ?.let { runCatching { json.decodeFromString(OrgData.serializer(), it) }.getOrNull() }
            ?: OrgData()
        set(value) = prefs.edit().putString("org", json.encodeToString(OrgData.serializer(), value)).apply()

    fun account(id: String): Account? = accounts.firstOrNull { it.id == id }

    // ---------- resume positions ----------

    fun getPosition(key: String): Long = prefs.getLong("pos_$key", 0L)

    fun setPosition(key: String, positionMs: Long) {
        if (positionMs <= 0) prefs.edit().remove("pos_$key").apply()
        else prefs.edit().putLong("pos_$key", positionMs).apply()
    }

    fun removeAccountData(accountId: String) {
        favorites = favorites.filterNot { it.accountId == accountId }
        writeList("recent_v2", recent.filterNot { it.accountId == accountId })
    }

    companion object {
        const val DEFAULT_USER_AGENT = "NovaIPTV/1.0 (Linux; Android)"
    }
}
