package io.github.dishan18.books.manga.download

import java.io.File

/**
 * Downloaded chapters on disk: `<root>/<seriesId>/<chapterId>/0000.img …` plus a `.complete` marker
 * written last, so a chapter only counts as downloaded once every page is in place.
 */
class DownloadStorage(private val root: File) {

    fun chapterDir(seriesId: Long, chapterId: Long) = File(root, "$seriesId/$chapterId")

    fun pageFile(seriesId: Long, chapterId: Long, index: Int) =
        File(chapterDir(seriesId, chapterId), "%04d.img".format(index))

    fun isComplete(seriesId: Long, chapterId: Long) = File(chapterDir(seriesId, chapterId), COMPLETE).isFile

    /** Pages of a complete download, in order, or null if the chapter isn't fully downloaded. */
    fun completePages(seriesId: Long, chapterId: Long): List<File>? {
        val marker = File(chapterDir(seriesId, chapterId), COMPLETE)
        val count = marker.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: return null
        val pages = (0 until count).map { pageFile(seriesId, chapterId, it) }
        return pages.takeIf { list -> list.all { it.isFile && it.length() > 0 } }
    }

    fun existingPageCount(seriesId: Long, chapterId: Long): Int =
        chapterDir(seriesId, chapterId).listFiles { f -> f.name.endsWith(".img") && f.length() > 0 }?.size ?: 0

    fun markComplete(seriesId: Long, chapterId: Long, pageCount: Int) {
        File(chapterDir(seriesId, chapterId), COMPLETE).writeText(pageCount.toString())
    }

    fun delete(seriesId: Long, chapterId: Long) {
        chapterDir(seriesId, chapterId).deleteRecursively()
    }

    fun deleteSeries(seriesId: Long) {
        File(root, seriesId.toString()).deleteRecursively()
    }

    /** Total bytes used by downloads. */
    fun sizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private companion object {
        const val COMPLETE = ".complete"
    }
}
