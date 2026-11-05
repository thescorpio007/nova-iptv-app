package com.nova.iptv.player

import com.nova.iptv.NovaApp
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.SavedItem
import com.nova.iptv.data.SearchIndex

/**
 * The same film on your other account ("|FR| The Runner (2026)" ↔ "FR_The_Runner_2026"):
 * same words, same language tag and same year when both names have them.
 */
object FilmMatch {

    data class Key(val lang: String?, val words: String, val year: Int?)

    // "|FR| ", "[FR] ", "FR| ", "FR: ", "FR_", "B-AR_"
    private val LEAD = Regex("""^\s*(?:[|\[]\s*([A-Za-z]{2,4})\s*[|\]]\s*|([A-Za-z]{2,4})\s*[|:]\s*|(?:[A-Z]-)?([A-Z]{2,3})[_-])""")
    private val MARKS = Regex("""\p{Mn}+""")
    private val SPLIT = Regex("[^a-z0-9]+")
    private val QUALITY = setOf(
        "hd", "fhd", "uhd", "sd", "4k", "8k", "hdr", "hevc", "h265", "h264", "x265", "x264", "1080p", "720p", "2160p", "480p",
        "360p", "multi", "bluray", "webrip", "webdl", "web", "dl",
    )

    fun key(name: String): Key {
        var n = name.replace('‎', ' ').replace('‏', ' ').replace(' ', ' ').trim()
        var lang: String? = null
        LEAD.find(n)?.let { m ->
            lang = (1..3).map { m.groupValues[it] }.firstOrNull { it.length in 2..4 }?.lowercase()
            n = n.substring(m.range.last + 1)
        }
        n = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replace(MARKS, "").lowercase()
        val tokens = n.split(SPLIT).filter { it.isNotBlank() }
        val yearTok = tokens.lastOrNull { it.length == 4 && (it.startsWith("19") || it.startsWith("20")) && tokens.size > 1 }
        val words = tokens.filter { it != yearTok && it !in QUALITY }.joinToString(" ")
        return Key(lang, words, yearTok?.toIntOrNull())
    }

    fun matches(a: Key, b: Key): Boolean =
        a.words.length >= 3 && a.words == b.words &&
            (a.lang == null || b.lang == null || a.lang == b.lang) &&
            (a.year == null || b.year == null || a.year == b.year)

    /** The same film on one of your other accounts, ready to play; null when not there (or not a film). */
    suspend fun sameFilmElsewhere(app: NovaApp, item: PlayItem): PlayItem? {
        if (item.isLive || item.saved.type != ItemType.MOVIE) return null
        val want = key(item.title)
        if (want.words.length < 3) return null
        for (a in app.storage.accounts.filter { it.id != item.saved.accountId }) {
            val movies = SearchIndex.get(a.id)?.movies?.takeIf { it.isNotEmpty() }
                ?: runCatching { app.api.movies(a, null) }.getOrNull() ?: continue
            val hit = movies.firstOrNull { m -> matches(want, key(m.name)) } ?: continue
            return PlayItem(
                SavedItem(ItemType.MOVIE, hit.streamId, hit.name, hit.icon, hit.extension, accountId = a.id),
                app.api.movieUrl(a, hit.streamId, hit.extension), isLive = false, downloadExt = hit.extension,
            )
        }
        return null
    }
}
