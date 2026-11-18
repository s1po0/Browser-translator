package dev.rojink.browser.translation

import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dev.rojink.browser.web.origin
import dev.rojink.browser.web.readLimited
import dev.rojink.browser.web.sameSite
import dev.rojink.browser.web.webUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val INPUT_LIMIT = 14 * 1024 * 1024
private val regionFormats = setOf("image/jpeg", "image/png", "image/webp")
// A tall strip owns a canvas plus one detailed decode. Avoid several such scans competing for RAM.
private val longImageSlot = Semaphore(1)
private val budgetLock = Any()
private lateinit var sharedSlots: Semaphore
private fun imageSlots(context: Context): Semaphore = synchronized(budgetLock) {
    if (!::sharedSlots.isInitialized) {
        val memory = context.getSystemService(ActivityManager::class.java)
        val count = when {
            memory == null || memory.isLowRamDevice || memory.memoryClass < 128 -> 1
            memory.memoryClass < 256 -> 2
            else -> 3
        }
        sharedSlots = Semaphore(count)
    }
    sharedSlots
}

/** Native media loading isolates cookies from cross-site requests, including redirects. */
private class MediaLoader(private val context: Context) {
    private val base = privateClient.newBuilder().callTimeout(17, TimeUnit.SECONDS).build()
    private val public = base.newBuilder().dns(object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).filter(InetAddress::isPublicMediaAddress).ifEmpty { throw UnknownHostException(hostname) }
    }).build()

    suspend fun load(work: ImageWork): ByteArray? {
        val url = webUrl(work.candidate.url) ?: return work.fallback()
        if (url.host == "rojink.invalid") return demo(url)
        var target: HttpUrl = url
        repeat(6) {
            if (work.referer.isHttps && !target.isHttps) return work.fallback()
            val firstParty = sameSite(target, work.referer)
            val request = Request.Builder().url(target).header("User-Agent", work.userAgent)
                .header("Accept", "image/webp,image/png,image/jpeg,image/gif,image/*;q=.8,*/*;q=.5")
            val ref = if (target.origin() == work.referer.origin()) work.referer.newBuilder().fragment(null).build().toString()
                else work.referer.origin() + "/"
            request.header("Referer", ref)
            if (firstParty) work.cookies?.getCookie(target.toString())?.takeIf { it.isNotEmpty() }?.let { request.header("Cookie", it) }
            val result = try {
                (if (firstParty) base else public).exchange(request.build()) { response ->
                    if (response.code in 300..399) return@exchange DownloadResult(null, response.header("Location"))
                    if (!response.isSuccessful) throw IOException("Image host HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Missing image")
                    if (body.contentLength() > INPUT_LIMIT) throw IOException("Image too large")
                    DownloadResult(readLimited(body.byteStream(), INPUT_LIMIT), null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: IOException) { return work.fallback() ?: throw e }
            result.bytes?.let { return it }
            val next = result.location?.let { target.resolve(it) }?.let { webUrl(it.toString()) }
                ?: return work.fallback()
            if (next.host == "rojink.invalid") return null
            target = next
        }
        return work.fallback()
    }

    private suspend fun demo(url: HttpUrl): ByteArray? = withContext(Dispatchers.IO) {
        if (url.encodedPath !in setOf("/demo/chapter.png", "/demo/menu.png")) return@withContext null
        context.assets.open(url.encodedPath.removePrefix("/")).use { readLimited(it, INPUT_LIMIT) }
    }

    private data class DownloadResult(val bytes: ByteArray?, val location: String?)
}

private fun InetAddress.isPublicMediaAddress(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) return false
    val b = address.map { it.toInt() and 0xff }
    if (b.size == 4) return b[0] in 1..223 && !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 198 && b[1] in 18..19)
    return b.size == 16 && b[0] and 0xfe != 0xfc && b[0] and 0xe0 == 0x20
}

internal data class GalleryImage(val bitmap: Bitmap?, val keptText: Boolean)

private sealed interface ImageFrame {
    data class Original(val keptText: Boolean = false) : ImageFrame
    data class Cached(val image: RenderedImage.Stored) : ImageFrame
    data class Painted(val bitmap: Bitmap, val cacheKey: String?, val keptText: Boolean) : ImageFrame
}

internal class ImagePipeline(
    private val context: Context,
    private val measure: ((String, Long) -> Unit)? = null,
    private val paintDecision: ((Box, PaintDecision) -> Unit)? = null,
) : PageImageProcessor, AutoCloseable {
    private val slots = imageSlots(context)
    private val onDevice = OnDeviceImageOcr(context)
    private val loader = MediaLoader(context)
    private val renderer = ImageRenderer(context)
    private val cache = ImageCache(File(context.cacheDir, "translated-images"))

    override suspend fun process(info: ImageWork, options: ReaderOptions, providers: ReaderProviders, privateTab: Boolean): ImageTranslation =
        slots.withPermit {
            val input = loader.load(info) ?: return@withPermit ImageTranslation()
            when (val frame = prepare(input, options, providers, info.candidate.lang, save = !privateTab)) {
                is ImageFrame.Original -> ImageTranslation(keptText = frame.keptText)
                is ImageFrame.Cached -> ImageTranslation(frame.image)
                is ImageFrame.Painted -> try {
                    val image = timed("encode") {
                        frame.cacheKey?.let { cache.put(it, frame.bitmap) } ?: withContext(Dispatchers.Default) {
                            RenderedImage.Memory(ByteArrayOutputStream().use { output ->
                                if (!frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Could not render image")
                                output.toByteArray()
                            })
                        }
                    }
                    ImageTranslation(image, frame.keptText)
                } finally { frame.bitmap.recycle() }
            }
        }

    suspend fun open(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open selected file")
        stream.use { readLimited(it, INPUT_LIMIT) }
    }

    suspend fun preview(bytes: ByteArray): Bitmap = withContext(Dispatchers.Default) { decode(bytes, imageInfo(bytes)) }

    suspend fun gallery(input: ByteArray, options: ReaderOptions, providers: ReaderProviders, incognito: Boolean): GalleryImage = slots.withPermit {
        when (val frame = prepare(input, options, providers, "", !incognito)) {
            is ImageFrame.Original -> GalleryImage(null, frame.keptText)
            is ImageFrame.Cached -> GalleryImage(withContext(Dispatchers.Default) {
                frame.image.stream().use { BitmapFactory.decodeStream(it) } ?: throw IOException("Could not decode output")
            }, false)
            // The gallery can show the painted bitmap directly; it doesn't need a PNG round trip.
            is ImageFrame.Painted -> try {
                frame.cacheKey?.let { timed("cache") { cache.put(it, frame.bitmap) } }
                GalleryImage(frame.bitmap, frame.keptText)
            } catch (failure: Throwable) { frame.bitmap.recycle(); throw failure }
        }
    }

    suspend fun clear() = cache.clear()
    override fun close() { onDevice.close() }

    private inline fun <T> timed(stage: String, block: () -> T): T {
        val observer = measure ?: return block()
        val start = System.nanoTime()
        return block().also { observer(stage, (System.nanoTime() - start) / 1_000_000) }
    }

    private suspend fun prepare(input: ByteArray, options: ReaderOptions, providers: ReaderProviders, language: String, save: Boolean): ImageFrame {
        val hint = OcrHint(options.script, language, options.enhancedOcr)
        val vision = providers.visionKey
        val key = if (save) withContext(Dispatchers.Default) {
            val langHint = language.lowercase().take(16)
            sha256("sipan-image-v5:${sha256(input)}:${options.target}:${options.script}:${options.enhancedOcr}:$langHint:${providers.variant}:${vision != null}".toByteArray())
        } else null
        if (key != null) cache.get(key)?.let { return ImageFrame.Cached(it) }
        val info = withContext(Dispatchers.Default) { imageInfo(input) }
        val plan = if (vision == null && info.upright && info.mime in regionFormats) planLongImage(info.size) else null
        if (plan != null) {
            longImageSlot.withPermit { prepareLong(input, plan, hint, options, providers, key) }?.let { return it }
        }
        val bitmap = timed("decode") { withContext(Dispatchers.Default) { decode(input, info) } }
        var display = false
        try {
            if (bitmap.width < 80 || bitmap.height < 80) return ImageFrame.Original()
            val recognized = timed("ocr") { (if (vision != null) CloudVisionOcrProvider(vision) else onDevice).recognize(bitmap, hint) }
            val comic = recognized.comic || options.script == ENGLISH_COMIC_SCRIPT
            val (layout, missed) = timed("layout") { withContext(Dispatchers.Default) {
                val surface = if (comic) ComicSurface(bitmap, recognized.bubbles) else null
                val layout = groupForImage(recognized.copy(comic = comic), surface?.let { it::sameInterior })
                val missed = comic && options.enhancedOcr && recognized.bubbles.any { missedBubbleBands(it, recognized.lines).isNotEmpty() }
                layout to missed
            } }
            if (layout.units.isEmpty()) return ImageFrame.Original(keptText = missed)
            val translations = timed("translate") {
                translateImageWords(layout.units, options.target, options.script == ENGLISH_COMIC_SCRIPT, providers.imageTranslator, providers.imageRetry)
            }
            val drawn = timed("paint") { withContext(Dispatchers.Default) {
                renderer.paint(bitmap, layout.units, translations.map { it.take(2000) }, layout.protected, options.target, comic,
                    recognized.bubbles, paintDecision)
            } }
            val keptText = missed || drawn < layout.units.size
            if (drawn == 0) return ImageFrame.Original(keptText = keptText)
            coroutineContext.ensureActive()
            // Partial renders are valid for this session, but don't pin a missed bubble or provider error to disk.
            display = true
            // If a region decoder failed, do not reuse its lower-detail fallback as a complete result.
            return ImageFrame.Painted(bitmap, key.takeUnless { keptText || plan != null }, keptText)
        } finally { if (!display) bitmap.recycle() }
    }

    private suspend fun prepareLong(
        input: ByteArray, plan: LongImagePlan, hint: OcrHint, options: ReaderOptions, providers: ReaderProviders, key: String?,
    ): ImageFrame? {
        // Retain ownership until withContext returns; cancellation can discard a result at dispatcher handoff.
        var pending: Bitmap? = null
        try {
            val frame = withContext(Dispatchers.Default) {
                val source = LongImageOcr.open(input, plan) ?: return@withContext null
                source.use {
                    val bitmap = Bitmap.createBitmap(plan.output.width, plan.output.height, Bitmap.Config.ARGB_8888)
                        .also { pending = it }
                    val scan = timed("strip OCR") { source.scan(bitmap, onDevice, hint) } ?: return@withContext null
                    if (scan.readings.isEmpty()) return@withContext ImageFrame.Original(keptText = scan.suspectedMiss)
                    val translations = timed("translate") {
                        translateImageWords(scan.readings.map { it.unit }, options.target, options.script == ENGLISH_COMIC_SCRIPT,
                            providers.imageTranslator, providers.imageRetry)
                    }
                    val drawn = timed("strip paint") {
                        source.paint(bitmap, scan, translations.map { it.take(2000) }, options.target, renderer, paintDecision)
                    }
                    val kept = scan.suspectedMiss || scan.truncated || drawn < scan.readings.size
                    if (drawn == 0) ImageFrame.Original(keptText = kept) else ImageFrame.Painted(bitmap, key.takeUnless { kept }, kept)
                }
            }
            if (frame is ImageFrame.Painted) pending = null
            return frame
        } finally { pending?.recycle() }
    }

    private data class ImageInfo(val size: RasterSize, val mime: String?, val orientation: Int) {
        val upright get() = orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED
    }

    @Suppress("DEPRECATION")
    private fun imageInfo(data: ByteArray): ImageInfo {
        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, info)
        if (info.outWidth <= 0 || info.outHeight <= 0) throw IOException("Unsupported image format")
        val orientation = if (info.outMimeType == "image/jpeg") runCatching {
            ExifInterface(ByteArrayInputStream(data)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL) else ExifInterface.ORIENTATION_NORMAL
        return ImageInfo(RasterSize(info.outWidth, info.outHeight), info.outMimeType, orientation)
    }

    private fun decode(data: ByteArray, info: ImageInfo): Bitmap {
        val sample = imageSample(info.size)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true }
        val raw = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: throw IOException("Could not decode image")
        val orientation = info.orientation
        val rotation = when (orientation) { 3, 4 -> 180f; 5, 6 -> 90f; 7, 8 -> 270f; else -> 0f }
        val flip = orientation in listOf(2, 4, 5, 7)
        if (rotation == 0f && !flip) return raw
        val transformed = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply {
            setRotate(rotation)
            if (flip) postScale(-1f, 1f)
        }, true)
        if (raw !== transformed) raw.recycle()
        if (transformed.isMutable) return transformed
        return try { transformed.copy(Bitmap.Config.ARGB_8888, true) ?: throw IOException("Could not rotate image") } finally { transformed.recycle() }
    }
}

private class ImageCache(private val root: File, private val max: Long = 160L * 1024 * 1024) {
    private val lock = Mutex()
    suspend fun get(key: String): RenderedImage.Stored? = withContext(Dispatchers.IO) {
        lock.withLock { File(root, "$key.png").takeIf { it.isFile }?.let { it.setLastModified(System.currentTimeMillis()); RenderedImage.Stored(it) } }
    }
    suspend fun put(key: String, bitmap: Bitmap): RenderedImage = withContext(Dispatchers.IO) {
        lock.withLock {
            check(root.exists() || root.mkdirs()) { "Could not create translation cache" }
            val file = File(root, "$key.png")
            if (file.isFile) return@withLock RenderedImage.Stored(file)
            val temp = File.createTempFile("render-", ".tmp", root)
            try {
                temp.outputStream().buffered().use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Image encoding failed")
                }
                coroutineContext.ensureActive()
                if (!temp.renameTo(file)) throw IOException("Image caching failed")
                val files = root.listFiles()?.filter { it.extension == "png" }?.sortedBy { it.lastModified() }.orEmpty()
                var size = files.sumOf { it.length() }
                for (old in files) {
                    if (size <= max) break
                    if (old == file) continue
                    val bytes = old.length()
                    if (old.delete()) size -= bytes
                }
                RenderedImage.Stored(file)
            } finally { temp.delete() }
        }
    }
    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { root.deleteRecursively() } }
}
