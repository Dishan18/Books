package io.github.dishan18.books.manga.source.komga

import io.github.dishan18.books.manga.data.SeriesStatus
import io.github.dishan18.books.manga.source.api.Listing
import io.github.dishan18.books.manga.source.api.SourceError
import io.github.dishan18.books.manga.source.api.SourceException
import io.github.dishan18.books.manga.source.net.HostAuthInterceptor
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

class KomgaSourceTest {

    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
    private val routes = mutableMapOf<String, MockResponse>()
    private val auth = mapOf("Authorization" to Credentials.basic("reader", "secret"))

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.headers["Authorization"] != auth.getValue("Authorization")) {
                    return MockResponse.Builder().code(401).build()
                }
                return routes[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun source(headers: Map<String, String> = auth): KomgaSource {
        val base = server.url("/komga").toString()
        val client = OkHttpClient.Builder().addInterceptor(HostAuthInterceptor(server.hostName, headers)).build()
        return KomgaSource("komga-1", "Home", client, base, headers)
    }

    private fun json(body: String) = MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build()

    @Test
    fun browseParsesSeriesAndPaging() = runTest {
        routes["/komga/api/v1/series"] = json(
            """{"content":[{"id":"s1","name":"folder-name","metadata":{"title":"Real Title","status":"ENDED"},
                "booksMetadata":{"authors":[{"name":"Writer","role":"writer"},{"name":"Artist","role":"penciller"}]}}],
                "number":0,"totalPages":3,"last":false}""",
        )
        val page = source().browse(Listing.POPULAR, 1)

        val series = page.items.single()
        assertEquals("Real Title", series.title)
        assertEquals("Writer", series.author)
        assertEquals(SeriesStatus.COMPLETED, series.status)
        assertEquals(server.url("/komga/api/v1/series/s1/thumbnail").toString(), series.coverUrl)
        assertEquals(true, page.hasNextPage)
        val url = requests.single().url
        assertEquals("0", url.queryParameter("page"))
        assertEquals("metadata.titleSort,asc", url.queryParameter("sort"))
    }

    @Test
    fun chaptersAndPagesUseBookIds() = runTest {
        routes["/komga/api/v1/series/s1/books"] = json(
            """{"content":[
                {"id":"b1","name":"Vol 01","metadata":{"title":"","numberSort":1.0,"releaseDate":"2020-05-01"},"media":{"pagesCount":3}},
                {"id":"b2","name":"Vol 02","metadata":{"title":"Second","numberSort":2.0},"media":{"pagesCount":0}}],"last":true}""",
        )
        routes["/komga/api/v1/books/b1/pages"] = json("""[{"number":2},{"number":1},{"number":3}]""")
        val chapters = source().chapters("s1")

        assertEquals(listOf("Vol 01", "Second"), chapters.map { it.name })
        assertEquals(3, chapters[0].pageCount)
        assertEquals(null, chapters[1].pageCount)
        assertEquals(1588291200000L, chapters[0].uploadedAt)

        val pages = source().pages("s1", "b1")
        assertEquals(
            listOf(1, 2, 3).map { server.url("/komga/api/v1/books/b1/pages/$it").toString() },
            pages.map { it.url },
        )
    }

    @Test
    fun wrongCredentialsAreReportedAsAuthError() = runTest {
        try {
            source(headers = mapOf("Authorization" to Credentials.basic("reader", "wrong"))).browse(Listing.LATEST, 1)
            fail("expected an error")
        } catch (e: SourceException) {
            assertEquals(SourceError.AuthRequired, e.error)
        }
    }

    @Test
    fun authHeaderIsNeverSentToOtherHosts() {
        val other = MockWebServer().apply { start() }
        try {
            val interceptor = HostAuthInterceptor("komga.invalid", auth)
            val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
            other.enqueue(MockResponse.Builder().body("ok").build())
            client.newCall(okhttp3.Request.Builder().url(other.url("/")).build()).execute().close()
            assertFalse(other.takeRequest().headers.names().contains("Authorization"))
        } finally {
            other.close()
        }
    }
}
