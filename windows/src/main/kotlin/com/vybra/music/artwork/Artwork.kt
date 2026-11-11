package com.vybra.music.artwork

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.vybra.music.media.AudioTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.File
import java.io.IOException
import java.util.Collections

/** Referencia a la carátula de una canción/álbum, usada como modelo de imagen en Coil. */
data class ArtworkRequest(val songId: Long, val albumId: Long)

object ArtworkLoader {
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Resuelve la ruta del archivo de una canción (lo asigna el contenedor de la app). */
    @Volatile var pathOf: suspend (Long) -> String? = { null }

    fun key(request: ArtworkRequest): String =
        if (request.albumId > 0) "album:${request.albumId}" else "song:${request.songId}"

    fun clearMissingCache() = missing.clear()

    private val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart", "albumartsmall")
    private val IMAGE_EXT = listOf("jpg", "jpeg", "png", "webp")

    /**
     * Obtiene la carátula en máxima calidad disponible: primero la imagen incrustada en el
     * archivo; si no existe, una imagen de portada en la carpeta del álbum (cover.jpg, folder.jpg…).
     */
    suspend fun loadBytes(request: ArtworkRequest): ByteArray? {
        val key = key(request)
        if (key in missing) return null
        val path = pathOf(request.songId) ?: return null
        val file = File(path)
        AudioTags.embeddedArtwork(file)?.let { return it }
        val dir = file.parentFile
        if (dir != null && dir.isDirectory) {
            val images = dir.listFiles { f -> f.isFile && f.extension.lowercase() in IMAGE_EXT }.orEmpty()
            val cover = COVER_NAMES.firstNotNullOfOrNull { name ->
                images.firstOrNull { it.nameWithoutExtension.equals(name, ignoreCase = true) }
            } ?: images.firstOrNull { it.nameWithoutExtension.equals(file.nameWithoutExtension, ignoreCase = true) }
            if (cover != null) runCatching { cover.readBytes() }.getOrNull()?.let { return it }
        }
        missing += key
        return null
    }
}

/** Fetcher de Coil que extrae las carátulas desde los archivos locales. */
class ArtworkFetcher(
    private val data: ArtworkRequest,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = withContext(Dispatchers.IO) { ArtworkLoader.loadBytes(data) }
            ?: throw IOException("Sin carátula")
        return SourceFetchResult(
            source = ImageSource(source = Buffer().write(bytes), fileSystem = options.fileSystem),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ArtworkRequest> {
        override fun create(data: ArtworkRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtworkFetcher(data, options)
    }
}

class ArtworkKeyer : Keyer<ArtworkRequest> {
    override fun key(data: ArtworkRequest, options: Options): String = ArtworkLoader.key(data)
}
