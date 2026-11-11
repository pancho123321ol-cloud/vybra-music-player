package com.vybra.music.playback

import com.vybra.music.data.db.Song
import com.vybra.music.playback.engine.MediaItem

fun Song.toMediaItem(): MediaItem = MediaItem(
    mediaId = id.toString(),
    path = path,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    albumId = albumId,
    durationMs = duration,
)

/** Información ligera de la canción actual para la interfaz. */
data class NowPlaying(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
)

fun MediaItem.toNowPlaying(): NowPlaying? {
    val id = mediaId.toLongOrNull() ?: return null
    return NowPlaying(
        songId = id,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
    )
}
