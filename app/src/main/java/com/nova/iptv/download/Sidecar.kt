package com.nova.iptv.download

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Progress files of the downloads ("….parts", "….pieces") are kept inside Nova's own storage, not next to the
 * video in Download: Android may refuse to let Nova open there a file it made before a reinstall (EACCES).
 */
object Sidecar {
    @Volatile var dir: File? = null

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun file(videoPath: String, ext: String): File {
        val d = dir ?: return File("$videoPath.$ext")
        d.mkdirs()
        val f = File(d, sha1(videoPath) + "." + ext)
        if (!f.exists()) {
            val old = File("$videoPath.$ext")
            if (old.exists()) runCatching { old.copyTo(f, overwrite = true); old.delete() }
        }
        return f
    }

    private fun writable(f: File) = runCatching { RandomAccessFile(f, "rw").close(); true }.getOrDefault(false)

    /**
     * The video's own file: when it already exists but can't be written (made by an earlier install),
     * it is removed, or a new name is used: "Film (2).ts".
     */
    fun usablePath(path: String): String {
        val f = File(path)
        if (!f.exists() || writable(f)) return path
        runCatching { f.delete() }
        if (!f.exists()) return path
        val base = path.substringBeforeLast('.')
        val ext = path.substringAfterLast('.', "")
        for (i in 2..50) {
            val p = "$base ($i)" + if (ext.isNotEmpty()) ".$ext" else ""
            val g = File(p)
            if (!g.exists() || writable(g)) return p
        }
        return path
    }
}
