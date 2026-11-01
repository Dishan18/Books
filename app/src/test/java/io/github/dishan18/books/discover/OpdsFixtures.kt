package io.github.dishan18.books.discover

/** Trimmed copies of real catalog responses (Project Gutenberg's OPDS, and a Calibre-style feed). */
object OpdsFixtures {
    val gutenbergSearch = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:opensearch="http://a9.com/-/spec/opensearch/1.1/">
        <id>http://www.gutenberg.org/ebooks/search.opds/?query=dickens</id>
        <title>Books: dickens</title>
        <link rel="self" type="application/atom+xml;profile=opds-catalog" href="/ebooks/search.opds/?query=dickens"/>
        <link rel="next" title="Next Page" type="application/atom+xml;profile=opds-catalog" href="/ebooks/search.opds/?query=dickens&amp;start_index=26"/>
        <entry>
          <id>https://www.gutenberg.org/ebooks/subjects/search.opds/?query=dickens</id>
          <title>Subjects</title>
          <content type="text">27 subject headings match your search.</content>
          <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/subjects/search.opds/?query=dickens"/>
        </entry>
        <entry>
          <id>https://www.gutenberg.org/ebooks/98.opds</id>
          <title>A Tale of Two Cities</title>
          <content type="text">Charles Dickens</content>
          <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/98.opds"/>
          <link type="image/png" rel="http://opds-spec.org/image/thumbnail" href="data:image/png;base64,iVBORw0KGgo="/>
        </entry>
        <entry>
          <id>https://www.gutenberg.org/ebooks/1400.opds</id>
          <title>Great Expectations</title>
          <content type="text">Charles Dickens</content>
          <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/1400.opds"/>
        </entry>
        </feed>
    """.trimIndent()

    val gutenbergBook = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xmlns:dcterms="http://purl.org/dc/terms/">
        <id>http://www.gutenberg.org/ebooks/1342.opds</id>
        <title>Pride and Prejudice by Jane Austen</title>
        <entry>
          <title>Pride and Prejudice</title>
          <content type="xhtml">
            <div xmlns="http://www.w3.org/1999/xhtml">
              <p>This edition had all images removed.</p>
              <p>Summary: "Pride and Prejudice" by Jane Austen is a novel published in 1813.</p>
              <p>Language: English</p>
            </div>
          </content>
          <id>urn:gutenberg:1342:2</id>
          <rights>Public domain in the USA.</rights>
          <author><name>Austen, Jane</name></author>
          <category scheme="http://purl.org/dc/terms/LCSH" term="England -- Fiction"/>
          <category scheme="http://purl.org/dc/terms/LCSH" term="Love stories"/>
          <category scheme="http://purl.org/dc/terms/DCMIType" term="Text"/>
          <dcterms:language>en</dcterms:language>
          <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB (older e-readers, no images)" length="609138" href="https://www.gutenberg.org/ebooks/1342.epub.noimages"/>
          <link type="application/x-mobipocket-ebook" rel="http://opds-spec.org/acquisition" title="Kindle (no images)" length="540013" href="https://www.gutenberg.org/ebooks/1342.kindle.noimages"/>
          <link type="image/jpeg" rel="http://opds-spec.org/image" href="https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg"/>
          <link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/author/68.opds" title="By Austen, Jane…"/>
          <link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/639.opds" title="In Category: Romance…"/>
          <link type="application/atom+xml;profile=opds-catalog" rel="related" href="/ebooks/bookshelf/13.opds" title="In Best Books Ever Listings…"/>
        </entry>
        <entry>
          <title>Pride and Prejudice</title>
          <id>urn:gutenberg:1342:3</id>
          <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB3 (E-readers incl. Send-to-Kindle)" length="24864457" href="https://www.gutenberg.org/ebooks/1342.epub3.images"/>
          <link type="application/epub+zip" rel="http://opds-spec.org/acquisition" title="EPUB (older e-readers)" length="24876678" href="https://www.gutenberg.org/ebooks/1342.epub.images"/>
        </entry>
        </feed>
    """.trimIndent()

    /** A home server's catalog: navigation, books with relative links, and links that aren't free. */
    val calibreRoot = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <id>urn:calibre:root</id>
          <title>My Library</title>
          <link rel="search" type="application/opensearchdescription+xml" href="/opds/search.xml"/>
          <link rel="next" type="application/atom+xml;profile=opds-catalog;kind=acquisition" href="?offset=2"/>
          <entry>
            <id>urn:calibre:authors</id>
            <title>By Author</title>
            <content type="text">Books sorted by author</content>
            <link rel="subsection" type="application/atom+xml;profile=opds-catalog;kind=navigation" href="/opds/authors"/>
          </entry>
          <entry>
            <id>urn:uuid:book-1</id>
            <title>The Hobbit</title>
            <author><name>J. R. R. Tolkien</name></author>
            <summary type="html">&lt;p&gt;In a hole in the ground &amp;amp; more.&lt;/p&gt;</summary>
            <category term="Fantasy" label="Fantasy"/>
            <link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/get/epub/1"/>
            <link rel="http://opds-spec.org/acquisition/buy" type="application/pdf" href="https://shop.example/buy/1"/>
            <link rel="http://opds-spec.org/image/thumbnail" type="image/jpeg" href="/thumb/1"/>
          </entry>
          <entry>
            <id>urn:uuid:book-2</id>
            <title>Store Only</title>
            <link rel="http://opds-spec.org/acquisition/borrow" type="application/epub+zip" href="/borrow/2"/>
            <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-mobipocket-ebook" href="/get/mobi/2"/>
          </entry>
        </feed>
    """.trimIndent()

    val openSearch = """
        <?xml version="1.0" encoding="UTF-8"?>
        <OpenSearchDescription xmlns="http://a9.com/-/spec/opensearch/1.1/">
          <ShortName>Calibre</ShortName>
          <Url type="text/html" template="/browse/search?q={searchTerms}"/>
          <Url type="application/atom+xml" template="/opds/search/{searchTerms}?page={startPage?}"/>
        </OpenSearchDescription>
    """.trimIndent()
}
