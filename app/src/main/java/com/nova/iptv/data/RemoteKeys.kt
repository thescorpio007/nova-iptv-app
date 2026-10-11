package com.nova.iptv.data

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remote control buttons → actions, customisable in Settings → Remote control (TiviMate style).
 * The defaults suit the usual Android TV remotes and the MX3 air-mouse remote.
 */
object RemoteKeys {

    /** Where an action works: the live player, the film / series player, or Nova's lists and menus. */
    enum class Area(val label: String) { LIVE("Live TV player"), VOD("Films & series player"), LISTS("Lists & menus") }

    data class Action(val id: String, val area: Area, val label: String, val defaults: List<Int>)

    val actions: List<Action> = listOf(
        // ---- live TV
        Action("live_next", Area.LIVE, "Next channel", listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT)),
        Action("live_prev", Area.LIVE, "Previous channel", listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS)),
        Action("live_list", Area.LIVE, "Groups & channels list (over the video)", listOf(KeyEvent.KEYCODE_DPAD_LEFT)),
        Action("live_recent", Area.LIVE, "Recently watched channels bar", listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_BOOKMARK)),
        Action("live_info", Area.LIVE, "Show info & buttons", listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE)),
        Action("live_recall", Area.LIVE, "Last watched channel (recall)", listOf(KeyEvent.KEYCODE_LAST_CHANNEL)),
        Action("live_pause", Area.LIVE, "Pause / play", listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)),
        Action("live_rewind", Area.LIVE, "Rewind 30 s (disk buffer)", listOf(KeyEvent.KEYCODE_MEDIA_REWIND)),
        Action("live_forward", Area.LIVE, "Forward 30 s (disk buffer)", listOf(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)),
        Action("live_record", Area.LIVE, "Record", listOf(KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_PROG_RED)),
        Action("live_favorite", Area.LIVE, "Add / remove favorite", listOf(KeyEvent.KEYCODE_PROG_GREEN)),
        Action("live_aspect", Area.LIVE, "Aspect ratio", listOf(KeyEvent.KEYCODE_PROG_YELLOW)),
        Action("live_tracks", Area.LIVE, "Audio & subtitles", listOf(KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_CAPTIONS)),
        Action("live_external", Area.LIVE, "Open in external player", emptyList()),
        Action("live_golive", Area.LIVE, "Jump to live (fixes a stuck picture)", emptyList()),
        // ---- films & series
        Action("vod_play_pause", Area.VOD, "Play / pause", listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)),
        Action("vod_back", Area.VOD, "Back 10 s", listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND)),
        Action("vod_forward", Area.VOD, "Forward 10 s", listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)),
        Action("vod_back_long", Area.VOD, "Back 1 minute", emptyList()),
        Action("vod_forward_long", Area.VOD, "Forward 1 minute", emptyList()),
        Action("vod_controls", Area.VOD, "Show controls", listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO)),
        Action("vod_next", Area.VOD, "Next episode", listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_UP)),
        Action("vod_prev", Area.VOD, "Previous episode", listOf(KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_DOWN)),
        Action("vod_download", Area.VOD, "Download", listOf(KeyEvent.KEYCODE_PROG_RED)),
        Action("vod_favorite", Area.VOD, "Add / remove favorite", listOf(KeyEvent.KEYCODE_PROG_GREEN)),
        Action("vod_aspect", Area.VOD, "Aspect ratio", listOf(KeyEvent.KEYCODE_PROG_YELLOW)),
        Action("vod_tracks", Area.VOD, "Audio & subtitles", listOf(KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_CAPTIONS)),
        Action("vod_external", Area.VOD, "Open in external player", emptyList()),
        // ---- lists & menus
        Action("list_page_down", Area.LISTS, "Page down in lists", listOf(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN)),
        Action("list_page_up", Area.LISTS, "Page up in lists", listOf(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP)),
        Action("list_live", Area.LISTS, "Go to Live TV", listOf(KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_PROG_RED)),
        Action("list_films", Area.LISTS, "Go to Films", listOf(KeyEvent.KEYCODE_PROG_GREEN)),
        Action("list_series", Area.LISTS, "Go to Series", listOf(KeyEvent.KEYCODE_PROG_YELLOW)),
        Action("list_search", Area.LISTS, "Search", listOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_PROG_BLUE)),
        Action("list_recent", Area.LISTS, "Recently watched", emptyList()),
        Action("list_settings", Area.LISTS, "Settings", emptyList()),
        Action("list_last", Area.LISTS, "Play last watched channel", listOf(KeyEvent.KEYCODE_LAST_CHANNEL)),
    )

    private val byId = actions.associateBy { it.id }

    fun action(id: String): Action? = byId[id]

    // ------------------------------------------------------------------ saved map

    @Volatile private var cache: Map<String, List<Int>>? = null
    @Volatile private var cacheSource: String? = null

    /** Keys assigned to every action (defaults for actions never changed). */
    /**
     * The built-in buttons of an action. With "TV style" arrows (default) ▲ = next channel and ▼ = previous;
     * with "list style" it's the other way round. Only ▲ ▼ change.
     */
    fun defaultsOf(storage: Storage, a: Action): List<Int> =
        if (storage.tvArrows && (a.id == "live_next" || a.id == "live_prev")) a.defaults.map(::flipArrow) else a.defaults

    private fun flipArrow(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> KeyEvent.KEYCODE_DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_DOWN -> KeyEvent.KEYCODE_DPAD_UP
        else -> code
    }

    /** Switches ▲ ▼ between next and previous channel (keeps any other buttons you assigned). */
    fun setTvArrows(storage: Storage, on: Boolean) {
        if (storage.tvArrows == on) return
        storage.tvArrows = on
        val raw = storage.remoteKeyMap ?: return
        val custom = runCatching { JSONObject(raw) }.getOrNull() ?: return
        listOf("live_next", "live_prev").forEach { id ->
            val arr = custom.optJSONArray(id) ?: return@forEach
            custom.put(id, JSONArray((0 until arr.length()).map { flipArrow(arr.getInt(it)) }))
        }
        storage.remoteKeyMap = custom.toString()
    }

    fun map(storage: Storage): Map<String, List<Int>> {
        val raw = (storage.remoteKeyMap ?: "") + "|" + storage.tvArrows
        cache?.let { if (cacheSource == raw) return it }
        val custom = HashMap<String, List<Int>>()
        val saved = storage.remoteKeyMap
        if (!saved.isNullOrBlank()) {
            runCatching {
                val o = JSONObject(saved)
                o.keys().forEach { k ->
                    val arr = o.optJSONArray(k) ?: return@forEach
                    custom[k] = (0 until arr.length()).map { arr.getInt(it) }
                }
            }
        }
        val full = actions.associate { a -> a.id to (custom[a.id] ?: defaultsOf(storage, a)) }
        cache = full
        cacheSource = raw
        return full
    }

    fun keysFor(storage: Storage, id: String): List<Int> = map(storage)[id].orEmpty()

    /** The action a button does in [area], or null. */
    fun actionFor(storage: Storage, area: Area, keyCode: Int): String? {
        val m = map(storage)
        return actions.firstOrNull { it.area == area && keyCode in m[it.id].orEmpty() }?.id
    }

    private fun save(storage: Storage, full: Map<String, List<Int>>) {
        val o = JSONObject()
        full.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        storage.remoteKeyMap = o.toString()
    }

    /**
     * Adds [keyCode] to [id]. The same button is taken away from other actions of the same area,
     * so one press never does two things. Returns the labels of the actions it was taken from.
     */
    fun assign(storage: Storage, id: String, keyCode: Int): List<String> {
        val a = byId[id] ?: return emptyList()
        val full = map(storage).toMutableMap()
        val takenFrom = mutableListOf<String>()
        actions.filter { it.area == a.area && it.id != id }.forEach { other ->
            val keys = full[other.id].orEmpty()
            if (keyCode in keys) {
                full[other.id] = keys - keyCode
                takenFrom += other.label
            }
        }
        full[id] = (full[id].orEmpty() - keyCode) + keyCode
        save(storage, full)
        return takenFrom
    }

    fun remove(storage: Storage, id: String, keyCode: Int) {
        val full = map(storage).toMutableMap()
        full[id] = full[id].orEmpty() - keyCode
        save(storage, full)
    }

    fun clear(storage: Storage, id: String) {
        val full = map(storage).toMutableMap()
        full[id] = emptyList()
        save(storage, full)
    }

    fun resetAction(storage: Storage, id: String) {
        val a = byId[id] ?: return
        val full = map(storage).toMutableMap()
        full[id] = defaultsOf(storage, a)
        save(storage, full)
    }

    fun resetAll(storage: Storage) {
        storage.remoteKeyMap = null
    }

    /** Buttons Nova never takes over (the system needs them). */
    fun isReserved(keyCode: Int) = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME ||
        keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_ESCAPE

    fun isDigit(keyCode: Int) = keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
        keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9

    fun digitOf(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    /** A readable button name: "▲ Up", "PG +", "Red", "OK"… */
    fun keyName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "▲ Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "▼ Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "◀ Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "▶ Right"
        KeyEvent.KEYCODE_DPAD_CENTER -> "OK"
        KeyEvent.KEYCODE_ENTER -> "Enter"
        KeyEvent.KEYCODE_PAGE_UP -> "PG +"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PG −"
        KeyEvent.KEYCODE_CHANNEL_UP -> "CH +"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "CH −"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "⏭ Next"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "⏮ Previous"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "⏯ Play/Pause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "▶ Play"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "⏸ Pause"
        KeyEvent.KEYCODE_MEDIA_STOP -> "⏹ Stop"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "⏪ Rewind"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "⏩ Fast forward"
        KeyEvent.KEYCODE_MEDIA_RECORD -> "● Record"
        KeyEvent.KEYCODE_MENU -> "☰ Menu"
        KeyEvent.KEYCODE_INFO -> "ⓘ Info"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_PROG_RED -> "🔴 Red"
        KeyEvent.KEYCODE_PROG_GREEN -> "🟢 Green"
        KeyEvent.KEYCODE_PROG_YELLOW -> "🟡 Yellow"
        KeyEvent.KEYCODE_PROG_BLUE -> "🔵 Blue"
        KeyEvent.KEYCODE_CAPTIONS -> "CC Subtitles"
        KeyEvent.KEYCODE_LAST_CHANNEL -> "Last channel"
        KeyEvent.KEYCODE_TV -> "TV"
        KeyEvent.KEYCODE_SEARCH -> "🔍 Search"
        KeyEvent.KEYCODE_DEL -> "DEL"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Forward DEL"
        KeyEvent.KEYCODE_EXPLORER -> "🌐 Browser"
        KeyEvent.KEYCODE_VOLUME_UP -> "VOL +"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "VOL −"
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> "🔇 Mute"
        KeyEvent.KEYCODE_ZOOM_IN -> "Zoom +"
        KeyEvent.KEYCODE_ZOOM_OUT -> "Zoom −"
        KeyEvent.KEYCODE_APP_SWITCH -> "Recent apps"
        KeyEvent.KEYCODE_ALL_APPS -> "All apps"
        KeyEvent.KEYCODE_SPACE -> "Space"
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() }
    }
}
