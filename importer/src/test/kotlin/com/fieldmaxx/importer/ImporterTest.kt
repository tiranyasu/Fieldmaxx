package com.fieldmaxx.importer

import com.fieldmaxx.core.model.ImportFlag
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

private const val NOTES =
    "     NOTE FOR AERIAL PATH\r1. IDENTIFY THE FEASIBILITY OF ATTACHING FIBER ON POLES THROUGHOUT THE PROPOSED CABLE ROUTE ON POLE.\r2. PLEASE MARK THE POLES IN BETWEEN THE CABLE PATH IN CASE IF WE HAVE MISSED ANY OF THEM."

private fun annotationPdf(vararg texts: String, lines: List<Triple<String, Float, Float>> = emptyList()) =
    makePdf(listOf(PdfPageSpec(lines = lines, freeText = texts.toList(), images = listOf(PdfImageSpec(solidPng(), 50f, 100f, 40f, 40f)))))

class ImporterTest {
    @Test fun aerialPdf() {
        val r = importFile("A05J9XE.pdf", annotationPdf(NOTES, AERIAL))
        assertEquals("pdf", r.kind)
        assertEquals((1..7).map { "POLE $it" }, r.points.map { it.label })
        assertTrue("NOTE FOR AERIAL PATH" in r.notes)
        assertTrue(r.unread.isEmpty())
        r.points.forEach { assertTrue("page-1" in it.refImageIds, it.label) }
        assertEquals("POLE", r.layerSuggestion)
        assertTrue(r.refImages.any { it.id == "page-1" && it.kind == "page" && it.page == 1 })
        assertTrue(r.refImages.any { it.kind == "item" })
        assertEquals(r.refImages.map { it.id }.toSet(), r.images.map { it.id }.toSet())
    }

    @Test fun queryDocx() {
        val r = importFile("QUERY_1_-_A05K51V.docx", makeDocx(QUERY_LISTS, QUERY_BODY))
        assertEquals("docx", r.kind)
        assertEquals(26, r.points.size)
        assertEquals("QUERY 1", r.layerSuggestion)
        assertEquals(2, r.points[22].refImageIds.size)
        assertTrue(r.unread.isEmpty())
    }

    @Test fun queryItemsMentioningPolesStayQueryItems() {
        val r = importFile("QUERY_1_-_A05K51V.docx", makeDocx(QUERY_LISTS, QUERY_BODY))
        assertEquals(26, r.points.count { it.label.startsWith("Query 1-") })
        assertEquals(0, r.points.count { it.label.startsWith("POLE") })
    }

    @Test fun queryDocxAsPdfSameLabels() {
        assumeTrue(sofficeAvailable(), "soffice not installed")
        val dir = Files.createTempDirectory("fmx-soffice").toFile()
        try {
            val docx = File(dir, "QUERY_1_-_A05K51V.docx").also { it.writeBytes(makeDocx(QUERY_LISTS, QUERY_BODY)) }
            val pdf = convertToPdf(docx, dir)
            val a = importFile(docx.name, docx.readBytes())
            val b = importFile(pdf.name, pdf.readBytes())
            assertEquals(a.points.map { it.label }, b.points.map { it.label })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun unparseableLineReported() {
        val r = importFile("job.pdf", annotationPdf("POLE 8 = 39.6361,-119.6912\rPOLE 9 = 39.63,abc"))
        assertEquals(1, r.points.size)
        val u = r.unread.single()
        assertEquals("POLE 9 = 39.63,abc", u.text)
        assertEquals(1, u.sourcePage)
    }

    @Test fun outOfRangePairIsUnreadNotDropped() {
        val r = importFile("job.pdf", annotationPdf("POLE 8 = 39.6361,-119.6912\rPOLE 9 = 95.123456,-199.123456"))
        assertEquals(1, r.points.size)
        assertEquals("POLE 9 = 95.123456,-199.123456", r.unread.single().text)
    }

    @Test fun strayCoordinatesFlaggedUnlabelled() {
        val r = importFile("job.pdf", annotationPdf("POLE 1 = 39.6361,-119.6912\rsee also 39.6400,-119.7000"))
        assertEquals(2, r.points.size)
        val stray = r.points.single { ImportFlag.unlabelled in it.flags }
        assertEquals(39.64, stray.lat)
        assertEquals(-119.7, stray.lon)
        assertEquals(1, stray.sourcePage)
        assertTrue("page-1" in stray.refImageIds)
    }

    @Test fun annotationAndPageTextNotDoubled() {
        val lines = AERIAL.split('\r').filter { it.isNotBlank() && it.any(Char::isDigit) }
            .mapIndexed { i, t -> Triple(t.trim(), 40f, 700f - i * 14f) }
        val r = importFile("job.pdf", annotationPdf(NOTES, AERIAL, lines = listOf(Triple("NOTE FOR AERIAL PATH", 40f, 740f)) + lines))
        assertEquals((1..7).map { "POLE $it" }, r.points.map { it.label }.distinct().sortedBy { it.removePrefix("POLE ").toInt() })
        assertEquals(r.points.size, r.points.map { it.label }.distinct().size)
        assertEquals(1, Regex("NOTE FOR AERIAL PATH").findAll(r.notes).count())
        assertTrue(r.points.none { ImportFlag.duplicate_label in it.flags })
    }

    @Test fun unsupportedType() {
        val e = assertFailsWith<ImportException> { importFile("job.xlsx", byteArrayOf(1)) }
        assertEquals("unsupported_type", e.code)
    }
}

internal fun sofficeAvailable(): Boolean = runCatching {
    ProcessBuilder("soffice", "--version").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0
}.getOrDefault(false)

internal fun convertToPdf(docx: File, outDir: File): File {
    val profile = Files.createTempDirectory("fmx-lo-profile").toFile()
    try {
        val p = ProcessBuilder(
            "soffice", "-env:UserInstallation=file://${profile.absolutePath}", "--headless", "--convert-to", "pdf",
            "--outdir", outDir.absolutePath, docx.absolutePath,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertEquals(0, p.waitFor())
    } finally {
        profile.deleteRecursively()
    }
    return assertNotNull(File(outDir, docx.nameWithoutExtension + ".pdf").takeIf { it.exists() })
}
