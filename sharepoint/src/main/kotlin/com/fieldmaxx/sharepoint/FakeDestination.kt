package com.fieldmaxx.sharepoint

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** In-memory [PhotoDestination] for tests of the server and the app. Thread-safe: uploads may overlap. */
class FakeDestination(private val root: String = "Field Notes") : PhotoDestination {
    val folders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val calls: MutableList<String> = CopyOnWriteArrayList()
    @Volatile
    var uploadDelayMs: Long = 0

    /** One-shot: after this many chunks of one `uploadChunked` call, throw NETWORK (progress already reported). */
    @Volatile
    var dropChunkAfter: Int? = null

    private val partials = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val lock = Any()
    private var failuresLeft = 0
    private var failureCode = DestinationErrorCode.UNEXPECTED
    private val nextUploadId = AtomicInteger(1)

    fun failNext(n: Int, code: DestinationErrorCode) {
        synchronized(lock) {
            failuresLeft = n
            failureCode = code
        }
    }

    override fun rootName(): String = root

    override fun listFolders(relPath: String): List<String> {
        begin("listFolders $relPath")
        if (relPath.isNotEmpty() && relPath !in folders) throw notFound(relPath)
        val prefix = if (relPath.isEmpty()) "" else "$relPath/"
        return folders.filter { it.startsWith(prefix) && '/' !in it.removePrefix(prefix) }
            .map { it.removePrefix(prefix) }
            .sorted()
    }

    override fun ensureFolder(relPath: String) {
        begin("ensureFolder $relPath")
        var acc = ""
        for (part in relPath.split('/').filter { it.isNotEmpty() }) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            folders.add(acc)
        }
    }

    override fun fileInfo(relPath: String): FileInfo? {
        begin("fileInfo $relPath")
        return files[relPath]?.let { FileInfo(it.size.toLong()) }
    }

    override fun upload(relPath: String, bytes: ByteArray) {
        begin("upload $relPath")
        checkTarget(relPath)
        delay()
        store(relPath, bytes.copyOf())
    }

    override fun uploadChunked(relPath: String, file: File, chunkBytes: Int, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit) {
        begin("uploadChunked $relPath")
        checkTarget(relPath)
        val data = file.readBytes()
        val session: ChunkSession
        val partial: ByteArrayOutputStream
        if (resume != null) {
            val p = partials[resume.uploadId]
                ?: throw DestinationException(DestinationErrorCode.UPLOAD_SESSION_LOST, "upload session ${resume.uploadId} is gone")
            partial = p
            session = ChunkSession(resume.uploadId, partial.size().toLong())
        } else {
            val id = "upload-${nextUploadId.getAndIncrement()}"
            partial = ByteArrayOutputStream().also { partials[id] = it }
            session = ChunkSession(id, 0)
        }
        var offset = session.offset.toInt()
        var sent = 0
        while (offset < data.size) {
            if (shouldDrop(sent)) {
                throw DestinationException(DestinationErrorCode.NETWORK, "connection dropped")
            }
            delay()
            val end = minOf(data.size, offset + chunkBytes)
            partial.write(data, offset, end - offset)
            offset = end
            sent++
            onProgress(ChunkSession(session.uploadId, offset.toLong()))
        }
        store(relPath, partial.toByteArray())
        partials.remove(session.uploadId)
    }

    override fun download(relPath: String, out: OutputStream) {
        begin("download $relPath")
        out.write(files[relPath] ?: throw notFound(relPath))
    }

    private fun begin(call: String) {
        calls.add(call)
        val code = synchronized(lock) {
            if (failuresLeft > 0) {
                failuresLeft--
                failureCode
            } else null
        }
        if (code != null) throw DestinationException(code, "injected failure for $call")
    }

    private fun shouldDrop(sent: Int): Boolean = synchronized(lock) {
        val limit = dropChunkAfter
        if (limit != null && sent >= limit) {
            dropChunkAfter = null
            true
        } else false
    }

    private fun store(relPath: String, bytes: ByteArray) {
        if (files.putIfAbsent(relPath, bytes) != null) throw DestinationException(DestinationErrorCode.EXISTS, "$relPath already exists")
    }

    private fun delay() {
        if (uploadDelayMs > 0) Thread.sleep(uploadDelayMs)
    }

    private fun checkTarget(relPath: String) {
        if (relPath in files) throw DestinationException(DestinationErrorCode.EXISTS, "$relPath already exists")
        val parent = relPath.substringBeforeLast('/', "")
        if (parent.isNotEmpty() && parent !in folders) throw notFound(parent)
    }

    private fun notFound(path: String) = DestinationException(DestinationErrorCode.NOT_FOUND, "$path not found")
}
