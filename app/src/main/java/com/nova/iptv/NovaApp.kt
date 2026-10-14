package com.nova.iptv

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.nova.iptv.data.Folders
import com.nova.iptv.data.Storage
import com.nova.iptv.data.XtreamApi
import com.nova.iptv.download.DownloadCenter
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class NovaApp : Application() {

    lateinit var storage: Storage
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(com.nova.iptv.data.LinkHeadersInterceptor { if (::storage.isInitialized) storage else null })
            .build()
    }

    val api: XtreamApi by lazy { XtreamApi(http) { storage.userAgent } }

    private val databaseProvider by lazy { StandaloneDatabaseProvider(this) }

    /** Folder actually used by the movie cache (shown in Settings). */
    var vodCacheFolder: File? = null
        private set

    /** Folder actually used by the HLS live segment cache. */
    var liveHlsCacheFolder: File? = null
        private set

    /**
     * Rolling disk cache for movies and episodes. When it is full, the least recently
     * used pieces are deleted automatically. Only one may exist per folder, so the folder
     * choice takes effect after the app restarts.
     */
    val vodCache: SimpleCache by lazy {
        openCache(Folders.BUFFER_MOVIES, storage.vodCacheMb.toLong()) { vodCacheFolder = it }
    }

    /** Rolling disk cache for live HLS (.m3u8) segments, used when the live disk buffer is on. */
    val liveHlsCache: SimpleCache by lazy {
        openCache(Folders.BUFFER_LIVE_HLS, storage.liveDiskMb.toLong()) { liveHlsCacheFolder = it }
    }

    private fun openCache(sub: String, sizeMb: Long, used: (File) -> Unit): SimpleCache {
        val evictor = { LeastRecentlyUsedCacheEvictor(sizeMb * 1024L * 1024L) }
        val preferred = Folders.bufferDir(this, sub, storage.usePublicFolder)
        return try {
            SimpleCache(preferred, evictor(), databaseProvider).also { used(preferred) }
        } catch (e: Exception) {
            val fallback = File(cacheDir, sub).apply { mkdirs() }
            SimpleCache(fallback, evictor(), databaseProvider).also { used(fallback) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        com.nova.iptv.download.Sidecar.dir = java.io.File(filesDir, "dl-progress")
        storage = Storage(this)
        Folders.customRoot = storage.storageRoot
        com.nova.iptv.data.Locations.init(this, storage)
        DownloadCenter.init(storage)
        com.nova.iptv.data.CrashLog.install(this)
        com.nova.iptv.data.StreamCheck.init(this)
        com.nova.iptv.data.PlaybackLog.init(this)
    }
}
