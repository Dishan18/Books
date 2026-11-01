package io.github.dishan18.books.data

import android.content.Context
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/** Opens EPUB files with the Readium toolkit. */
class Readium(context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    suspend fun open(file: File): Result<Publication> {
        val asset = assetRetriever.retrieve(file)
            .getOrElse { return Result.failure(IllegalStateException(it.message)) }
        return opener.open(asset, allowUserInteraction = false).fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                asset.close()
                Result.failure(IllegalStateException(it.message))
            },
        )
    }
}
