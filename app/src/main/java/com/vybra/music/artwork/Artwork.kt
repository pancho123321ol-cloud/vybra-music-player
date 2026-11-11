package com.vybra.music.artwork

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Referencia a la carátula de una canción/álbum, usada como modelo de imagen en Coil. */
data class ArtworkRequest(val songId: Long, val albumId: Long)

object ArtworkLoader {
    private const val SCHEME = "vybra-art"
    private val missing: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun uriFor(songId: Long, albumId: Long): Uri =
        Uri.parse("$SCHEME://song/$songId?album=$albumId")

    fun parse(uri: Uri): ArtworkRequest? {
        if (uri.scheme != SCHEME) return null
        val songId = uri.lastPathSegment?.toLongOrNull() ?: return null
        val albumId = uri.getQueryParameter("album")?.toLongOrNull() ?: -1L
        return ArtworkRequest(songId, albumId)
    }

    fun key(request: ArtworkRequest): String =
        if (request.albumId > 0) "album:${request.albumId}" else "song:${request.songId}"

    fun clearMissingCache() = missing.clear()

    /**
     * Obtiene la carátula en máxima calidad disponible: primero la imagen incrustada en el
     * archivo; si no existe, la carátula que MediaStore tenga para el álbum.
     */
    fun loadBytes(context: Context, request: ArtworkRequest): ByteArray? {
        val key = key(request)
        if (key in missing) return null
        val songUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, request.songId)

        val embedded = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, songUri)
                retriever.embeddedPicture
            } finally {
                retriever.release()
            }
        }.getOrNull()
        if (embedded != null && embedded.isNotEmpty()) return embedded

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val thumb = runCatching {
                context.contentResolver.loadThumbnail(songUri, Size(1024, 1024), null)
            }.getOrNull()
            if (thumb != null) return thumb.toJpeg()
        } else if (request.albumId > 0) {
            val albumArt = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), request.albumId)
            val bytes = runCatching {
                context.contentResolver.openInputStream(albumArt)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        missing += key
        return null
    }

    fun decodeSampled(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun Bitmap.toJpeg(): ByteArray =
        ByteArrayOutputStream().use { out ->
            compress(Bitmap.CompressFormat.JPEG, 95, out)
            out.toByteArray()
        }
}

/** Fetcher de Coil que extrae las carátulas desde los archivos locales. */
class ArtworkFetcher(
    private val data: ArtworkRequest,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bytes = withContext(Dispatchers.IO) { ArtworkLoader.loadBytes(options.context, data) }
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

/** BitmapLoader de Media3 para mostrar la carátula en notificaciones y pantalla de bloqueo. */
@androidx.annotation.OptIn(UnstableApi::class)
class ArtworkBitmapLoader(private val context: Context) : BitmapLoader {
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        executor.submit(Callable {
            ArtworkLoader.decodeSampled(data, 1024) ?: throw IOException("No se pudo decodificar")
        })

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        executor.submit(Callable {
            val request = ArtworkLoader.parse(uri) ?: throw IOException("URI no soportada: $uri")
            val bytes = ArtworkLoader.loadBytes(context, request) ?: throw IOException("Sin carátula")
            ArtworkLoader.decodeSampled(bytes, 1024) ?: throw IOException("No se pudo decodificar")
        })
}
