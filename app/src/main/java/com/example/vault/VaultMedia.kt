package com.example.vault

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException

/** Обёртка, по которой Coil понимает, что файл лежит в хранилище и его нужно расшифровать. */
class VaultModel(val file: File)

class VaultKeyer : Keyer<VaultModel> {
    override fun key(data: VaultModel, options: Options): String =
        "vault:" + data.file.path + ":" + data.file.lastModified()
}

class VaultFetcherFactory : Fetcher.Factory<VaultModel> {
    override fun create(data: VaultModel, options: Options, imageLoader: ImageLoader): Fetcher =
        VaultFetcher(data.file, options)
}

class VaultFetcher(private val file: File, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        if (Fs.isVideo(file)) {
            val bmp = VaultCrypto.videoThumb(file) ?: throw IOException("Нет кадра")
            DrawableResult(
                drawable = BitmapDrawable(options.context.resources, bmp),
                isSampled = true,
                dataSource = DataSource.DISK
            )
        } else {
            val stream = VaultCrypto.openStream(file)
            SourceResult(
                source = ImageSource(stream.source().buffer(), options.context),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        }
    }
}

/** Источник данных для видеоплеера: читает файл хранилища и расшифровывает на лету, только в памяти. */
@UnstableApi
class VaultDataSource : BaseDataSource(false) {
    private var current: Uri? = null
    private var reader: VaultReader? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        current = dataSpec.uri
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw IOException("Нет пути к файлу")
        val r = VaultCrypto.openReader(File(path))
        reader = r
        pos = dataSpec.position
        if (pos > r.plainLen) {
            r.close()
            throw IOException("Позиция за пределами файла")
        }
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, r.plainLen - pos)
        } else {
            r.plainLen - pos
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("Источник закрыт")
        val n = r.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = current

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            current = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
