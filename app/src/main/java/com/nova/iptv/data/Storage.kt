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
        get() = prefs.getInt("cushion_seconds", 10)
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

    /** Disk space for the rolling live buffer, in MB. */
    var liveDiskMb: Int
        get() = prefs.getInt("live_disk_mb", 600)
        set(value) = prefs.edit().putInt("live_disk_mb", value).apply()

    /** Jump to the next channel automatically when a live channel doesn't work. */
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
        get() = prefs.getInt("read_ahead_mb", 100)
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

    /** Search: what to look for ("all", "live", "movie", "series") and in which account (null = all servers). */
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
