package io.github.dishan18.books.manga.source.mangadex

import io.github.dishan18.books.manga.data.SeriesStatus
import io.github.dishan18.books.manga.source.MangaDexSettings
import io.github.dishan18.books.manga.source.api.Listing
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import io.github.dishan18.books.manga.source.api.SourcePage
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

class MangaDexSourceTest {

    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun source(settings: MangaDexSettings = MangaDexSettings()) = MangaDexSource(
        client = OkHttpClient(),
        settings = settings,
        apiBase = server.url("/"),
        uploadsBase = server.url("/uploads").toString().trimEnd('/'),
        reportUrl = server.url("/report").toString(),
    )

    private fun json(body: String) = MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build()

    @Test
    fun popularListingParsesSeriesAndSendsFilters() = runTest {
        routes["/manga"] = json(
            """
            {"result":"ok","limit":30,"offset":0,"total":31,"data":[
              {"id":"m1","type":"manga","attributes":{"title":{"ja-ro":"Romaji","en":"English Title"},
                "altTitles":[],"description":[],"status":"ongoing","tags":[]},
               "relationships":[
                 {"id":"a1","type":"author","attributes":{"name":"Author One"}},
                 {"id":"c1","type":"cover_art","attributes":{"fileName":"cover.png"}}]}
            ]}
            """,
        )
        val page = source(MangaDexSettings(languages = listOf("en", "fr"), contentRatings = listOf("safe"))).browse(Listing.POPULAR, 1)

        val series = page.items.single()
        assertEquals("m1", series.key)
        assertEquals("English Title", series.title)
        assertEquals("Author One", series.author)
        assertEquals(SeriesStatus.ONGOING, series.status)
        assertTrue(series.coverUrl!!.endsWith("/uploads/covers/m1/cover.png.512.jpg"))
        assertTrue("31 total, 1 shown", page.hasNextPage)

        val url = requests.single().url
        assertEquals("desc", url.queryParameter("order[followedCount]"))
        assertEquals(listOf("safe"), url.queryParameterValues("contentRating[]"))
        assertEquals(listOf("en", "fr"), url.queryParameterValues("availableTranslatedLanguage[]"))
        assertEquals(listOf("cover_art", "author"), url.queryParameterValues("includes[]"))
    }

    @Test
    fun chaptersSkipExternalAndUnavailableAndAreOrdered() = runTest {
        routes["/manga/m1/feed"] = json(
            """
            {"result":"ok","limit":500,"offset":0,"total":5,"data":[
              {"id":"c10","attributes":{"volume":"2","chapter":"10","title":"Later","pages":20,"publishAt":"2024-02-01T00:00:00+00:00"},
               "relationships":[{"id":"g1","type":"scanlation_group","attributes":{"name":"Group A"}}]},
              {"id":"c2","attributes":{"volume":null,"chapter":"2","title":"","pages":18,"publishAt":"2024-01-02T00:00:00+00:00"},"relationships":[]},
              {"id":"ext","attributes":{"chapter":"3","externalUrl":"https://publisher.example/3","pages":0},"relationships":[]},
              {"id":"gone","attributes":{"chapter":"4","isUnavailable":true,"pages":10},"relationships":[]},
              {"id":"c1","attributes":{"volume":"1","chapter":"1","title":"Start","pages":22,"publishAt":"2024-01-01T00:00:00+00:00"},"relationships":[]}
            ]}
            """,
        )
        val chapters = source().chapters("m1")

        assertEquals(listOf("c1", "c2", "c10"), chapters.map { it.key })
        assertEquals("Vol. 1 Ch. 1 - Start", chapters[0].name)
        assertEquals("Ch. 2", chapters[1].name)
        assertEquals("Group A", chapters[2].scanlator)
        assertEquals(10f, chapters[2].number)
        assertEquals(1704067200000L, chapters[0].uploadedAt)

        val url = requests.single().url
        assertEquals("0", url.queryParameter("includeExternalUrl"))
        assertEquals("0", url.queryParameter("includeEmptyPages"))
    }

    @Test
    fun pagesBuildAtHomeUrlsAndHonourDataSaver() = runTest {
        routes["/at-home/server/c1"] = json(
            """{"result":"ok","baseUrl":"https://node.example","chapter":{"hash":"abc","data":["1.jpg","2.jpg"],"dataSaver":["1s.jpg","2s.jpg"]}}""",
        )
        val full = source().pages("m1", "c1")
        assertEquals(listOf("https://node.example/data/abc/1.jpg", "https://node.example/data/abc/2.jpg"), full.map { it.url })

        val saver = source(MangaDexSettings(dataSaver = true)).pages("m1", "c1")
        assertEquals("https://node.example/data-saver/abc/1s.jpg", saver.first().url)
    }

    @Test
    fun imageFetchWritesFileAndReportsToMangaDex() = runTest {
        routes["/node/data/abc/1.jpg"] = MockResponse.Builder().body("image-bytes").addHeader("X-Cache", "HIT").build()
        routes["/report"] = json("""{"result":"ok"}""")
        val destination = temp.root.resolve("page.jpg")

        source().fetchPage(SourcePage(0, server.url("/node/data/abc/1.jpg").toString()), destination)

        assertEquals("image-bytes", destination.readText())
        assertFalse(temp.root.resolve("page.jpg.part").exists())
        val report = waitForRequest("/report")
        val body = report.body!!.utf8()
        assertTrue(body, body.contains("\"success\":true") && body.contains("\"cached\":true") && body.contains("\"bytes\":11"))
    }

    @Test
    fun rateLimitResponseBecomesTypedError() = runTest {
        routes["/manga"] = MockResponse.Builder().code(429).addHeader("Retry-After", "60").build()
        try {
            source().search("anything", 1)
            fail("expected an error")
        } catch (e: SourceException) {
            assertEquals(SourceError.RateLimited(60), e.error)
        }
    }

    @Test
    fun missingTitleFallsBackToAltTitles() = runTest {
        routes["/manga/m2"] = json(
            """{"result":"ok","data":{"id":"m2","attributes":{"title":{"ja":"題名"},"altTitles":[{"en":"Alt English"}],
               "description":{"en":"  Summary  "},"status":"completed","tags":[
                 {"id":"t1","attributes":{"name":{"en":"Action"},"group":"genre"}},
                 {"id":"t2","attributes":{"name":{"en":"Long Strip"},"group":"format"}}]},"relationships":[]}}""",
        )
        val details = source().details("m2")
        assertEquals("題名", details.title)
        assertEquals("Summary", details.description)
        assertEquals(listOf("Action"), details.genres)
        assertEquals(SeriesStatus.COMPLETED, details.status)
        assertNull(details.coverUrl)
    }

    private fun waitForRequest(path: String): RecordedRequest {
        repeat(50) {
            synchronized(requests) { requests.firstOrNull { it.url.encodedPath == path } }?.let { return it }
            Thread.sleep(20)
        }
        error("No request to $path")
    }
}
