package com.ppxb.miwaku.core.data.image

import android.content.Context
import android.graphics.BitmapFactory
import com.ppxb.miwaku.core.network.ApiFailure
import com.ppxb.miwaku.core.network.RequestHeaders
import com.ppxb.miwaku.core.network.await
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.Call
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
 * Who is waiting for a page, highest urgency first. The store pumps flights in this order, so
 * `Retry` (a page the reader already failed to show) jumps ahead of everything else and `Download`
 * always yields to the reader.
 */
enum class PagePriority { Retry, Visible, Nearby, Behind, Warmup, Download }

/**
 * On-disk cache for original page bytes. Decoding, unscrambling and downsampling stay in Coil;
 * this store only makes sure a page is downloaded once and gives the reader its true dimensions
 * before the image is composed.
 */
class PageImageStore(
    context: Context,
    private val userAgent: String,
    private val httpClient: OkHttpClient,
    private val alternateImageHost: suspend (String?) -> String? = { null },
    private val localFile: (PageImage) -> File? = { null },
    private val cacheLimitBytes: () -> Long = { DEFAULT_CACHE_LIMIT_BYTES },
    // Transfer budgets are constructor parameters so a benchmark can compare configurations; the
    // defaults are the shipped values.
    private val readerTransfers: Int = READER_TRANSFERS,
    private val downloadTransfers: Int = DOWNLOAD_TRANSFERS,
    private val totalTransfers: Int = TOTAL_TRANSFERS,
) {
    private val cacheRoot = File(context.cacheDir, "$CACHE_DIR/v$CACHE_VERSION")
    private val sizeSnapshot = File(cacheRoot, SIZE_SNAPSHOT_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningTransfers = 0
    private var runningReaders = 0
    private var runningDownloads = 0
    private val inFlight = HashMap<String, Flight>()
    private val readyFiles = ConcurrentHashMap<String, File>()
    private val flightsLock = Any()
    private val maintenance = Mutex()
    @Volatile private var admissionPaused: CompletableDeferred<Unit>? = null
    private val trimming = AtomicBoolean(false)
    private val downloadsSinceTrim = AtomicInteger(0)
    private val sizeKeys = LinkedHashSet<String>()
    private val sizeLock = Any()
    private val _sizes = MutableStateFlow<Map<String, PageSize>>(emptyMap())
    val sizes: StateFlow<Map<String, PageSize>> = _sizes.asStateFlow()
    @Volatile private var sizePersistJob: Job? = null

    init {
        scope.launch {
            loadSizeSnapshot()
            trim()
        }
    }

    fun cachedFile(image: PageImage): File? {
        readyFiles[image.cacheKey]?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        localFile(image)?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        return fileFor(image).takeIf { it.isFile && it.length() > 0 }
    }

    fun sizeOf(image: PageImage): PageSize? = _sizes.value[image.cacheKey]

    suspend fun prepare(image: PageImage, priority: PagePriority = PagePriority.Nearby): File =
        prepare(image, flowOf(priority))

    suspend fun prepare(image: PageImage, priorities: Flow<PagePriority>): File =
        prepareInternal(image, priorities, target = null)

    /**
     * Downloads straight into [target] (the offline directory), so the durable copy never needs a
     * second write pass. When a reader flight for the same page is already running, its result is
     * copied into [target] instead of downloading the bytes again.
     */
    suspend fun prepareTo(
        image: PageImage,
        target: File,
        priority: PagePriority = PagePriority.Download,
    ): File = prepareTo(image, target, flowOf(priority))

    suspend fun prepareTo(
        image: PageImage,
        target: File,
        priorities: Flow<PagePriority>,
    ): File = prepareInternal(image, priorities, target)

    private suspend fun prepareInternal(
        image: PageImage,
        priorities: Flow<PagePriority>,
        target: File?,
    ): File =
        withContext(Dispatchers.IO) {
            admissionPaused?.await()
            if (target != null && target.isFile && target.length() > 0) {
                readSize(target)?.let { size ->
                    recordSize(image, size)
                    ReaderPerf.log { "page_download_hit key=${image.cacheKey}" }
                    return@withContext target
                }
            }
            localFile(image)?.let { file ->
                if (file.isFile && file.length() > 0) {
                    readSize(file)?.let { size ->
                        readyFiles[image.cacheKey] = file
                        recordSize(image, size)
                        ReaderPerf.log { "page_download_hit key=${image.cacheKey}" }
                        return@withContext if (target != null) adopt(file, target) else file
                    }
                }
            }

            cachedFile(image)?.let { file ->
                val size = readSize(file)
                if (size != null) {
                    recordSize(image, size)
                    file.setLastModified(System.currentTimeMillis())
                    if (target != null) return@withContext adopt(file, target)
                    readyFiles[image.cacheKey] = file
                    if (file.isFile && file.length() > 0) {
                        ReaderPerf.log { "page_cache_hit key=${image.cacheKey}" }
                        return@withContext file
                    }
                } else if (file.absolutePath.startsWith(cacheRoot.absolutePath)) {
                    // Never delete a downloaded copy; only the disposable reader cache is repaired here.
                    file.delete()
                }
            }

            coroutineScope {
                val owner = Any()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val flight = synchronized(flightsLock) {
                        if (admissionPaused != null) null else {
                            val current = inFlight.getOrPut(image.cacheKey) { Flight(image, target) }
                            if (!current.closing) current.owners[owner] = PagePriority.Download
                            current
                        }
                    }
                    if (flight == null) { admissionPaused?.await(); continue }
                    if (flight.closing) { flight.finished.await(); continue }
                    val subscription = launch(start = CoroutineStart.UNDISPATCHED) {
                        priorities.collect { priority ->
                            synchronized(flightsLock) {
                                if (owner in flight.owners) flight.owners[owner] = priority
                                pumpLocked()
                            }
                        }
                    }
                    try {
                        val produced = flight.result.await()
                        return@coroutineScope if (target != null) adopt(produced, target) else produced
                    } catch (cancel: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    } finally {
                        subscription.cancel()
                        leaveFlight(flight, owner)
                    }
                    flight.finished.await()
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
        }

    /**
     * Invoked only under flightsLock; I/O starts on the store's IO scope. Reader transfers and
     * download transfers have separate budgets so a running download can never starve a page turn,
     * while [TOTAL_TRANSFERS] keeps the CDN load bounded.
     */
    private fun pumpLocked() {
        if (admissionPaused != null) return
        while (runningTransfers < totalTransfers) {
            val next = inFlight.values
                .filter { !it.closing && it.job == null && it.owners.isNotEmpty() }
                .sortedWith(
                    compareBy<Flight> { it.owners.values.minOf { priority -> priority.ordinal } }
                        .thenBy { it.created },
                )
                .firstOrNull { flight ->
                    if (flight.isDownloadFlight()) runningDownloads < downloadTransfers else runningReaders < readerTransfers
                } ?: return
            val downloadFlight = next.isDownloadFlight()
            runningTransfers++
            if (downloadFlight) runningDownloads++ else runningReaders++
            next.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    // Another flight may have committed between the caller's cache check and admission.
                    val cached = cachedFile(next.image)?.takeIf { readSize(it) != null }
                    if (cached != null) {
                        readyFiles[next.image.cacheKey] = cached
                        recordSize(next.image, checkNotNull(readSize(cached)))
                        next.result.complete(next.target?.let { adopt(cached, it) } ?: cached)
                    } else next.result.complete(download(next.image, next, next.target ?: fileFor(next.image)))
                } catch (error: Throwable) {
                    next.result.completeExceptionally(if (next.closing) CancellationException("request released") else error)
                }
            }
            next.job!!.invokeOnCompletion { error ->
                if (!next.result.isCompleted) next.result.completeExceptionally(error ?: CancellationException("request closed"))
                    synchronized(flightsLock) {
                        if (inFlight[next.image.cacheKey] === next) inFlight.remove(next.image.cacheKey)
                        runningTransfers--
                        if (downloadFlight) runningDownloads-- else runningReaders--
                        next.finished.complete(Unit)
                        pumpLocked()
                    }
            }
            next.job!!.start()
        }
    }

    private fun leaveFlight(flight: Flight, owner: Any) {
        val close = synchronized(flightsLock) {
            flight.owners.remove(owner)
            if (flight.owners.isNotEmpty()) false else { flight.closing = true; true }
        }
        if (close) cancelFlight(flight)
    }

    private fun cancelFlight(flight: Flight) {
        val (call, job) = synchronized(flightsLock) {
            flight.closing = true
            if (flight.job == null) {
                if (inFlight[flight.image.cacheKey] === flight) inFlight.remove(flight.image.cacheKey)
                flight.result.cancel()
                flight.finished.complete(Unit)
            }
            flight.call to flight.job
        }
        // Cancelling Call also unblocks a body.read that is already past Call.await().
        job?.cancel()
        call?.cancel()
    }

    private class Flight(val image: PageImage, val target: File? = null) {
        val result = CompletableDeferred<File>()
        val finished = CompletableDeferred<Unit>()
        val owners = HashMap<Any, PagePriority>()
        val created = System.nanoTime()
        var job: Job? = null
        var call: Call? = null
        @Volatile var closing = false

        /** A flight that any offline download waits on consumes the download budget. */
        fun isDownloadFlight(): Boolean = owners.values.any { it == PagePriority.Download }
    }

    suspend fun loadCachedSizes(images: List<PageImage>) =
        withContext(Dispatchers.IO) {
            val started = ReaderPerf.mark()
            val found = HashMap<String, PageSize>()
            for (image in images) {
                currentCoroutineContext().ensureActive()
                val file = cachedFile(image) ?: continue
                val size = readSize(file) ?: continue
            readyFiles[image.cacheKey] = file
                found[image.cacheKey] = size
            }
            if (found.isNotEmpty()) {
                synchronized(sizeLock) {
                    _sizes.update { current ->
                        val next = current.toMutableMap()
                        for ((key, size) in found) {
                            val existing = next[key]
                            if (existing == null || existing.width != size.width || existing.height != size.height) {
                                next[key] = PageSize(size.width, size.height, System.nanoTime())
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

    suspend fun invalidate(image: PageImage) = maintain(image.cacheKey) {
        val key = image.cacheKey
        ReaderPerf.log { "page_invalidate key=$key" }
        fileFor(image).delete()
        readyFiles.remove(key)
        synchronized(sizeLock) {
            _sizes.update { it - key }
            sizeKeys.remove(key)
        }
        scheduleSizePersist()
    }

    /**
     * Drops the disposable reader copy after a durable offline copy took the same bytes.
     * The cached dimensions stay valid, so layout keeps using the real page ratio.
     */
    suspend fun releaseCachedCopy(image: PageImage) {
        val cached = fileFor(image)
        if (!cached.isFile) return
        maintain(image.cacheKey) {
            if (readyFiles[image.cacheKey]?.absolutePath == cached.absolutePath) readyFiles.remove(image.cacheKey)
            cached.delete()
            ReaderPerf.log { "page_cache_released key=${image.cacheKey}" }
        }
    }

    fun cacheSizeBytes(): Long =
        runCatching {
            cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

    suspend fun clearCache() = maintain(null) {
        runCatching {
            cacheRoot.walkTopDown().filter { it.isFile }.forEach { it.delete() }
        }
        synchronized(sizeLock) {
            _sizes.value = emptyMap()
            readyFiles.clear()
            sizeKeys.clear()
        }
        sizePersistJob?.cancel()
        sizeSnapshot.delete()
        ReaderPerf.log { "reader_cache_cleared" }
    }

    private suspend fun maintain(key: String?, action: () -> Unit) = withContext(Dispatchers.IO) {
        maintenance.withLock {
            val gate = CompletableDeferred<Unit>()
            val flights = synchronized(flightsLock) {
                admissionPaused = gate
                inFlight.values.filter { key == null || it.image.cacheKey == key }.onEach { it.closing = true }
            }
            try {
                flights.forEach(::cancelFlight)
                flights.forEach { it.finished.await() }
                action()
            } finally {
                synchronized(flightsLock) { admissionPaused = null; gate.complete(Unit); pumpLocked() }
            }
        }
    }

    private suspend fun download(image: PageImage, flight: Flight, target: File): File {
        val started = ReaderPerf.mark()
        target.parentFile?.mkdirs()
        val temp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            try {
                fetchTo(image.url, temp, flight)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                if (!e.isAlternateHostRetryable()) throw e
                val retryUrl = alternateUrl(image) ?: throw e
                ReaderPerf.log {
                    "image_host_retry key=${image.cacheKey} from=${image.url.toHttpUrlOrNull()?.host} to=${retryUrl.toHttpUrlOrNull()?.host}"
                }
                fetchTo(retryUrl, temp, flight)
            }

            currentCoroutineContext().ensureActive()
            if (temp.length() <= 0) {
                throw IOException("empty image")
            }
            if (!ImageIntegrity.isComplete(temp)) {
                // A truncated body still reports valid dimensions, so only the container tail can
                // tell us the transfer was cut short.
                throw ApiFailure.Network("incomplete image body")
            }
            val size = readSize(temp) ?: throw IOException("invalid image")
            synchronized(flightsLock) {
                if (flight.closing) throw CancellationException("image invalidated before commit")
                if (!temp.renameTo(target)) {
                    if (target.isFile && readSize(target) != null) {
                        temp.delete()
                        readyFiles[image.cacheKey] = target
                        recordSize(image, size, refresh = true)
                        ReaderPerf.log {
                            "page_downloaded key=${image.cacheKey} ms=${ReaderPerf.mark() - started} bytes=${target.length()}"
                        }
                        return target
                    }
                    throw IOException("cannot store image")
                }
                readyFiles[image.cacheKey] = target
                recordSize(image, size, refresh = true)
            }
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

    private suspend fun fetchTo(url: String, temp: File, flight: Flight) {
        val request =
            Request.Builder()
                .url(url)
                .headers(RequestHeaders.image(userAgent))
                .build()
        val call = httpClient.newCall(request)
        synchronized(flightsLock) {
            flight.call = call
            if (flight.closing) call.cancel()
        }
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
                            currentCoroutineContext().ensureActive()
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
        val current = image.url.toHttpUrlOrNull() ?: return null
        val alternate = alternateImageHost(current.host)?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
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
        scheduleSizePersist()
    }

    private fun touchSizeKey(key: String) {
        sizeKeys.remove(key)
        sizeKeys.add(key)
        while (sizeKeys.size > MAX_SIZE_ENTRIES) {
            val oldest = sizeKeys.first()
            sizeKeys.remove(oldest)
            readyFiles.remove(oldest)
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

    private fun adopt(source: File, target: File): File =
        if (source.absolutePath == target.absolutePath) target else copyInto(source, target)

    /** Copies [source] beside [target] and renames, so a half-written offline page never appears. */
    private fun copyInto(source: File, target: File): File {
        target.parentFile?.mkdirs()
        val temp = File.createTempFile("${target.name}.", ".part", target.parentFile)
        try {
            source.inputStream().buffered().use { input ->
                temp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (temp.length() <= 0L) throw IOException("empty page")
            if (!temp.renameTo(target)) throw IOException("cannot commit page")
            return target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Sizes are cheap to persist and expensive to rebuild: one bounds read per page on every start. */
    private fun loadSizeSnapshot() {
        if (!sizeSnapshot.isFile) return
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(sizeSnapshot))).use { input ->
                if (input.readInt() != SIZE_SNAPSHOT_VERSION) return
                val count = input.readInt()
                if (count <= 0 || count > MAX_SIZE_ENTRIES) return
                synchronized(sizeLock) {
                    repeat(count) {
                        val keyLength = input.readInt()
                        if (keyLength <= 0 || keyLength > MAX_SNAPSHOT_KEY_BYTES) return
                        val key =
                            ByteArray(keyLength)
                                .also { bytes -> input.readFully(bytes) }
                                .toString(Charsets.UTF_8)
                        val width = input.readInt()
                        val height = input.readInt()
                        if (width > 0 && height > 0) {
                            _sizes.update { it + (key to PageSize(width, height)) }
                            sizeKeys.remove(key)
                            sizeKeys.add(key)
                        }
                    }
                    while (sizeKeys.size > MAX_SIZE_ENTRIES) {
                        val oldest = sizeKeys.first()
                        sizeKeys.remove(oldest)
                        _sizes.update { it - oldest }
                    }
                }
            }
            ReaderPerf.log { "sizes_restored count=${_sizes.value.size}" }
        }.onFailure {
            runCatching { sizeSnapshot.delete() }
        }
    }

    private fun scheduleSizePersist() {
        if (sizePersistJob?.isActive == true) return
        sizePersistJob = scope.launch {
            delay(SIZE_PERSIST_DEBOUNCE_MS)
            persistSizeSnapshot()
        }
    }

    private fun persistSizeSnapshot() {
        val entries = synchronized(sizeLock) { _sizes.value.toList() }
        if (entries.isEmpty()) return
        runCatching {
            cacheRoot.mkdirs()
            val temp = File(cacheRoot, "$SIZE_SNAPSHOT_NAME.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { output ->
                output.writeInt(SIZE_SNAPSHOT_VERSION)
                output.writeInt(entries.size)
                for ((key, size) in entries) {
                    val bytes = key.toByteArray(Charsets.UTF_8)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.writeInt(size.width)
                    output.writeInt(size.height)
                }
            }
            if (!temp.renameTo(sizeSnapshot)) temp.delete()
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
        const val READER_TRANSFERS = 4
        const val DOWNLOAD_TRANSFERS = 2
        const val TOTAL_TRANSFERS = 6
        const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
        const val IMAGE_CALL_TIMEOUT_MS = 60_000L
        const val DEFAULT_CACHE_LIMIT_BYTES = 1024L * 1024 * 1024
        const val MIN_CACHE_BYTES = 256L * 1024 * 1024
        const val CACHE_FREE_SPACE_DIVISOR = 4L
        const val RECENT_FILE_GRACE_MS = 10 * 60 * 1000L
        const val TEMP_MAX_AGE_MS = 60 * 60 * 1000L
        const val TRIM_EVERY_DOWNLOADS = 20
        const val MAX_SIZE_ENTRIES = 4096
        const val SIZE_SNAPSHOT_NAME = "sizes.bin"
        const val SIZE_SNAPSHOT_VERSION = 1
        const val SIZE_PERSIST_DEBOUNCE_MS = 2_000L
        const val MAX_SNAPSHOT_KEY_BYTES = 256
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }
}
