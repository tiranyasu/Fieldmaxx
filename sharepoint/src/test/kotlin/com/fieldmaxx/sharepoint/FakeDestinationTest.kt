package com.fieldmaxx.sharepoint

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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
    fun chunkedUploadShowsAnEmptyFileUntilItsLastChunk() {
        val d = FakeDestination().apply { ensureFolder("A") }
        val f = tmp(100)
        val seen = mutableListOf<Pair<Long, Long?>>()
        d.uploadChunked("A/p.jpg", f, 30, null) { seen += it.offset to d.fileInfo("A/p.jpg")?.size }
        assertEquals(listOf(0L, 30L, 60L, 90L, 100L), seen.map { it.first }, "R10: the session is reported before the first chunk")
        assertEquals(listOf(0L, 0L, 0L, 0L, 100L), seen.map { it.second }, "0 bytes until the last chunk is in")
        assertContentEquals(f.readBytes(), d.files["A/p.jpg"])

        // A file that fits one chunk goes in one request, with no empty file first and no progress.
        val small = mutableListOf<ChunkSession>()
        d.uploadChunked("A/s.jpg", tmp(20), 30, null) { small += it }
        assertEquals(emptyList(), small)
    }

    @Test
    fun aFreshSessionFillsAnEmptyFile() {
        val d = FakeDestination().apply { ensureFolder("A"); dropChunkAfter = 1 }
        val f = tmp(100)
        // Another uploader of this photo stopped after its first chunk: its empty file stays under the name.
        assertEquals(DestinationErrorCode.NETWORK, assertFailsWith<DestinationException> { d.uploadChunked("A/p.jpg", f, 30, null) {} }.code)
        assertEquals(0L, d.fileInfo("A/p.jpg")?.size)

        val progress = mutableListOf<ChunkSession>()
        d.uploadChunked("A/p.jpg", f, 30, ChunkSession("adopt-1", 0)) { progress += it }
        assertEquals(listOf(30L, 60L, 90L, 100L), progress.map { it.offset })
        assertEquals(setOf("adopt-1"), progress.map { it.uploadId }.toSet())
        assertContentEquals(f.readBytes(), d.files["A/p.jpg"])
        assertEquals(1, d.files.size)

        // A session into a file that isn't there finds nothing to fill.
        assertEquals(DestinationErrorCode.NOT_FOUND, assertFailsWith<DestinationException> { d.uploadChunked("A/none.jpg", f, 30, ChunkSession("x", 0)) {} }.code)
    }

    @Test
    fun aSecondSessionIsBusyWhileTheFirstIsOpen() {
        val d = FakeDestination().apply { ensureFolder("A"); busyWhileUploading = true; dropChunkAfter = 1 }
        val f = tmp(100)
        assertFailsWith<DestinationException> { d.uploadChunked("A/p.jpg", f, 30, null) {} } // its session stays open
        val e = assertFailsWith<DestinationException> { d.uploadChunked("A/p.jpg", f, 30, ChunkSession("adopt-1", 0)) {} }
        assertEquals(DestinationErrorCode.BUSY, e.code)
        assertEquals(0L, d.fileInfo("A/p.jpg")?.size, "nothing written")

        d.expireSessions() // SharePoint lets the lock go
        d.uploadChunked("A/p.jpg", f, 30, ChunkSession("adopt-2", 0)) {}
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

    @Test
    fun concurrentUploadsAreSafe() {
        val d = FakeDestination().apply { ensureFolder("A"); uploadDelayMs = 3 }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val ids = ConcurrentLinkedQueue<String>()
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val start = CountDownLatch(1)
        val files = (0 until 8).map { tmp(40) }
        val threads = (0 until 8).map { t ->
            Thread {
                try {
                    start.await()
                    for (i in 0 until 10) {
                        if (i % 2 == 0) {
                            val n = inFlight.incrementAndGet()
                            maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                            try { d.upload("A/t$t-$i.jpg", byteArrayOf(1)) } finally { inFlight.decrementAndGet() }
                        } else {
                            val n = inFlight.incrementAndGet()
                            maxInFlight.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                            try { d.uploadChunked("A/t$t-$i.jpg", files[t], 20, null) { ids.add(it.uploadId) } } finally { inFlight.decrementAndGet() }
                        }
                    }
                } catch (e: Throwable) { errors.add(e) }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(emptyList(), errors.toList())
        assertEquals(80, d.files.size)
        assertEquals(1 + 80, d.calls.size)
        assertEquals(40, ids.distinct().size)
        assertEquals(ids.size / 3, ids.distinct().size) // each: its empty file, then two chunks
        assert(maxInFlight.get() > 1) { "uploads never overlapped" }
    }
}
