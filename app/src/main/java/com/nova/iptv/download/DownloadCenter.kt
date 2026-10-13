package com.nova.iptv.download

import android.content.Context
import com.nova.iptv.data.DownloadItem
import com.nova.iptv.data.DownloadStatus
import com.nova.iptv.data.Folders
import com.nova.iptv.data.ItemType
import com.nova.iptv.data.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

/** The list of downloads and recordings, shared by the screens, the player and the download service. */
object DownloadCenter {

    private lateinit var storage: Storage
    private val lock = Any()
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items
    private var lastSave = 0L

    fun init(storage: Storage) {
        this.storage = storage
        // After a restart nothing is running any more.
        _items.value = storage.downloads.map {
            when (it.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> it.copy(status = DownloadStatus.PAUSED)
                DownloadStatus.RECORDING -> it.copy(status = DownloadStatus.DONE, doneBytes = File(it.path).length())
                else -> it
            }
        }
        storage.downloads = _items.value
    }

    fun get(id: String): DownloadItem? = _items.value.firstOrNull { it.id == id }

    fun add(item: DownloadItem) {
        synchronized(lock) {
            _items.value = listOf(item) + _items.value
            storage.downloads = _items.value
        }
    }

    fun update(id: String, persist: Boolean = true, change: (DownloadItem) -> DownloadItem) {
        synchronized(lock) {
            _items.value = _items.value.map { if (it.id == id) change(it) else it }
            val now = System.currentTimeMillis()
            if (persist || now - lastSave > 5000) {
                storage.downloads = _items.value
                lastSave = now
            }
        }
    }

    fun remove(id: String, deleteFile: Boolean) {
        val item = get(id) ?: return
        synchronized(lock) {
            _items.value = _items.value.filterNot { it.id == id }
            storage.downloads = _items.value
        }
        if (deleteFile) runCatching { File(item.path).delete() }
    }

    /** A download being watched while it downloads: it goes first. */
    @Volatile private var priorityId: String? = null

    /**
     * Two lanes run side by side: one download from your IPTV servers (they allow few connections)
     * and one from other web links (they don't use your account's connections).
     */
    fun isServer(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        return runCatching { storage.accounts }.getOrDefault(emptyList()).any { a ->
            runCatching { java.net.URI(com.nova.iptv.data.XtreamApi.normalizeServer(a.server)).host }.getOrNull() == host
        }
    }

    /** Oldest waiting download of this lane (the list is newest first); a "Download & watch" one goes first. */
    fun nextQueued(server: Boolean): DownloadItem? {
        priorityId?.let { id ->
            _items.value.firstOrNull { it.id == id && it.status == DownloadStatus.QUEUED && isServer(it.url) == server }?.let { return it }
        }
        return _items.value.lastOrNull { it.status == DownloadStatus.QUEUED && isServer(it.url) == server }
    }

    fun nextQueued(): DownloadItem? = nextQueued(true) ?: nextQueued(false)

    /** For a waiting download: "another download is finishing (27 s left)". */
    fun waitingNote(d: DownloadItem): String {
        val lane = isServer(d.url)
        val running = _items.value.firstOrNull { it.id != d.id && it.status == DownloadStatus.DOWNLOADING && isServer(it.url) == lane }
            ?: return "waiting for its turn"
        val left = if (running.speed > 0 && running.totalBytes > running.doneBytes) {
            val sec = (running.totalBytes - running.doneBytes) / running.speed
            " (" + (if (sec >= 3600) "${sec / 3600} h ${sec % 3600 / 60} min" else if (sec >= 60) "${sec / 60} min" else "$sec s") + " left)"
        } else ""
        return "waiting — another download is finishing$left"
    }

    /**
     * "Download & watch": starts (or resumes) this download right away. Another download that is running
     * waits in the queue meanwhile (it continues later from where it stopped).
     */
    fun watchNow(context: Context, title: String, url: String, ext: String, kind: String, icon: String?, subFolder: String = ""): DownloadItem {
        val item = _items.value.firstOrNull { it.url == url && it.status != DownloadStatus.FAILED }
            ?: run {
                val dir = com.nova.iptv.data.Locations.dirFor(
                    if (kind == ItemType.MOVIE) com.nova.iptv.data.Locations.Kind.MOVIES else com.nova.iptv.data.Locations.Kind.SERIES, subFolder,
                )
                val file = Folders.uniqueFile(dir, title, ext.ifBlank { "mp4" })
                DownloadItem(
                    id = UUID.randomUUID().toString(), title = title, kind = kind, url = url,
                    path = file.absolutePath, icon = icon, createdAt = System.currentTimeMillis(),
                    status = DownloadStatus.PAUSED,
                ).also { add(it) }
            }
        if (item.status == DownloadStatus.DONE) return item
        priorityId = item.id
        // Others that are downloading step back into the queue (they keep what they already have).
        val lane = isServer(item.url)
        _items.value.filter { it.id != item.id && it.status == DownloadStatus.DOWNLOADING && isServer(it.url) == lane }
            .forEach { other -> update(other.id) { o -> o.copy(status = DownloadStatus.QUEUED) } }
        if (get(item.id)?.status != DownloadStatus.DOWNLOADING) {
            update(item.id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        }
        DownloadService.start(context)
        return get(item.id) ?: item
    }

    fun enqueue(context: Context, title: String, url: String, ext: String, kind: String, icon: String?, subFolder: String = ""): DownloadItem {
        val existing = _items.value.firstOrNull { it.url == url && it.status != DownloadStatus.FAILED }
        if (existing != null) {
            if (existing.status == DownloadStatus.PAUSED) resume(context, existing.id)
            return existing
        }
        val dir = com.nova.iptv.data.Locations.dirFor(
                    if (kind == ItemType.MOVIE) com.nova.iptv.data.Locations.Kind.MOVIES else com.nova.iptv.data.Locations.Kind.SERIES, subFolder,
                )
        val file = Folders.uniqueFile(dir, title, ext.ifBlank { "mp4" })
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            title = title,
            kind = kind,
            url = url,
            path = file.absolutePath,
            icon = icon,
            createdAt = System.currentTimeMillis(),
        )
        add(item)
        DownloadService.start(context)
        return item
    }

    fun pause(id: String) = update(id) {
        if (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING) {
            it.copy(status = DownloadStatus.PAUSED, speed = 0)
        } else {
            it
        }
    }

    fun resume(context: Context, id: String) {
        update(id) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        DownloadService.start(context)
    }

    fun isDownloaded(url: String): Boolean =
        _items.value.any { it.url == url && it.status == DownloadStatus.DONE && File(it.path).exists() }
}
