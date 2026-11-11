package com.vybra.music.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.vybra.music.data.db.Playlist
import com.vybra.music.data.db.PlaylistInfo
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.db.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PlaylistRepository(private val context: Context, db: VybraDatabase) {
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
    suspend fun setCover(id: Long, imageUri: Uri?): Boolean = withContext(Dispatchers.IO) {
        val p = dao.playlist(id) ?: return@withContext false
        p.coverPath?.let { File(it).delete() }
        if (imageUri == null) {
            dao.update(p.copy(coverPath = null, updatedAt = System.currentTimeMillis()))
            return@withContext true
        }
        val bitmap = runCatching { decode(imageUri) }.getOrNull() ?: return@withContext false
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "playlist_${id}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        dao.update(p.copy(coverPath = file.absolutePath, updatedAt = System.currentTimeMillis()))
        true
    }

    private fun decode(uri: Uri): Bitmap {
        val maxSize = 1024
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxOf(w, h).toFloat() / maxSize
                if (scale > 1f) decoder.setTargetSize((w / scale).toInt(), (h / scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            val full = MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            val scale = maxOf(full.width, full.height).toFloat() / maxSize
            if (scale > 1f) Bitmap.createScaledBitmap(full, (full.width / scale).toInt(), (full.height / scale).toInt(), true) else full
        }
    }
}
