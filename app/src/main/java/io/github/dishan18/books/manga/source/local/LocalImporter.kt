package io.github.dishan18.books.manga.source.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.dishan18.books.manga.data.MangaRepository
import io.github.dishan18.books.manga.source.api.SourceSeries
import io.github.dishan18.books.manga.util.NaturalOrder
import io.github.dishan18.books.manga.util.isImageName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

data class ImportResult(val seriesId: Long, val title: String, val chapters: Int, val skipped: List<String>)

/**
 * Copies CBZ/ZIP archives or image folders picked by the user into the local source's storage and
 * adds them to the manga library. Files are validated before they count as chapters.
 */
class LocalImporter(
    private val context: Context,
    private val local: LocalSource,
    private val repository: MangaRepository,
) {
    /** A sensible series title for these files, e.g. "One Piece" for "One Piece - Ch 001.cbz". */
    fun suggestTitle(names: List<String>): String {
        val bases = names.map { it.substringBeforeLast('.') }
        if (bases.isEmpty()) return "Imported manga"
        if (bases.size == 1) return cleanTitle(bases.first())
        val prefix = bases.reduce { acc, s -> acc.commonPrefixWith(s, ignoreCase = true) }
        return cleanTitle(prefix).ifBlank { cleanTitle(bases.first()) }
    }

    fun displayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    /** Each archive becomes one chapter of a new series called [title]. */
    suspend fun importArchives(uris: List<Uri>, title: String): ImportResult = withContext(Dispatchers.IO) {
        val seriesKey = newSeriesDir()
        val dir = local.seriesDir(seriesKey)
        val skipped = mutableListOf<String>()
        try {
            uris.forEach { uri ->
                val name = safeName(displayName(uri))
                if (!LocalSource.isArchive(name)) {
                    skipped += name
                    return@forEach
                }
                val target = uniqueFile(dir, name)
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: run { skipped += name; return@forEach }
                if (!isValidArchive(target)) {
                    target.delete()
                    skipped += name
                }
            }
            finish(seriesKey, title, skipped)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /**
     * A folder of images becomes a one-chapter series; a folder of sub-folders and/or archives
     * becomes a series with one chapter per child.
     */
    suspend fun importFolder(treeUri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryName(treeUri, rootId) ?: "Imported folder"
        val children = listChildren(treeUri, rootId)
        val seriesKey = newSeriesDir()
        val dir = local.seriesDir(seriesKey)
        val skipped = mutableListOf<String>()
        try {
            val images = children.filter { !it.isDirectory && isImageName(it.name) }
            if (images.isNotEmpty()) {
                copyImages(treeUri, images, File(dir, safeName(rootName)))
            }
            children.filter { it.isDirectory }.forEach { child ->
                val childImages = listChildren(treeUri, child.documentId).filter { !it.isDirectory && isImageName(it.name) }
                if (childImages.isEmpty()) skipped += child.name else copyImages(treeUri, childImages, uniqueFile(dir, safeName(child.name)))
            }
            children.filter { !it.isDirectory && LocalSource.isArchive(it.name) }.forEach { archive ->
                val target = uniqueFile(dir, safeName(archive.name))
                context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, archive.documentId))
                    ?.use { input -> target.outputStream().use { input.copyTo(it) } }
                if (!isValidArchive(target)) {
                    target.delete()
                    skipped += archive.name
                }
            }
            finish(seriesKey, cleanTitle(rootName), skipped)
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    private suspend fun finish(seriesKey: String, title: String, skipped: List<String>): ImportResult {
        val dir = local.seriesDir(seriesKey)
        val chapters = LocalSource.chapterFiles(dir)
        if (chapters.isEmpty()) {
            dir.deleteRecursively()
            throw IllegalArgumentException(
                if (skipped.isEmpty()) "No images found" else "None of these could be read as manga (${skipped.joinToString()})",
            )
        }
        local.writeInfo(seriesKey, LocalSeriesInfo(title = title.ifBlank { "Imported manga" }))
        writeCover(seriesKey, chapters.first())
        val coverUri = local.coverFile(seriesKey).takeIf { it.isFile }?.toURI()?.toString()
        val seriesId = repository.upsertFromCatalog(LocalSource.ID, SourceSeries(seriesKey, title, coverUri))
        repository.refreshDetails(seriesId)
        repository.refreshChapters(seriesId).getOrThrow()
        repository.setFavorite(seriesId, true)
        return ImportResult(seriesId, title, chapters.size, skipped)
    }

    private fun writeCover(seriesKey: String, firstChapter: File) {
        runCatching {
            val firstImage = LocalSource.imageEntries(firstChapter).firstOrNull() ?: return
            val temp = File.createTempFile("cover", ".img", context.cacheDir)
            try {
                if (firstChapter.isDirectory) {
                    File(firstChapter, firstImage).copyTo(temp, overwrite = true)
                } else {
                    ZipFile(firstChapter).use { zip -> zip.getInputStream(zip.getEntry(firstImage)).use { input -> temp.outputStream().use { input.copyTo(it) } } }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(temp.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= COVER_WIDTH) sample *= 2
                val decoded = BitmapFactory.decodeFile(temp.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
                // Webtoon pages are very tall: use the top 2:3 part as the cover.
                val coverHeight = minOf(decoded.height, decoded.width * 3 / 2)
                val cropped = Bitmap.createBitmap(decoded, 0, 0, decoded.width, coverHeight)
                local.coverFile(seriesKey).outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            } finally {
                temp.delete()
            }
        }
    }

    private data class Child(val documentId: String, val name: String, val isDirectory: Boolean)

    private fun listChildren(treeUri: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(Child(cursor.getString(0), cursor.getString(1), cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }.orEmpty().sortedWith(compareBy(NaturalOrder) { it.name })
    }

    private fun queryName(treeUri: Uri, documentId: String): String? =
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun copyImages(treeUri: Uri, images: List<Child>, target: File) {
        target.mkdirs()
        images.forEachIndexed { index, image ->
            val extension = image.name.substringAfterLast('.', "jpg").lowercase()
            val out = File(target, "%04d.%s".format(index + 1, extension))
            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, image.documentId))
                ?.use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }

    private fun isValidArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            var images = 0
            var entries = 0
            for (entry in zip.entries()) {
                if (++entries > MAX_ENTRIES) return false
                if (!entry.isDirectory && isImageName(entry.name)) images++
            }
            images > 0
        }
    }.getOrDefault(false)

    private fun newSeriesDir(): String = UUID.randomUUID().toString().also { local.seriesDir(it).mkdirs() }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 2
        while (candidate.exists()) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }

    private companion object {
        const val COVER_WIDTH = 600
        const val MAX_ENTRIES = 5_000

        /** File names from other apps are untrusted: keep them to one plain path segment. */
        fun safeName(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\u0000]"""), "_").trim().trimStart('.').ifBlank { "chapter" }.take(120)

        fun cleanTitle(raw: String): String =
            raw.replace('_', ' ')
                .replace(Regex("""(?i)[\s\-_.(\[]*(ch(apter)?|vol(ume)?|ep(isode)?)?[\s.#]*\d*[\s\-_.)\]]*$"""), "")
                .trim()
    }
}
