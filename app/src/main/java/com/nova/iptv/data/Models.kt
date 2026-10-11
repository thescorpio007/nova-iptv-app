package com.nova.iptv.data

import kotlinx.serialization.Serializable

@Serializable
data class Account(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    /** Comes from your accounts link (kept up to date from it). */
    val fromList: Boolean = false,
)

data class UserInfo(
    val status: String,
    val expDate: Long?,
    val maxConnections: String,
    val activeConnections: String,
    val isTrial: Boolean,
)

/** A group of channels / films / series on one account. [key] is unique across accounts. */
data class Category(val accountId: String, val id: String, val name: String) {
    val key: String get() = "$accountId|$id"
}

enum class ContentType { LIVE, MOVIE, SERIES }

data class LiveChannel(
    val accountId: String,
    val streamId: String,
    val num: Int,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val hasArchive: Boolean,
    /** When the provider added it (unix seconds, 0 = unknown). */
    val added: Long = 0L,
) {
    val key: String get() = "$accountId|$streamId"
}

data class Movie(
    val accountId: String,
    val streamId: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val rating: String?,
    val extension: String,
    val added: Long = 0L,
    val year: Int? = null,
) {
    val key: String get() = "$accountId|$streamId"
}

data class Series(
    val accountId: String,
    val seriesId: String,
    val name: String,
    val cover: String?,
    val categoryId: String,
    val plot: String?,
    val rating: String?,
    val added: Long = 0L,
    val year: Int? = null,
) {
    val key: String get() = "$accountId|$seriesId"
}

/** Details shown on a film's or series' information page (any field may be missing). */
data class MediaDetails(
    val plot: String? = null,
    val director: String? = null,
    val cast: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val duration: String? = null,
    /** Rating out of 10. */
    val rating: Double? = null,
    val backdrop: String? = null,
    val poster: String? = null,
    /** YouTube video id or full address. */
    val trailer: String? = null,
    val country: String? = null,
)

data class Episode(
    val id: String,
    val title: String,
    val season: Int,
    val episodeNum: Int,
    val extension: String,
    val plot: String?,
    val image: String?,
)

data class SeriesInfo(
    val series: Series,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val episodes: Map<Int, List<Episode>>,
    val details: MediaDetails = MediaDetails(),
) {
    val seasons: List<Int> get() = episodes.keys.sorted()
}

/** A past programme that can be replayed (catch-up / timeshift). */
data class CatchupEntry(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long,
    /** Start time as the server writes it, "yyyy-MM-dd:HH-mm", used in the timeshift address. */
    val serverStart: String,
    val durationMinutes: Int,
)

data class EpgEntry(
    val title: String,
    val description: String,
    val start: Long,
    val end: Long,
)

/** A lightweight reference used for favorites, recently watched and playback. */
@Serializable
data class SavedItem(
    val type: String, // "live", "movie", "series", "episode"
    val id: String,
    val name: String,
    val icon: String? = null,
    val extension: String? = null,
    val savedAt: Long = 0L,
    val accountId: String = "",
) {
    val key: String get() = "$accountId|$id"
    val resumeKey: String get() = "${type}_${accountId}_$id"
    fun sameAs(other: SavedItem) = type == other.type && id == other.id && accountId == other.accountId
}

/**
 * Personal organisation of the lists (saved on the device):
 *  - hiddenGroups / hiddenItems: "<zone>|<key>"
 *  - groupOrder: zone -> group keys in the chosen order
 *  - itemOrder: "<zone>|<group key>" -> item keys in the chosen order
 *  - closedSections: "<zone>|<account id>" of account sections that are folded away
 */
@Serializable
data class OrgData(
    val hiddenGroups: Set<String> = emptySet(),
    val hiddenItems: Set<String> = emptySet(),
    val groupOrder: Map<String, List<String>> = emptyMap(),
    val itemOrder: Map<String, List<String>> = emptyMap(),
    val closedSections: Set<String> = emptySet(),
    /** Display names of hidden groups / items, for the list in Settings. */
    val names: Map<String, String> = emptyMap(),
)

object Org {
    /** Sorts [items] by a saved order; items not in it keep their place after the ordered ones. */
    fun <T> applyOrder(items: List<T>, order: List<String>?, key: (T) -> String): List<T> {
        if (order.isNullOrEmpty()) return items
        val pos = HashMap<String, Int>(order.size * 2)
        order.forEachIndexed { i, k -> pos[k] = i }
        return items.withIndex()
            .sortedWith(compareBy<IndexedValue<T>>({ pos[key(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /** Swaps [key] with its neighbour in [keys] (delta -1 = up, +1 = down). */
    fun move(keys: List<String>, key: String, delta: Int): List<String> {
        val i = keys.indexOf(key)
        val j = i + delta
        if (i < 0 || j < 0 || j >= keys.size) return keys
        val list = keys.toMutableList()
        val t = list[i]
        list[i] = list[j]
        list[j] = t
        return list
    }

    fun toggle(set: Set<String>, value: String): Set<String> = if (value in set) set - value else set + value
}

/** A downloaded movie / episode or a live recording, saved under Download/NovaIPTV. */
@Serializable
data class DownloadItem(
    val id: String,
    val title: String,
    val kind: String, // "movie", "episode", "recording"
    val url: String,
    val path: String,
    val icon: String? = null,
    val totalBytes: Long = -1L,
    val doneBytes: Long = 0L,
    val status: String = DownloadStatus.QUEUED,
    val error: String? = null,
    val createdAt: Long = 0L,
)

object DownloadStatus {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val FAILED = "failed"
    const val RECORDING = "recording"
}

object ItemType {
    const val LIVE = "live"
    const val MOVIE = "movie"
    const val SERIES = "series"
    const val EPISODE = "episode"
}
