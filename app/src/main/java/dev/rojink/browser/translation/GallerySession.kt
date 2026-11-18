package dev.rojink.browser.translation

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import dev.rojink.browser.R
import dev.rojink.browser.web.Browser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class GallerySession(
    private val context: Context, private val browser: Browser, private val pipeline: ImagePipeline,
    private val providers: ProviderFactory,
) {
    var active by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var working by mutableStateOf(false)
        private set
    var before by mutableStateOf<Bitmap?>(null)
        private set
    var after by mutableStateOf<Bitmap?>(null)
        private set
    var original by mutableStateOf(true)
        private set
    var privateGallery by mutableStateOf(false)
        private set
    var error by mutableIntStateOf(0)
        private set
    var feedback by mutableIntStateOf(0)
        private set
    var lastOptions by mutableStateOf<ReaderOptions?>(null)
        private set
    private var input: ByteArray? = null
    private var job: Job? = null
    private var revision = 0L
    private val shares get() = File(context.cacheDir, "shared-images")

    fun select(uri: Uri, privateTab: Boolean) {
        close()
        privateGallery = privateTab
        active = true
        loading = true
        browser.setAttached(false)
        val id = revision
        job = browser.scope.launch {
            try {
                val data = pipeline.open(uri)
                val preview = pipeline.preview(data)
                if (id != revision) { preview.recycle(); return@launch }
                input = data
                before = preview
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (id == revision) error = R.string.photo_error }
            finally { if (id == revision) loading = false }
        }
    }

    fun translate(options: ReaderOptions) {
        val data = input ?: return
        if (working || loading || !active) return
        val id = revision
        working = true
        error = 0
        lastOptions = options
        job = browser.scope.launch {
            try {
                val result = pipeline.gallery(data, options, providers.get(privateGallery, options.useVision), privateGallery)
                if (id != revision) { result.bitmap?.recycle(); return@launch }
                after = result.bitmap
                original = after == null
                if (result.keptText) error = R.string.image_text_kept
                else if (after == null) error = R.string.no_image_text
            } catch (e: CancellationException) { throw e }
            catch (e: HttpFailure) { if (id == revision) error = R.string.translate_error }
            catch (_: Exception) { if (id == revision) error = R.string.photo_error }
            finally { if (id == revision) working = false }
        }
    }

    fun toggle() { if (after != null) original = !original }

    fun close() {
        revision++
        job?.cancel(); job = null
        input = null; before = null; after = null
        error = 0; feedback = 0; lastOptions = null
        original = true; loading = false; working = false
        if (active) browser.setAttached(true)
        active = false
    }

    fun save(uri: Uri) {
        val result = after?.takeUnless { privateGallery } ?: return
        browser.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Could not write to destination")
                    out.use { compress(result, it) }
                }
                feedback = R.string.image_saved
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = R.string.operation_failed }
        }
    }

    fun share() {
        val result = after?.takeUnless { privateGallery } ?: return
        browser.scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) {
                    shares.mkdirs()
                    val entries = shares.listFiles().orEmpty().sortedByDescending(File::lastModified)
                    entries.drop(4).forEach { it.delete() }
                    entries.take(4).filter { System.currentTimeMillis() - it.lastModified() > 2 * 86400000L }.forEach { it.delete() }
                    val file = File.createTempFile("rojink-", ".png", shares)
                    try { file.outputStream().buffered().use { compress(result, it) } }
                    catch (e: Exception) { file.delete(); throw e }
                    FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                }
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { feedback = R.string.no_app }
        }
    }

    suspend fun clearShares() = withContext(Dispatchers.IO) { shares.deleteRecursively() }
    fun clearFeedback() { feedback = 0 }
    private fun compress(bitmap: Bitmap, output: OutputStream) {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Could not encode PNG")
    }
}
