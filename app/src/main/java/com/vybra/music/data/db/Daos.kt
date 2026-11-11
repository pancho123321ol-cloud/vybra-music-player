package com.vybra.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs")
    fun observeAll(): Flow<List<Song>>

    @Query("SELECT COUNT(*) FROM songs")
    fun observeCount(): Flow<Int>

    @Query("SELECT id FROM songs")
    suspend fun allIds(): List<Long>

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Song>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun byId(id: Long): Song?

    @Upsert
    suspend fun upsertAll(songs: List<Song>)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM songs ORDER BY dateAdded DESC, id DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<Song>>

    @Query(
        """
        SELECT albumId AS id, MAX(album) AS title,
               COALESCE(NULLIF(MAX(albumArtist), ''), MAX(artist)) AS artist,
               COUNT(*) AS songCount, MAX(year) AS year, MIN(id) AS coverSongId,
               SUM(duration) AS duration, MAX(dateAdded) AS dateAdded
        FROM songs GROUP BY albumId
        """
    )
    fun observeAlbums(): Flow<List<Album>>

    @Query(
        """
        SELECT albumId AS id, MAX(album) AS title,
               COALESCE(NULLIF(MAX(albumArtist), ''), MAX(artist)) AS artist,
               COUNT(*) AS songCount, MAX(year) AS year, MIN(id) AS coverSongId,
               SUM(duration) AS duration, MAX(dateAdded) AS dateAdded
        FROM songs WHERE albumId = :albumId GROUP BY albumId
        """
    )
    fun observeAlbum(albumId: Long): Flow<Album?>

    @Query(
        """
        SELECT albumId AS id, MAX(album) AS title,
               COALESCE(NULLIF(MAX(albumArtist), ''), MAX(artist)) AS artist,
               COUNT(*) AS songCount, MAX(year) AS year, MIN(id) AS coverSongId,
               SUM(duration) AS duration, MAX(dateAdded) AS dateAdded
        FROM songs WHERE artist = :artist OR albumArtist = :artist
        GROUP BY albumId ORDER BY year DESC, title COLLATE NOCASE
        """
    )
    fun observeAlbumsByArtist(artist: String): Flow<List<Album>>

    @Query(
        """
        SELECT artist AS name, COUNT(*) AS songCount, COUNT(DISTINCT albumId) AS albumCount,
               MIN(id) AS coverSongId, albumId AS coverAlbumId
        FROM songs GROUP BY artist
        """
    )
    fun observeArtists(): Flow<List<Artist>>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY track, title COLLATE NOCASE")
    fun observeAlbumSongs(albumId: Long): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE artist = :artist OR albumArtist = :artist ORDER BY album COLLATE NOCASE, track")
    fun observeArtistSongs(artist: String): Flow<List<Song>>
}

@Dao
interface FavoriteDao {
    @Query("SELECT s.* FROM favorites f INNER JOIN songs s ON s.id = f.songId ORDER BY f.addedAt DESC")
    fun observeFavoriteSongs(): Flow<List<Song>>

    @Query("SELECT songId FROM favorites")
    fun observeFavoriteIds(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    fun observeIsFavorite(songId: Long): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    suspend fun isFavorite(songId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: Favorite)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun delete(songId: Long)
}

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.*,
          (SELECT COUNT(*) FROM playlist_songs ps INNER JOIN songs s ON s.id = ps.songId
             WHERE ps.playlistId = p.id) AS songCount,
          (SELECT s.id FROM playlist_songs ps INNER JOIN songs s ON s.id = ps.songId
             WHERE ps.playlistId = p.id ORDER BY ps.position LIMIT 1) AS firstSongId,
          (SELECT s.albumId FROM playlist_songs ps INNER JOIN songs s ON s.id = ps.songId
             WHERE ps.playlistId = p.id ORDER BY ps.position LIMIT 1) AS firstAlbumId
        FROM playlists p ORDER BY p.updatedAt DESC
        """
    )
    fun observePlaylists(): Flow<List<PlaylistInfo>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: Long): Flow<Playlist?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun playlist(id: Long): Playlist?

    @Query(
        """
        SELECT s.* FROM playlist_songs ps INNER JOIN songs s ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId ORDER BY ps.position
        """
    )
    fun observePlaylistSongs(playlistId: Long): Flow<List<Song>>

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    suspend fun songIds(playlistId: Long): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongs(entries: List<PlaylistSong>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: Long)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun setPosition(playlistId: Long, songId: Long, position: Int)

    @Query("UPDATE playlists SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long = System.currentTimeMillis())

    @Transaction
    suspend fun addSongs(playlistId: Long, songIds: List<Long>): Int {
        val existing = songIds(playlistId).toHashSet()
        var position = maxPosition(playlistId)
        val newEntries = songIds.distinct().filterNot { it in existing }.map {
            position += 1
            PlaylistSong(playlistId = playlistId, songId = it, position = position)
        }
        if (newEntries.isNotEmpty()) insertSongs(newEntries)
        touch(playlistId)
        return newEntries.size
    }

    @Transaction
    suspend fun reorder(playlistId: Long, orderedSongIds: List<Long>) {
        orderedSongIds.forEachIndexed { index, songId -> setPosition(playlistId, songId, index) }
        touch(playlistId)
    }
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(event: PlayEvent): Long

    @Query("UPDATE play_events SET listenedMs = :listenedMs WHERE id = :id")
    suspend fun updateListened(id: Long, listenedMs: Long)

    @Query(
        """
        SELECT s.* FROM songs s INNER JOIN
          (SELECT songId, MAX(playedAt) AS lastPlayed FROM play_events GROUP BY songId) h
          ON s.id = h.songId
        ORDER BY h.lastPlayed DESC LIMIT :limit
        """
    )
    fun observeRecentlyPlayed(limit: Int): Flow<List<Song>>

    @Query(
        """
        SELECT s.*, e.id AS eventId, e.playedAt AS playedAt
        FROM play_events e INNER JOIN songs s ON s.id = e.songId
        ORDER BY e.playedAt DESC LIMIT :limit
        """
    )
    fun observeHistory(limit: Int): Flow<List<HistoryEntry>>

    @Query(
        """
        SELECT s.*, COUNT(e.id) AS plays FROM play_events e INNER JOIN songs s ON s.id = e.songId
        GROUP BY s.id ORDER BY plays DESC, MAX(e.playedAt) DESC LIMIT :limit
        """
    )
    fun observeMostPlayed(limit: Int): Flow<List<SongPlays>>

    @Query(
        """
        SELECT s.artist AS artist, COUNT(e.id) AS plays, MIN(s.id) AS coverSongId, s.albumId AS coverAlbumId
        FROM play_events e INNER JOIN songs s ON s.id = e.songId
        GROUP BY s.artist ORDER BY plays DESC LIMIT :limit
        """
    )
    fun observeTopArtists(limit: Int): Flow<List<ArtistPlays>>

    @Query("SELECT COUNT(*) FROM play_events")
    fun observeTotalPlays(): Flow<Int>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM play_events")
    fun observeTotalListened(): Flow<Long>

    @Query("SELECT COUNT(DISTINCT songId) FROM play_events")
    fun observeDistinctSongs(): Flow<Int>

    @Query("SELECT COUNT(*) FROM play_events WHERE playedAt >= :since")
    fun observePlaysSince(since: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(listenedMs), 0) FROM play_events WHERE playedAt >= :since")
    fun observeListenedSince(since: Long): Flow<Long>

    @Query("DELETE FROM play_events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM play_events")
    suspend fun clear()
}
