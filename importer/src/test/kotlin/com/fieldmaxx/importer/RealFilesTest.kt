package com.fieldmaxx.importer

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

private val PRIVATE = File("src/test/resources/private")

class RealFilesTest {
    private fun need(name: String): File {
        val f = File(PRIVATE, name)
        assumeTrue(f.exists(), "private sample $name not present")
        return f
    }

    @Test fun aerialPdf() {
        val f = need("A05J9XE.pdf")
        val r = importFile(f.name, f.readBytes())
        assertEquals((1..7).map { "POLE $it" }, r.points.map { it.label })
        assertEquals(39.63609452036168, r.points[0].lat!!, 1e-12)
        assertEquals(-119.69104360818118, r.points[0].lon!!, 1e-12)
        assertEquals(39.63616773905345, r.points[6].lat!!, 1e-12)
        assertEquals(-119.67284033048661, r.points[6].lon!!, 1e-12)
        assertTrue(r.unread.isEmpty(), r.unread.toString())
    }

    private fun queryPoints(r: ImportResult) {
        assertEquals(26, r.points.size)
        fun at(label: String) = r.points.single { it.label == label }
        assertEquals(39.35535679, at("Query 1-1").lat!!, 1e-9)
        assertEquals(-119.81288376, at("Query 1-1").lon!!, 1e-9)
        assertEquals(39.3548480, at("Query 1-25A").lat!!, 1e-9)
        assertEquals(-119.8126657, at("Query 1-25A").lon!!, 1e-9)
        assertEquals(39.3540205, at("Query 1-25B").lat!!, 1e-9)
        assertEquals(-119.8126599, at("Query 1-25B").lon!!, 1e-9)
    }

    @Test fun queryDocx() {
        val f = need("QUERY_1_-_A05K51V.docx")
        queryPoints(importFile(f.name, f.readBytes()))
    }

    @Test fun queryDocxConvertedToPdfGivesSameLabels() {
        val f = need("QUERY_1_-_A05K51V.docx")
        assumeTrue(sofficeAvailable(), "soffice not installed")
        val dir = Files.createTempDirectory("fmx-real").toFile()
        try {
            val pdf = convertToPdf(f, dir)
            val a = importFile(f.name, f.readBytes())
            val b = importFile(pdf.name, pdf.readBytes())
            assertEquals(a.points.map { it.label }, b.points.map { it.label })
        } finally {
            dir.deleteRecursively()
        }
    }
}
