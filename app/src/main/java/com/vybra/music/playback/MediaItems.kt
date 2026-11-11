package com.vybra.music.playback

import android.content.ContentUris
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.vybra.music.artwork.ArtworkLoader
import com.vybra.music.data.db.Song

const val EXTRA_ALBUM_ID = "vybra.albumId"

fun Song.toMediaItem(): MediaItem {
    val songUri = uri
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(songUri)
        .setMimeType(mimeType)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(songUri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(albumArtist)
                .setTrackNumber(track.takeIf { it > 0 })
                .setReleaseYear(year.takeIf { it > 0 })
                .setDurationMs(duration)
                .setArtworkUri(ArtworkLoader.uriFor(id, albumId))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putLong(EXTRA_ALBUM_ID, albumId) })
                .build(),
        )
        .build()
}

/** Completa la URI de reproducción de un item recibido desde un controlador. */
fun MediaItem.resolvePlayable(): MediaItem {
    if (localConfiguration != null) return this
    val uri = requestMetadata.mediaUri
        ?: mediaId.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }
        ?: return this
    return buildUpon().setUri(uri).build()
}

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
    val md = mediaMetadata
    return NowPlaying(
        songId = id,
        title = md.title?.toString() ?: "",
        artist = md.artist?.toString() ?: "",
        album = md.albumTitle?.toString() ?: "",
        albumId = md.extras?.getLong(EXTRA_ALBUM_ID, -1L) ?: -1L,
        durationMs = md.durationMs ?: 0L,
    )
}
