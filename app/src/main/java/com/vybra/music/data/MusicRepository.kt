package com.vybra.music.data

import com.vybra.music.data.db.Album
import com.vybra.music.data.db.Artist
import com.vybra.music.data.db.ArtistPlays
import com.vybra.music.data.db.Favorite
import com.vybra.music.data.db.HistoryEntry
import com.vybra.music.data.db.PlayEvent
import com.vybra.music.data.db.VybraDatabase
import com.vybra.music.data.db.Song
import com.vybra.music.data.db.SongPlays
import com.vybra.music.data.prefs.SortOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

data class ListeningStats(
    val totalPlays: Int = 0,
    val totalListenedMs: Long = 0,
    val distinctSongs: Int = 0,
    val playsLast7Days: Int = 0,
    val listenedLast7DaysMs: Long = 0,
    val topSongs: List<SongPlays> = emptyList(),
    val topArtists: List<ArtistPlays> = emptyList(),
)

class MusicRepository(private val db: VybraDatabase, scope: CoroutineScope) {
    private val songDao = db.songDao()
    private val favoriteDao = db.favoriteDao()
    private val historyDao = db.historyDao()

    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }

    /** Toda la biblioteca, compartida entre pantallas para evitar consultas repetidas. */
    val songs: StateFlow<List<Song>?> = songDao.observeAll()
        .stateIn(scope, SharingStarted.WhileSubscribed(10_000), null)

    val songCount: Flow<Int> = songDao.observeCount()
    val albums: Flow<List<Album>> = songDao.observeAlbums()
    val artists: Flow<List<Artist>> = songDao.observeArtists()
    fun recentlyAdded(limit: Int = 50): Flow<List<Song>> = songDao.observeRecentlyAdded(limit)

    fun album(id: Long): Flow<Album?> = songDao.observeAlbum(id)
    fun albumSongs(id: Long): Flow<List<Song>> = songDao.observeAlbumSongs(id)
    fun artistSongs(name: String): Flow<List<Song>> = songDao.observeArtistSongs(name)
    fun artistAlbums(name: String): Flow<List<Album>> = songDao.observeAlbumsByArtist(name)

    suspend fun song(id: Long): Song? = songDao.byId(id)

    /** Devuelve las canciones en el mismo orden que [ids], omitiendo las que ya no existen. */
    suspend fun songsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val map = HashMap<Long, Song>(ids.size)
        ids.distinct().chunked(900).forEach { chunk -> songDao.byIds(chunk).forEach { map[it.id] = it } }
        return ids.mapNotNull { map[it] }
    }

    // ---------- Ordenación ----------

    fun sortSongs(list: List<Song>, option: SortOption, ascending: Boolean): List<Song> {
        val comparator: Comparator<Song> = when (option) {
            SortOption.TITLE -> compareBy(collator) { it.title }
            SortOption.ARTIST -> compareBy<Song, String>(collator) { it.artist }.thenBy(collator) { it.album }.thenBy { it.track }
            SortOption.ALBUM -> compareBy<Song, String>(collator) { it.album }.thenBy { it.track }.thenBy(collator) { it.title }
            SortOption.DATE_ADDED -> compareBy<Song> { it.dateAdded }.thenBy { it.id }
        }
        val sorted = list.sortedWith(comparator)
        return if (ascending) sorted else sorted.reversed()
    }

    fun sortAlbums(list: List<Album>, option: SortOption, ascending: Boolean): List<Album> {
        val comparator: Comparator<Album> = when (option) {
            SortOption.TITLE, SortOption.ALBUM -> compareBy(collator) { it.title }
            SortOption.ARTIST -> compareBy<Album, String>(collator) { it.artist }.thenBy(collator) { it.title }
            SortOption.DATE_ADDED -> compareBy { it.dateAdded }
        }
        val sorted = list.sortedWith(comparator)
        return if (ascending) sorted else sorted.reversed()
    }

    fun sortArtists(list: List<Artist>, ascending: Boolean): List<Artist> {
        val sorted = list.sortedWith(compareBy(collator) { it.name })
        return if (ascending) sorted else sorted.reversed()
    }

    // ---------- Favoritos ----------

    val favorites: Flow<List<Song>> = favoriteDao.observeFavoriteSongs()
    val favoriteIds: Flow<Set<Long>> = favoriteDao.observeFavoriteIds().map { it.toHashSet() }
    fun isFavorite(songId: Long): Flow<Boolean> = favoriteDao.observeIsFavorite(songId)

    suspend fun toggleFavorite(songId: Long): Boolean {
        return if (favoriteDao.isFavorite(songId)) {
            favoriteDao.delete(songId); false
        } else {
            favoriteDao.insert(Favorite(songId)); true
        }
    }

    // ---------- Historial y estadísticas ----------

    fun recentlyPlayed(limit: Int = 20): Flow<List<Song>> = historyDao.observeRecentlyPlayed(limit)
    fun history(limit: Int = 500): Flow<List<HistoryEntry>> = historyDao.observeHistory(limit)
    fun mostPlayed(limit: Int = 50): Flow<List<SongPlays>> = historyDao.observeMostPlayed(limit)

    suspend fun recordPlay(songId: Long): Long =
        historyDao.insert(PlayEvent(songId = songId, playedAt = System.currentTimeMillis()))

    suspend fun updateListened(eventId: Long, listenedMs: Long) = historyDao.updateListened(eventId, listenedMs)
    suspend fun deleteHistoryEntry(eventId: Long) = historyDao.delete(eventId)
    suspend fun clearHistory() = historyDao.clear()

    fun stats(): Flow<ListeningStats> {
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val totals = combine(
            historyDao.observeTotalPlays(),
            historyDao.observeTotalListened(),
            historyDao.observeDistinctSongs(),
            historyDao.observePlaysSince(weekAgo),
            historyDao.observeListenedSince(weekAgo),
        ) { plays, listened, distinct, weekPlays, weekListened ->
            ListeningStats(plays, listened, distinct, weekPlays, weekListened)
        }
        return combine(totals, historyDao.observeMostPlayed(10), historyDao.observeTopArtists(5)) { t, songs, artists ->
            t.copy(topSongs = songs, topArtists = artists)
        }
    }

    companion object {
        /** Normaliza texto para búsquedas sin distinguir mayúsculas ni acentos. */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.getDefault())
    }
}
