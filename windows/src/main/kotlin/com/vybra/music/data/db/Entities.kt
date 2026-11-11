package com.vybra.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Canción local indexada desde las carpetas de música. El id es un hash estable de la ruta. */
@Entity(
    tableName = "songs",
    indices = [Index("albumId"), Index("artist"), Index("dateAdded"), Index("folder")],
)
data class Song(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val albumArtist: String?,
    val duration: Long,
    val track: Int,
    val year: Int,
    val path: String,
    val folder: String,
    /** Segundos desde epoch (MediaStore.DATE_ADDED). */
    val dateAdded: Long,
    val size: Long,
    val mimeType: String?,
) {
    val file: java.io.File get() = java.io.File(path)
}

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Ruta absoluta de una portada personalizada guardada en el almacenamiento interno. */
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("songId")],
)
data class PlaylistSong(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey val songId: Long,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "play_events", indices = [Index("songId"), Index("playedAt")])
data class PlayEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
    val listenedMs: Long = 0,
)

// ---------- Proyecciones ----------

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songCount: Int,
    val year: Int,
    val coverSongId: Long,
    val duration: Long,
    val dateAdded: Long,
)

data class Artist(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val coverSongId: Long,
    val coverAlbumId: Long,
)

data class PlaylistInfo(
    @Embedded val playlist: Playlist,
    val songCount: Int,
    val firstSongId: Long?,
    val firstAlbumId: Long?,
)

data class HistoryEntry(
    @Embedded val song: Song,
    @ColumnInfo(name = "eventId") val eventId: Long,
    @ColumnInfo(name = "playedAt") val playedAt: Long,
)

data class SongPlays(
    @Embedded val song: Song,
    val plays: Int,
)

data class ArtistPlays(
    val artist: String,
    val plays: Int,
    val coverSongId: Long,
    val coverAlbumId: Long,
)
