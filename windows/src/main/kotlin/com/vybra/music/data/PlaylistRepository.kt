package com.vybra.music.data

import com.vybra.music.AppDirs
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import com.vybra.music.data.db.Playlist
import com.vybra.music.data.db.PlaylistInfo
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PlaylistRepository(db: VybraDatabase) {
    private val dao = db.playlistDao()

    val playlists: Flow<List<PlaylistInfo>> = dao.observePlaylists()
    fun playlist(id: Long): Flow<Playlist?> = dao.observePlaylist(id)
    fun songs(id: Long): Flow<List<Song>> = dao.observePlaylistSongs(id)

    suspend fun create(name: String, songIds: List<Long> = emptyList()): Long {
        val id = dao.insert(Playlist(name = name.trim().ifEmpty { "Nueva playlist" }))
        if (songIds.isNotEmpty()) dao.addSongs(id, songIds)
        return id
    }

    suspend fun rename(id: Long, name: String) {
        val p = dao.playlist(id) ?: return
        dao.update(p.copy(name = name.trim().ifEmpty { p.name }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) {
        dao.playlist(id)?.coverPath?.let { File(it).delete() }
        dao.delete(id)
    }

    suspend fun addSongs(id: Long, songIds: List<Long>): Int = dao.addSongs(id, songIds)
    suspend fun removeSong(id: Long, songId: Long) {
        dao.removeSong(id, songId)
        dao.touch(id)
    }
    suspend fun reorder(id: Long, orderedIds: List<Long>) = dao.reorder(id, orderedIds)

    /** Copia la imagen elegida al almacenamiento interno (reducida) y la asigna como portada. */
    suspend fun setCover(id: Long, imageUri: File?): Boolean = withContext(Dispatchers.IO) {
        val p = dao.playlist(id) ?: return@withContext false
        p.coverPath?.let { File(it).delete() }
        if (imageUri == null) {
            dao.update(p.copy(coverPath = null, updatedAt = System.currentTimeMillis()))
            return@withContext true
        }
        val bitmap = runCatching { decode(imageUri) }.getOrNull() ?: return@withContext false
        val dir = File(AppDirs.data, "covers").apply { mkdirs() }
        val file = File(dir, "playlist_${id}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { ImageIO.write(bitmap, "jpg", it) }
        dao.update(p.copy(coverPath = file.absolutePath, updatedAt = System.currentTimeMillis()))
        true
    }

    private fun decode(file: File): BufferedImage? {
        val maxSize = 1024
        val src = ImageIO.read(file) ?: return null
        val scale = maxOf(src.width, src.height).toFloat() / maxSize
        val w = if (scale > 1f) (src.width / scale).toInt() else src.width
        val h = if (scale > 1f) (src.height / scale).toInt() else src.height
        // JPEG no admite transparencia: se dibuja sobre un lienzo RGB.
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }
}
