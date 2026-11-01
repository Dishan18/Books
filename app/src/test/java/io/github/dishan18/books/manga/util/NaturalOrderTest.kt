package io.github.dishan18.books.manga.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NaturalOrderTest {

    @Test
    fun numbersCompareByValue() {
        val sorted = listOf("page10.jpg", "page2.jpg", "page1.jpg", "Page3.png").sortedWith(NaturalOrder)
        assertEquals(listOf("page1.jpg", "page2.jpg", "Page3.png", "page10.jpg"), sorted)
    }

    @Test
    fun leadingZerosAndChapterNames() {
        val sorted = listOf("Ch 010.cbz", "Ch 2.cbz", "Ch 001.cbz", "Ch 2.5.cbz").sortedWith(NaturalOrder)
        assertEquals(listOf("Ch 001.cbz", "Ch 2.cbz", "Ch 2.5.cbz", "Ch 010.cbz"), sorted)
    }

    @Test
    fun imageDetectionSkipsMetadata() {
        assertTrue(isImageName("001.JPG"))
        assertTrue(isImageName("folder/002.webp"))
        assertFalse(isImageName("ComicInfo.xml"))
        assertFalse(isImageName("__MACOSX/._001.jpg"))
        assertFalse(isImageName(".thumbnail.png"))
    }
}
