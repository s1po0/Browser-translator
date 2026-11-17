package dev.rojink.browser.web

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import dev.rojink.browser.data.Bookmark
import dev.rojink.browser.data.BrowserDao
import dev.rojink.browser.data.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.UUID

class Library(private val context: Context, private val dao: BrowserDao) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    val downloads = dao.downloads()

    /** OS-managed downloads to app-specific storage: no broad storage permission on API 26+. */
    suspend fun download(url: String, userAgent: String, referrer: String?, disposition: String?, mime: String?) = withContext(Dispatchers.IO) {
        val target = requireNotNull(webUrl(url))
        val name = URLUtil.guessFileName(url, disposition, mime)
            .replace(Regex("[\\p{Cntrl}/\\\\]"), "_").trim('.').takeLast(120).ifBlank { "download" }
        val request = DownloadManager.Request(Uri.parse(target.toString())).setTitle(name)
            .setMimeType(mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "${UUID.randomUUID()}/$name")
            .addRequestHeader("User-Agent", userAgent)
        // DownloadManager may follow redirects itself. Do not give it a raw Cookie header.
        referrer?.let(::webUrl)?.let { request.addRequestHeader("Referer", it.origin() + "/") }
        val id = manager.enqueue(request)
        try { dao.putDownload(Download(id, name)) } catch (e: Exception) { manager.remove(id); throw e }
    }

    suspend fun status(id: Long): Int = withContext(Dispatchers.IO) {
        manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else 0
        } ?: 0
    }

    suspend fun openDownload(id: Long): Intent = withContext(Dispatchers.IO) {
        val local = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL)
                c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) else null
        }
        val uri = local?.let(Uri::parse)?.takeIf { it.scheme == "file" }?.path?.let { path ->
            FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
        } ?: requireNotNull(manager.getUriForDownloadedFile(id))
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, manager.getMimeTypeForDownloadedFile(id) ?: "application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun removeDownload(id: Long) = withContext(Dispatchers.IO) {
        manager.remove(id)
        dao.removeDownload(id)
    }

    suspend fun importBookmarks(uri: Uri): Int = withContext(Dispatchers.IO) {
        val entries = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val document = Jsoup.parse(String(readLimited(input, 4 * 1024 * 1024), Charsets.UTF_8))
            document.select("a[href]").asSequence().mapNotNull { a ->
                webUrl(a.attr("href"))?.toString()?.let { Bookmark(it, a.text().take(240).ifBlank { it }) }
            }.distinctBy { it.url }.take(5000).toList()
        }
        dao.putBookmarks(entries)
        entries.size
    }

    suspend fun exportBookmarks(uri: Uri, entries: List<Bookmark>) = withContext(Dispatchers.IO) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { out ->
            out.appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
            out.appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
            out.appendLine("<TITLE>Sipan Bookmarks</TITLE><H1>Sipan Bookmarks</H1><DL><p>")
            entries.forEach { out.appendLine("<DT><A HREF=\"${escape(it.url)}\" ADD_DATE=\"${it.savedAt / 1000}\">${escape(it.title)}</A>") }
            out.appendLine("</DL><p>")
        }
    }

    companion object {
        private fun escape(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

fun readLimited(input: java.io.InputStream, maxBytes: Int): ByteArray {
    val buffer = ByteArray(8192)
    val output = java.io.ByteArrayOutputStream()
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (output.size() + count > maxBytes) throw java.io.IOException("Content exceeds size limit")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
