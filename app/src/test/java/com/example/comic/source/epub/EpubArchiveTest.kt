package com.example.comic.source.epub

import com.example.comic.source.SourceException
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readsImagesInSpineOrder() {
        val file = epub(
            "META-INF/container.xml" to """
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
            """.trimIndent(),
            "OEBPS/content.opf" to """
                <?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf">
                  <manifest>
                    <item id="chapter" href="text/chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="cover" href="images/001.jpg" media-type="image/jpeg"/>
                    <item id="page2" href="images/002.png" media-type="image/png"/>
                  </manifest>
                  <spine>
                    <itemref idref="chapter"/>
                  </spine>
                </package>
            """.trimIndent(),
            "OEBPS/text/chapter1.xhtml" to """
                <html><body>
                  <img src="../images/001.jpg"/>
                  <image xlink:href="../images/002.png"/>
                </body></html>
            """.trimIndent(),
            "OEBPS/images/001.jpg" to "cover",
            "OEBPS/images/002.png" to "page",
        )

        assertEquals(
            listOf("OEBPS/images/001.jpg", "OEBPS/images/002.png"),
            pages(file),
        )
    }

    @Test
    fun fallsBackToNaturalSortedImagesWithoutContainer() {
        val file = epub(
            "a/010.jpg" to "10",
            "a/002.jpg" to "2",
            "z/001.png" to "z",
        )

        assertEquals(
            listOf("a/002.jpg", "a/010.jpg", "z/001.png"),
            pages(file),
        )
    }

    @Test
    fun rejectsEpubWithoutImages() {
        val file = epub(
            "META-INF/container.xml" to "<container/>",
        )

        val failure = runCatching { pages(file) }.exceptionOrNull()

        assertTrue(failure is SourceException)
    }

    private fun pages(file: File): List<String> =
        ZipFile(file).use { zip -> EpubArchive.pageEntries(zip) }

    private fun epub(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("book.epub")
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("mimetype"))
            output.write("application/epub+zip".toByteArray())
            output.closeEntry()
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}
