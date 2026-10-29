package com.ppxb.miwaku.core.data.image

import android.content.Context
import android.graphics.BitmapFactory
import com.ppxb.miwaku.core.network.ApiFailure
import com.ppxb.miwaku.core.network.await
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class PageImage(
    val chapterId: String,
    val fileName: String,
    val index: Int,
    val splitCount: Int,
    val url: String,
) {
    val cacheKey: String get() = "$chapterId/$fileName"
}

data class PageSize(
    val width: Int,
    val height: Int,
    val stamp: Long = 0L,
)

/**
 * On-disk cache for original page bytes. Decoding, unscrambling and downsampling stay in Coil;
 * this store only makes sure a page is downloaded once and gives the reader its true dimensions
 * before the image is composed.
 */
class PageImageStore(
    context: Context,
    private val userAgent: String,
    private val httpClient: OkHttpClient,
    private val alternateImageHost: suspend () -> String? = { null },
    private val cacheLimitBytes: () -> Long = { DEFAULT_CACHE_LIMIT_BYTES },
) {
    private val cacheRoot = File(context.cacheDir, "$CACHE_DIR/v$CACHE_VERSION")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val inFlight = HashMap<String, Flight>()
    private val flightsLock = Any()
    private val trimming = AtomicBoolean(false)
    private val downloadsSinceTrim = AtomicInteger(0)
    private val sizeKeys = LinkedHashSet<String>()
    private val sizeLock = Any()
    private val _sizes = MutableStateFlow<Map<String, PageSize>>(emptyMap())
    val sizes: StateFlow<Map<String, PageSize>> = _sizes.asStateFlow()

    init {
        scope.launch { trim() }
    }

    fun cachedFile(image: PageImage): File? = fileFor(image).takeIf { it.isFile && it.length() > 0 }

    fun sizeOf(image: PageImage): PageSize? = _sizes.value[image.cacheKey]

    suspend fun prepare(image: PageImage): File =
        withContext(Dispatchers.IO) {
            cachedFile(image)?.let { file ->
                val size = readSize(file)
                if (size != null) {
                    recordSize(image, size)
                    file.setLastModified(System.currentTimeMillis())
                    if (file.isFile && file.length() > 0) {
                        ReaderPerf.log { "page_cache_hit key=${image.cacheKey}" }
                        return@withContext file
                    }
                } else {
                    file.delete()
                }
            }

            try {
                while (true) {
                    val flight = joinFlight(image)
                    try {
                        val file = flight.deferred.await()
                        if (file.isFile && file.length() > 0) {
                            return@withContext file
                        }
                        throw IOException("page file disappeared")
                    } catch (e: CancellationException) {
                        // Either this caller was cancelled or the shared flight was dropped by
                        // its last waiter. The check below tells the two apart.
                    } finally {
                        leaveFlight(image, flight)
                    }
                    currentCoroutineContext().ensureActive()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReaderPerf.log { "page_failed key=${image.cacheKey} msg=${e.message}" }
                throw e
            }
            throw IOException("page download did not complete")
        }

    private fun joinFlight(image: PageImage): Flight =
        synchronized(flightsLock) {
            val key = image.cacheKey
            val existing = inFlight[key]
            if (existing != null) {
                existing.waiters.incrementAndGet()
                return@synchronized existing
            }
            val candidate =
                scope.async(start = CoroutineStart.LAZY) {
                    downloadSlots.withPermit { download(image) }
                }
            val flight = Flight(candidate)
            flight.waiters.incrementAndGet()
            inFlight[key] = flight
            candidate.invokeOnCompletion {
                synchronized(flightsLock) {
                    if (inFlight[key] === flight) inFlight.remove(key)
                }
            }
            candidate.start()
            flight
        }

    /**
     * Drops one waiter and cancels the shared download when nobody is waiting anymore, so a
     * cancelled prefetch releases its download slot immediately instead of at the call timeout.
     */
    private fun leaveFlight(image: PageImage, flight: Flight) {
        if (flight.waiters.decrementAndGet() > 0) return
        synchronized(flightsLock) {
            if (inFlight[image.cacheKey] === flight) inFlight.remove(image.cacheKey)
        }
        flight.deferred.cancel()
    }

    private class Flight(val deferred: Deferred<File>) {
        val waiters = AtomicInteger(0)
    }

    suspend fun loadCachedSizes(images: List<PageImage>) =
        withContext(Dispatchers.IO) {
            val started = ReaderPerf.mark()
            val found = HashMap<String, PageSize>()
            for (image in images) {
                currentCoroutineContext().ensureActive()
                val file = cachedFile(image) ?: continue
                val size = readSize(file) ?: continue
                found[image.cacheKey] = size
            }
            if (found.isNotEmpty()) {
                synchronized(sizeLock) {
                    _sizes.update { current ->
                        var next = current
                        for ((key, size) in found) {
                            val existing = next[key]
                            if (existing == null || existing.width != size.width || existing.height != size.height) {
                                next = next + (key to PageSize(size.width, size.height, System.nanoTime()))
                            }
                        }
                        next
                    }
                    for (key in found.keys) {
                        touchSizeKey(key)
                    }
                }
            }
            ReaderPerf.log {
                "sizes_loaded count=${found.size} ms=${ReaderPerf.mark() - started}"
            }
        }

    fun invalidate(image: PageImage) {
        val key = image.cacheKey
        ReaderPerf.log { "page_invalidate key=$key" }
        val flight = synchronized(flightsLock) { inFlight.remove(key) }
        flight?.deferred?.cancel()
        fileFor(image).delete()
        synchronized(sizeLock) {
            _sizes.update { it - key }
            sizeKeys.remove(key)
        }
    }

    fun cacheSizeBytes(): Long =
        runCatching {
            cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    fun clearCache() {
        val flights =
            synchronized(flightsLock) {
                inFlight.values.toList().also { inFlight.clear() }
            }
        flights.forEach { it.deferred.cancel() }
        runCatching {
            cacheRoot.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        }
        synchronized(sizeLock) {
            _sizes.value = emptyMap()
            sizeKeys.clear()
        }
        ReaderPerf.log { "reader_cache_cleared" }
    }

    private suspend fun download(image: PageImage): File {
        val started = ReaderPerf.mark()
        val target = fileFor(image)
        target.parentFile?.mkdirs()
        val temp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            try {
                fetchTo(image.url, temp)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (!e.isAlternateHostRetryable()) throw e
                val retryUrl = alternateUrl(image) ?: throw e
                ReaderPerf.log {
                    "image_host_retry key=${image.cacheKey} from=${image.url.toHttpUrlOrNull()?.host} to=${retryUrl.toHttpUrlOrNull()?.host}"
                }
                fetchTo(retryUrl, temp)
            }

            currentCoroutineContext().ensureActive()
            if (temp.length() <= 0) {
                throw IOException("empty image")
            }
            val size = readSize(temp) ?: throw IOException("invalid image")
            if (!temp.renameTo(target)) {
                if (target.isFile && readSize(target) != null) {
                    temp.delete()
                    recordSize(image, size, refresh = true)
                    ReaderPerf.log {
                        "page_downloaded key=${image.cacheKey} ms=${ReaderPerf.mark() - started} bytes=${target.length()}"
                    }
                    return target
                }
                throw IOException("cannot store image")
            }
            recordSize(image, size, refresh = true)
            ReaderPerf.log {
                "page_downloaded key=${image.cacheKey} ms=${ReaderPerf.mark() - started} bytes=${target.length()}"
            }
            maybeTrim()
            return target
        } finally {
            if (temp.exists()) {
                temp.delete()
            }
        }
    }

    private suspend fun fetchTo(url: String, temp: File) {
        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "image/*")
                .build()
        val call = httpClient.newCall(request)
        call.timeout().timeout(IMAGE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val response =
            try {
                call.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw ApiFailure.Network(e.message ?: "image request failed", e)
            }
        response.use { res ->
            if (!res.isSuccessful) throw ApiFailure.Http(res.code)
            val body = res.body ?: throw ApiFailure.Decode("empty body")
            ReaderPerf.log {
                "page_http code=${res.code} protocol=${res.protocol} connection=${res.header("Connection")}"
            }
            if (body.contentLength() > MAX_IMAGE_BYTES) {
                throw ApiFailure.Decode("image too large")
            }
            try {
                body.byteStream().use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) {
                                throw ApiFailure.Decode("image too large")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } catch (e: IOException) {
                throw ApiFailure.Network(e.message ?: "image read failed", e)
            }
        }
    }

    private fun Throwable.isAlternateHostRetryable(): Boolean =
        (this as? ApiFailure)?.canRetryOnAlternateImageHost == true

    private suspend fun alternateUrl(image: PageImage): String? {
        val alternate = alternateImageHost()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val current = image.url.toHttpUrlOrNull() ?: return null
        val replacement = alternate.toHttpUrlOrNull() ?: return null
        if (current.host == replacement.host && current.port == replacement.port) return null
        return current
            .newBuilder()
            .scheme(replacement.scheme)
            .host(replacement.host)
            .port(replacement.port)
            .build()
            .toString()
    }

    private fun recordSize(image: PageImage, size: PageSize, refresh: Boolean = false) {
        synchronized(sizeLock) {
            _sizes.update { current ->
                val existing = current[image.cacheKey]
                if (!refresh &&
                    existing != null &&
                    existing.width == size.width &&
                    existing.height == size.height
                ) {
                    current
                } else {
                    current + (image.cacheKey to PageSize(size.width, size.height, System.nanoTime()))
                }
            }
            touchSizeKey(image.cacheKey)
        }
    }

    private fun touchSizeKey(key: String) {
        sizeKeys.remove(key)
        sizeKeys.add(key)
        while (sizeKeys.size > MAX_SIZE_ENTRIES) {
            val oldest = sizeKeys.first()
            sizeKeys.remove(oldest)
            _sizes.update { it - oldest }
        }
    }

    private fun maybeTrim() {
        if (downloadsSinceTrim.incrementAndGet() % TRIM_EVERY_DOWNLOADS != 0) return
        if (!trimming.compareAndSet(false, true)) return
        scope.launch {
            try {
                trim()
            } finally {
                trimming.set(false)
            }
        }
    }

    suspend fun trimToLimit() {
        if (!trimming.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.IO) { trim() }
        } finally {
            trimming.set(false)
        }
    }

    private fun trim() {
        val now = System.currentTimeMillis()
        val allFiles = cacheRoot.walkTopDown().filter { it.isFile }.toList()
        allFiles
            .filter { it.name.endsWith(".tmp") && now - it.lastModified() > TEMP_MAX_AGE_MS }
            .forEach { it.delete() }
        val files = allFiles.filter { it.isFile && !it.name.endsWith(".tmp") }
        if (files.isEmpty()) return
        val configured = cacheLimitBytes().coerceAtLeast(MIN_CACHE_BYTES)
        val budget =
            minOf(
                configured,
                maxOf(MIN_CACHE_BYTES, cacheRoot.usableSpace / CACHE_FREE_SPACE_DIVISOR),
            )
        var total = files.sumOf { it.length() }
        if (total <= budget) return
        val target = budget * 9 / 10
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= target) break
            if (now - file.lastModified() < RECENT_FILE_GRACE_MS) continue
            val length = file.length()
            if (file.delete()) {
                total -= length
            }
        }
    }

    private fun fileFor(image: PageImage): File {
        val chapter =
            image.chapterId
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
                .ifEmpty { "chapter" }
        val name = sha1(image.fileName).take(16)
        val extension =
            image.fileName
                .substringAfterLast('.', "")
                .lowercase()
                .takeIf { it in IMAGE_EXTENSIONS }
                ?: "img"
        return File(File(cacheRoot, chapter), "${image.index.toString().padStart(4, '0')}_$name.$extension")
    }

    private fun readSize(file: File): PageSize? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            PageSize(options.outWidth, options.outHeight)
        } else {
            null
        }
    }

    private fun sha1(value: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val CACHE_DIR = "reader_pages"
        const val CACHE_VERSION = 1
        const val MAX_CONCURRENT_DOWNLOADS = 3
        const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
        const val IMAGE_CALL_TIMEOUT_MS = 60_000L
        const val DEFAULT_CACHE_LIMIT_BYTES = 1024L * 1024 * 1024
        const val MIN_CACHE_BYTES = 256L * 1024 * 1024
        const val CACHE_FREE_SPACE_DIVISOR = 4L
        const val RECENT_FILE_GRACE_MS = 10 * 60 * 1000L
        const val TEMP_MAX_AGE_MS = 60 * 60 * 1000L
        const val TRIM_EVERY_DOWNLOADS = 20
        const val MAX_SIZE_ENTRIES = 4096
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
}
