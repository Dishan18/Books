package io.github.dishan18.books.manga.source.local

import io.github.dishan18.books.manga.source.api.Listing
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSourceTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var source: LocalSource

    @Before
    fun setUp() {
        root = temp.newFolder("manga-local")
        source = LocalSource(root)
        val series = File(root, "series-1").apply { mkdirs() }
        source.writeInfo("series-1", LocalSeriesInfo(title = "My Webtoon", author = "Me"))
        zip(File(series, "Chapter 10.cbz"), "10.jpg" to "ten", "2.jpg" to "two", "ComicInfo.xml" to "<x/>", "1.jpg" to "one")
        zip(File(series, "Chapter 2.zip"), "a.png" to "a")
        File(series, "Chapter 3").apply { mkdirs() }.also { File(it, "001.webp").writeText("folder-page") }
    }

    private fun zip(file: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun listsSeriesWithMetadata() = runTest {
        val page = source.browse(Listing.POPULAR, 1)
        assertEquals(listOf("My Webtoon"), page.items.map { it.title })
        assertEquals(listOf("My Webtoon"), source.search("webtoon", 1).items.map { it.title })
        assertTrue(source.search("nothing", 1).items.isEmpty())
    }

    @Test
    fun chaptersAreArchivesAndFoldersInNaturalOrder() = runTest {
        val chapters = source.chapters("series-1")
        assertEquals(listOf("Chapter 2", "Chapter 3", "Chapter 10"), chapters.map { it.name })
        assertEquals(listOf(2f, 3f, 10f), chapters.map { it.number })
    }

    @Test
    fun archivePagesSkipNonImagesAndExtractInOrder() = runTest {
        val pages = source.pages("series-1", "Chapter 10.cbz")
        assertEquals(3, pages.size)
        val out = temp.root.resolve("out.jpg")
        source.fetchPage(pages[0], out)
        assertEquals("one", out.readText())
        source.fetchPage(pages[2], out)
        assertEquals("ten", out.readText())
    }

    @Test
    fun folderChaptersWork() = runTest {
        val pages = source.pages("series-1", "Chapter 3")
        val out = temp.root.resolve("folder.webp")
        source.fetchPage(pages.single(), out)
        assertEquals("folder-page", out.readText())
    }

    @Test
    fun keysCannotEscapeTheLibrary() = runTest {
        try {
            source.pages("series-1", "../../secret.zip")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            source.seriesDir("../outside")
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
