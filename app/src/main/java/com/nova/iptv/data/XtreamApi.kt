package com.nova.iptv.data

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Client for the Xtream Codes "player_api.php" interface.
 * Xtream servers are inconsistent (numbers sent as strings, nulls, arrays vs objects),
 * so every field is read leniently.
 */
class XtreamApi(
    private val client: OkHttpClient,
    private val userAgent: () -> String,
) {

    private suspend fun call(account: Account, params: Map<String, String>): JsonElement =
        withContext(Dispatchers.IO) {
            val builder = (normalizeServer(account.server) + "/player_api.php").toHttpUrlOrNull()
                ?.newBuilder() ?: throw IOException("Invalid server address")
            builder.addQueryParameter("username", account.username)
            builder.addQueryParameter("password", account.password)
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            val request = Request.Builder()
                .url(builder.build())
                .header("User-Agent", userAgent())
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Server returned HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty().trim()
                if (body.isEmpty()) {
                    JsonNull
                } else {
                    try {
                        Json.parseToJsonElement(body)
                    } catch (e: Exception) {
                        throw IOException("The server sent an unexpected response")
                    }
                }
            }
        }

    suspend fun authenticate(account: Account): UserInfo {
        val root = call(account, emptyMap()).asObj()
            ?: throw IOException("The server did not answer like an Xtream server")
        val info = root["user_info"].asObj() ?: throw IOException("Login failed")
        if (info.str("auth") == "0") throw IOException("Wrong username or password")
        return UserInfo(
            status = info.str("status") ?: "Unknown",
            expDate = info.str("exp_date")?.toLongOrNull(),
            maxConnections = info.str("max_connections") ?: "-",
            activeConnections = info.str("active_cons") ?: "-",
            isTrial = info.str("is_trial") == "1",
        )
    }

    suspend fun categories(account: Account, type: ContentType): List<Category> {
        val action = when (type) {
            ContentType.LIVE -> "get_live_categories"
            ContentType.MOVIE -> "get_vod_categories"
            ContentType.SERIES -> "get_series_categories"
        }
        return call(account, mapOf("action" to action)).arr().mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val id = o.str("category_id") ?: return@mapNotNull null
            Category(account.id, id, o.str("category_name") ?: "Unnamed")
        }
    }

    suspend fun liveStreams(account: Account, categoryId: String?): List<LiveChannel> {
        val params = mutableMapOf("action" to "get_live_streams")
        if (categoryId != null) params["category_id"] = categoryId
        return call(account, params).arr().mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val id = o.str("stream_id") ?: return@mapNotNull null
            LiveChannel(
                accountId = account.id,
                streamId = id,
                num = o.str("num")?.toDoubleOrNull()?.toInt() ?: 0,
                name = o.str("name") ?: "Channel $id",
                icon = o.str("stream_icon"),
                categoryId = o.str("category_id") ?: "",
                hasArchive = o.str("tv_archive") == "1",
                added = o.str("added")?.toDoubleOrNull()?.toLong() ?: 0L,
            )
        }
    }

    suspend fun movies(account: Account, categoryId: String?): List<Movie> {
        val params = mutableMapOf("action" to "get_vod_streams")
        if (categoryId != null) params["category_id"] = categoryId
        return call(account, params).arr().mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val id = o.str("stream_id") ?: return@mapNotNull null
            Movie(
                accountId = account.id,
                streamId = id,
                name = o.str("name") ?: "Movie $id",
                icon = o.str("stream_icon"),
                categoryId = o.str("category_id") ?: "",
                rating = ratingOf(o),
                extension = o.str("container_extension") ?: "mp4",
                added = o.str("added")?.toDoubleOrNull()?.toLong() ?: 0L,
                year = yearOf(o, o.str("name")),
            )
        }
    }

    suspend fun series(account: Account, categoryId: String?): List<Series> {
        val params = mutableMapOf("action" to "get_series")
        if (categoryId != null) params["category_id"] = categoryId
        return call(account, params).arr().mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val id = o.str("series_id") ?: return@mapNotNull null
            Series(
                accountId = account.id,
                seriesId = id,
                name = o.str("name") ?: "Series $id",
                cover = o.str("cover"),
                categoryId = o.str("category_id") ?: "",
                plot = o.str("plot"),
                rating = ratingOf(o),
                added = (o.str("last_modified") ?: o.str("added"))?.toDoubleOrNull()?.toLong() ?: 0L,
                year = yearOf(o, o.str("name")),
            )
        }
    }

    suspend fun seriesInfo(account: Account, series: Series): SeriesInfo {
        val root = call(account, mapOf("action" to "get_series_info", "series_id" to series.seriesId))
            .asObj() ?: throw IOException("No information for this series")
        val info = root["info"].asObj()
        val episodes = mutableMapOf<Int, MutableList<Episode>>()

        fun add(seasonKey: Int?, el: JsonElement) {
            val o = el.asObj() ?: return
            val id = o.str("id") ?: return
            val season = o.str("season")?.toIntOrNull() ?: seasonKey ?: 1
            val epInfo = o["info"].asObj()
            episodes.getOrPut(season) { mutableListOf() }.add(
                Episode(
                    id = id,
                    title = o.str("title") ?: "Episode",
                    season = season,
                    episodeNum = o.str("episode_num")?.toIntOrNull() ?: 0,
                    extension = o.str("container_extension") ?: "mp4",
                    plot = epInfo?.str("plot"),
                    image = epInfo?.str("movie_image"),
                )
            )
        }

        when (val e = root["episodes"]) {
            is JsonObject -> e.forEach { (key, value) ->
                val arr = value.asArr()
                if (arr != null) arr.forEach { add(key.toIntOrNull(), it) } else add(key.toIntOrNull(), value)
            }
            is JsonArray -> e.forEach { seasonEl ->
                val arr = seasonEl.asArr()
                if (arr != null) arr.forEach { add(null, it) } else add(null, seasonEl)
            }
            else -> Unit
        }

        val sorted = episodes.mapValues { (_, list) -> list.sortedBy { it.episodeNum } }
        return SeriesInfo(
            series = series.copy(cover = series.cover ?: info?.str("cover")),
            plot = info?.str("plot") ?: series.plot,
            genre = info?.str("genre"),
            cast = info?.str("cast"),
            episodes = sorted,
            details = info?.let { detailsOf(it) } ?: MediaDetails(plot = series.plot),
        )
    }

    /** Full information of one film (director, cast, plot, backdrop, trailer…). */
    suspend fun movieInfo(account: Account, movie: Movie): MediaDetails {
        val root = call(account, mapOf("action" to "get_vod_info", "vod_id" to movie.streamId)).asObj()
            ?: return MediaDetails()
        val info = root["info"].asObj() ?: return MediaDetails()
        return detailsOf(info)
    }

    suspend fun shortEpg(account: Account, streamId: String): List<EpgEntry> {
        val root = call(
            account,
            mapOf("action" to "get_short_epg", "stream_id" to streamId, "limit" to "6"),
        ).asObj() ?: return emptyList()
        return root["epg_listings"].arr().mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val start = o.str("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val end = o.str("stop_timestamp")?.toLongOrNull() ?: start
            EpgEntry(
                title = decodeB64(o.str("title")),
                description = decodeB64(o.str("description")),
                start = start * 1000,
                end = end * 1000,
            )
        }.sortedBy { it.start }
    }

    /** Past programmes of a channel that the server keeps (catch-up), newest first. */
    suspend fun catchup(account: Account, streamId: String): List<CatchupEntry> {
        val root = call(
            account,
            mapOf("action" to "get_simple_data_table", "stream_id" to streamId),
        ).asObj() ?: return emptyList()
        val now = System.currentTimeMillis()
        return root["epg_listings"].arr().mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val start = o.str("start_timestamp")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val end = o.str("stop_timestamp")?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            if (end > now) return@mapNotNull null
            val archived = o.str("has_archive")
            if (archived != null && archived != "1") return@mapNotNull null
            // "2026-10-08 20:00:00" -> "2026-10-08:20-00"
            val serverStart = o.str("start")?.trim()?.let { raw ->
                val m = Regex("""(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})""").find(raw)
                m?.let { "${it.groupValues[1]}:${it.groupValues[2]}-${it.groupValues[3]}" }
            } ?: return@mapNotNull null
            CatchupEntry(
                title = decodeB64(o.str("title")),
                description = decodeB64(o.str("description")),
                start = start,
                end = end,
                serverStart = serverStart,
                durationMinutes = ((end - start) / 60_000).toInt().coerceAtLeast(1),
            )
        }.sortedByDescending { it.start }
    }

    /** Address of a past programme on Xtream servers (the /timeshift/ path). */
    fun catchupUrl(a: Account, streamId: String, e: CatchupEntry) =
        "${normalizeServer(a.server)}/timeshift/${Uri.encode(a.username)}/${Uri.encode(a.password)}/" +
            "${e.durationMinutes}/${e.serverStart}/$streamId.ts"

    fun liveUrl(a: Account, streamId: String, format: String) =
        "${normalizeServer(a.server)}/live/${Uri.encode(a.username)}/${Uri.encode(a.password)}/$streamId.$format"

    fun movieUrl(a: Account, streamId: String, ext: String) =
        "${normalizeServer(a.server)}/movie/${Uri.encode(a.username)}/${Uri.encode(a.password)}/$streamId.$ext"

    fun episodeUrl(a: Account, episodeId: String, ext: String) =
        "${normalizeServer(a.server)}/series/${Uri.encode(a.username)}/${Uri.encode(a.password)}/$episodeId.$ext"

    companion object {
        /** Turns "myhost.com:8080/", "http://myhost:80/player_api.php", etc. into "http://myhost:80". */
        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore('?')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (s.endsWith(suffix, true)) s = s.dropLast(suffix.length)
            }
            return s.trimEnd('/')
        }

        /** Reads server, username and password out of a pasted M3U / Xtream link, if present. */
        fun parseLink(raw: String): Triple<String, String, String>? {
            val url = raw.trim().toHttpUrlOrNull() ?: return null
            val user = url.queryParameter("username") ?: return null
            val pass = url.queryParameter("password") ?: return null
            val port = if (url.port == 80 && url.scheme == "http" || url.port == 443 && url.scheme == "https") "" else ":${url.port}"
            return Triple("${url.scheme}://${url.host}$port", user, pass)
        }

        private fun decodeB64(s: String?): String {
            if (s.isNullOrBlank()) return ""
            return try {
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
            } catch (e: IllegalArgumentException) {
                s
            }
        }

        /** Reads the details block of get_vod_info / get_series_info (providers use different names). */
        internal fun detailsOf(o: JsonObject): MediaDetails {
            val durationText = o.str("duration")?.let { formatDuration(it) }
                ?: o.str("duration_secs")?.toLongOrNull()?.takeIf { it > 0 }?.let { formatMinutes(it / 60) }
                ?: o.str("episode_run_time")?.toLongOrNull()?.takeIf { it > 0 }?.let { formatMinutes(it) + " / episode" }
            return MediaDetails(
                plot = o.str("plot") ?: o.str("description"),
                director = o.str("director"),
                cast = o.str("cast") ?: o.str("actors"),
                genre = o.str("genre"),
                releaseDate = o.str("releasedate") ?: o.str("releaseDate") ?: o.str("release_date") ?: o.str("year"),
                duration = durationText,
                rating = ratingOf(o)?.toDoubleOrNull(),
                backdrop = firstStr(o["backdrop_path"]),
                poster = o.str("movie_image") ?: o.str("cover_big") ?: o.str("cover"),
                trailer = o.str("youtube_trailer") ?: o.str("trailer"),
                country = o.str("country"),
            )
        }

        /** Rating out of 10: "rating", or "rating_5based" doubled. Null when missing or 0. */
        internal fun ratingOf(o: JsonObject): String? {
            val r = o.str("rating")?.replace(',', '.')?.toDoubleOrNull()
            val r5 = o.str("rating_5based")?.replace(',', '.')?.toDoubleOrNull()
            val value = when {
                r != null && r > 0 -> if (r > 10) r / 10 else r
                r5 != null && r5 > 0 -> r5 * 2
                else -> return null
            }
            return "%.1f".format(Locale.US, value).removeSuffix(".0")
        }

        private val yearInName = Regex("""(?:\(|\[|\s|-|\|)((?:19|20)\d{2})(?:\)|\]|\s|$)""")

        /** Year of production: "year" / release date fields, otherwise "(2026)" in the title. */
        internal fun yearOf(o: JsonObject, name: String?): Int? {
            for (k in listOf("year", "releaseDate", "release_date", "releasedate")) {
                val y = o.str(k)?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
                if (y != null) return y
            }
            return yearFromName(name)
        }

        fun yearFromName(name: String?): Int? =
            name?.let { n -> yearInName.findAll(n).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() }

        private fun firstStr(e: JsonElement?): String? = when (e) {
            is JsonPrimitive -> e.content.takeIf { it.isNotBlank() && it != "null" }
            is JsonArray -> e.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
            else -> null
        }

        /** "01:24:00" -> "1h 24m"; "84 min" -> "1h 24m". */
        fun formatDuration(raw: String): String? {
            val parts = raw.trim().split(":").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size >= 2 && raw.contains(':')) {
                val minutes = if (parts.size == 3) parts[0] * 60 + parts[1] else parts[0] * 60 + parts[1]
                return if (minutes > 0) formatMinutes(minutes.toLong()) else null
            }
            val m = Regex("""\d+""").find(raw)?.value?.toLongOrNull() ?: return raw.takeIf { it.isNotBlank() }
            return if (m > 0) formatMinutes(m) else null
        }

        fun formatMinutes(m: Long): String = if (m >= 60) "${m / 60}h ${"%02d".format(m % 60)}m" else "${m}m"

        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun formatTime(ms: Long): String = timeFormat.format(Date(ms))

        /** "Now 20:00–21:00  News" / "Next 21:00  Film" lines for an EPG list. */
        fun nowNext(list: List<EpgEntry>, now: Long = System.currentTimeMillis()): Pair<EpgEntry?, EpgEntry?> {
            val current = list.firstOrNull { now >= it.start && now < it.end }
            val next = list.firstOrNull { it.start >= (current?.end ?: now) }
            return current to next
        }
    }
}

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

internal fun JsonObject.str(key: String): String? {
    val e = this[key] ?: return null
    if (e is JsonNull || e !is JsonPrimitive) return null
    return e.content.takeIf { it.isNotBlank() && it != "null" }
}
