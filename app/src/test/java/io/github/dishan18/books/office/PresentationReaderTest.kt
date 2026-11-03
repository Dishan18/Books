package io.github.dishan18.books.office

import io.github.dishan18.books.data.BookFormat
import io.github.dishan18.books.office.slides.Align
import io.github.dishan18.books.office.slides.Fill
import io.github.dishan18.books.office.slides.Frame
import io.github.dishan18.books.office.slides.Geometry
import io.github.dishan18.books.office.slides.Presentation
import io.github.dishan18.books.office.slides.PresentationReader
import io.github.dishan18.books.office.slides.Shape
import io.github.dishan18.books.office.slides.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Reads a deck made by python-pptx from PowerPoint's default template (see
 * resources/office/make_fixtures.py), checking that slides inherit from their layout, master and theme.
 */
class PresentationReaderTest {

    private lateinit var deck: Presentation
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val accent1 = 0xFF4F81BD.toInt()

    @Before
    fun read() {
        val file = File(javaClass.getResource("/office/deck.pptx")!!.toURI())
        OfficePackage(file).use { pkg ->
            assertEquals(BookFormat.PPTX, OfficeFiles.formatOf(pkg))
            deck = PresentationReader(pkg).read()
        }
    }

    private fun Shape.text() = (this as Shape.Box).text!!.paragraphs.joinToString("\n") { p -> p.runs.joinToString("") { it.text } }

    @Test
    fun sizeAndSlides() {
        assertEquals(9_144_000f, deck.width)
        assertEquals(6_858_000f, deck.height)
        assertEquals(listOf(1, 2, 3), deck.slides.map { it.number })
        assertEquals(Fill.Solid(white), deck.slides[0].background)
    }

    @Test
    fun titleSlideInheritsFromLayoutAndMaster() {
        val (title, subtitle) = deck.slides[0].shapes.map { it as Shape.Box }
        // Position from the layout; size, colour and alignment from the master's title style.
        assertEquals(Frame(685_800f, 2_130_425f, 7_772_400f, 1_470_025f), title.frame)
        assertEquals("Hello Books", title.text())
        val run = title.text!!.paragraphs[0].runs[0]
        assertEquals(44f, run.size)
        assertEquals(black, run.color)
        assertEquals("Calibri", run.font)
        assertEquals(Align.CENTER, title.text!!.paragraphs[0].align)
        assertNull(title.text!!.paragraphs[0].bullet)

        // The layout's subtitle: centred, no bullet, 75% tint of black, body size from the master.
        assertEquals("A test deck", subtitle.text())
        val p = subtitle.text!!.paragraphs[0]
        assertEquals(Align.CENTER, p.align)
        assertNull(p.bullet)
        assertEquals(32f, p.runs[0].size)
        assertEquals(0xFF404040.toInt(), p.runs[0].color)
    }

    @Test
    fun bulletsComeFromTheMastersBodyStyle() {
        val (title, body) = deck.slides[1].shapes.map { it as Shape.Box }
        // This layout gives no position, so the master's title box is used.
        assertEquals(Frame(457_200f, 274_638f, 8_229_600f, 1_143_000f), title.frame)
        val paragraphs = body.text!!.paragraphs
        assertEquals(listOf("First point", "A detail", "Second point"), paragraphs.map { p -> p.runs.joinToString("") { it.text } })
        assertEquals("•", paragraphs[0].bullet!!.text)
        assertEquals(32f, paragraphs[0].runs[0].size)
        assertEquals(28f, paragraphs[1].runs[0].size)
        assertEquals(342_900f, paragraphs[0].marginLeft)
        assertEquals(-342_900f, paragraphs[0].indent)
        assertEquals(742_950f, paragraphs[1].marginLeft)
        assertEquals(Spacing.Percent(0.2f), paragraphs[0].spaceBefore)
    }

    @Test
    fun shapesPicturesGroupsTablesAndCharts() {
        val shapes = deck.slides[2].shapes
        val box = shapes[0] as Shape.Box
        assertEquals(Fill.Solid(0xFFFF0000.toInt()), box.fill)
        assertEquals("Red box", box.text())
        // Shape text takes the style's font colour (light 1) when it doesn't set one.
        assertEquals(white, box.text!!.paragraphs[0].runs[0].color)
        assertNotNull(box.stroke)

        val textBox = shapes[1] as Shape.Box
        assertNull(textBox.fill)
        val run = textBox.text!!.paragraphs[0].runs[0]
        assertEquals(24f, run.size)
        assertTrue(run.bold)
        assertEquals(0xFF123456.toInt(), run.color)

        val picture = shapes[2] as Shape.Picture
        assertTrue(picture.part!!.startsWith("ppt/media/"))

        val group = shapes[3] as Shape.Group
        assertEquals(listOf("ellipse", "rightArrow"), group.children.map { ((it as Shape.Box).geometry as Geometry.Preset).name })

        val table = shapes[4] as Shape.Table
        assertEquals(2, table.rows.size)
        assertEquals(2, table.columns.size)
        val head = table.rows[0].cells[0]
        assertEquals(Fill.Solid(accent1), head.fill) // PowerPoint's default table style: accent header row
        val headRun = head.text!!.paragraphs[0].runs[0]
        assertEquals("Head", headRun.text)
        assertEquals(white, headRun.color)
        assertTrue(headRun.bold)
        assertEquals("Cell", table.rows[1].cells[1].text!!.paragraphs[0].runs[0].text)
        assertEquals(black, table.rows[1].cells[1].text!!.paragraphs[0].runs[0].color)

        assertEquals("Chart", (shapes[5] as Shape.Unsupported).label)

        val line = shapes[6] as Shape.Box
        assertEquals("line", (line.geometry as Geometry.Preset).name)
        assertEquals(25_400f, line.stroke!!.width)
    }
}
