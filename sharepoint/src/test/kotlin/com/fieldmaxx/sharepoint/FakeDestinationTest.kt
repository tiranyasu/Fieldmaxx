package com.fieldmaxx.sharepoint

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FakeDestinationTest {
    private fun tmp(size: Int) = File.createTempFile("fake", ".bin").apply { deleteOnExit(); writeBytes(ByteArray(size) { it.toByte() }) }

    @Test
    fun uploadRulesExistsAndMissingParent() {
        val d = FakeDestination()
        assertEquals(DestinationErrorCode.NOT_FOUND, assertFailsWith<DestinationException> { d.upload("A/x.jpg", byteArrayOf(1)) }.code)
        d.ensureFolder("A")
        d.upload("A/x.jpg", byteArrayOf(1))
        assertEquals(DestinationErrorCode.EXISTS, assertFailsWith<DestinationException> { d.upload("A/x.jpg", byteArrayOf(2)) }.code)
        assertEquals(1L, d.fileInfo("A/x.jpg")?.size)
    }

    @Test
    fun droppedChunkResumesAtConfirmedOffset() {
        val d = FakeDestination().apply { ensureFolder("A"); dropChunkAfter = 2 }
        val f = tmp(100)
        var last: ChunkSession? = null
        assertEquals(DestinationErrorCode.NETWORK, assertFailsWith<DestinationException> { d.uploadChunked("A/p.jpg", f, 30, null) { last = it } }.code)
        assertEquals(60L, last!!.offset)
        d.uploadChunked("A/p.jpg", f, 30, last) { last = it }
        assertContentEquals(f.readBytes(), d.files["A/p.jpg"])
    }

    @Test
    fun unknownResumeSessionIsLost() {
        val d = FakeDestination().apply { ensureFolder("A") }
        val e = assertFailsWith<DestinationException> { d.uploadChunked("A/p.jpg", tmp(10), 5, ChunkSession("nope", 5)) {} }
        assertEquals(DestinationErrorCode.UPLOAD_SESSION_LOST, e.code)
    }

    @Test
    fun failNextThrowsThenRecovers() {
        val d = FakeDestination().apply { failNext(1, DestinationErrorCode.FORBIDDEN) }
        assertEquals(DestinationErrorCode.FORBIDDEN, assertFailsWith<DestinationException> { d.listFolders("") }.code)
        assertEquals(emptyList(), d.listFolders(""))
    }
}
