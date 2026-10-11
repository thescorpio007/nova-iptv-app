package com.nova.iptv.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.nova.iptv.NovaApp
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.SavedItem
import com.nova.iptv.download.DownloadCenter
import com.nova.iptv.download.HlsDownload
import com.nova.iptv.player.PlayItem
import com.nova.iptv.player.PlaybackQueue
import com.nova.iptv.player.PlayerActivity

/**
 * Any video link (mp4, mkv, ts, m3u8, m3u…) from the "Open link" tab or from Share: play it, download it, or
 * download & watch it — like a film, but never saved as an account.
 */
object LinkActions {
    private val URL = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    /** The first web link inside shared text ("Look at this: https://…"). */
    fun findLink(text: String?): String? = text?.let { URL.find(it)?.value?.trimEnd('.', ',', ')', ']') }

    fun title(url: String): String {
        val path = Uri.parse(url).path.orEmpty().trimEnd('/')
        return Uri.decode(path.substringAfterLast('/')).ifBlank { Uri.parse(url).host ?: "Video" }
    }

    private fun ext(url: String): String = when {
        HlsDownload.isHls(url) -> "ts"
        else -> url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            .takeIf { it in setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "mpg", "mpeg", "flv", "wmv") } ?: "mp4"
    }

    fun remember(context: Context, url: String) {
        val s = (context.applicationContext as NovaApp).storage
        s.recentLinks = listOf("${System.currentTimeMillis()}|$url") + s.recentLinks.filter { it.substringAfter('|') != url }
    }

    /** Plays it like "Open with" does (an .m3u list opens as a temporary channel list). */
    fun play(context: Context, url: String) {
        remember(context, url)
        Log.i("NovaTest", "LINK play ${title(url)}")
        context.startActivity(
            Intent(context, OpenWithActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun download(context: Context, url: String) {
        remember(context, url)
        Log.i("NovaTest", "LINK download ${title(url)}")
        DownloadCenter.enqueue(context.applicationContext, title(url).substringBeforeLast('.'), url, ext(url), ItemType.MOVIE, null)
    }

    fun downloadAndWatch(context: Context, url: String) {
        remember(context, url)
        Log.i("NovaTest", "LINK download & watch ${title(url)}")
        val name = title(url).substringBeforeLast('.')
        val d = DownloadCenter.watchNow(context.applicationContext, name, url, ext(url), ItemType.MOVIE, null)
        // HLS: the player reads the joined file being written, not the .m3u8 list.
        val playUrl = if (HlsDownload.isHls(url)) "nova-dl://${d.id}/video.ts" else url
        PlaybackQueue.items = listOf(
            PlayItem(SavedItem(ItemType.MOVIE, url, name, accountId = "link"), playUrl, isLive = false, temporary = true, downloadId = d.id)
        )
        PlaybackQueue.index = 0
        PlaybackQueue.groupKey = null
        PlaybackQueue.playlist = null
        context.startActivity(Intent(context, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
