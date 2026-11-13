package com.fieldmaxx.sharepoint

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/** In-memory [PhotoDestination] for tests of the server and the app. */
class FakeDestination(private val root: String = "Field Notes") : PhotoDestination {
    val folders: MutableSet<String> = mutableSetOf()
    val files: MutableMap<String, ByteArray> = mutableMapOf()
    val calls: MutableList<String> = mutableListOf()
    var uploadDelayMs: Long = 0

    /** One-shot: after this many chunks of one `uploadChunked` call, throw NETWORK (progress already reported). */
    var dropChunkAfter: Int? = null

    private val partials = mutableMapOf<String, ByteArrayOutputStream>()
    private var failuresLeft = 0
    private var failureCode = DestinationErrorCode.UNEXPECTED
    private var nextUploadId = 1

    fun failNext(n: Int, code: DestinationErrorCode) {
        failuresLeft = n
        failureCode = code
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
        delay()
        checkTarget(relPath)
        files[relPath] = bytes.copyOf()
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
            val id = "upload-${nextUploadId++}"
            partial = ByteArrayOutputStream().also { partials[id] = it }
            session = ChunkSession(id, 0)
        }
        var offset = session.offset.toInt()
        var sent = 0
        while (offset < data.size) {
            if (dropChunkAfter != null && sent >= dropChunkAfter!!) {
                dropChunkAfter = null
                throw DestinationException(DestinationErrorCode.NETWORK, "connection dropped")
            }
            delay()
            val end = minOf(data.size, offset + chunkBytes)
            partial.write(data, offset, end - offset)
            offset = end
            sent++
            onProgress(ChunkSession(session.uploadId, offset.toLong()))
        }
        files[relPath] = partial.toByteArray()
        partials.remove(session.uploadId)
    }

    override fun download(relPath: String, out: OutputStream) {
        begin("download $relPath")
        out.write(files[relPath] ?: throw notFound(relPath))
    }

    private fun begin(call: String) {
        calls.add(call)
        if (failuresLeft > 0) {
            failuresLeft--
            throw DestinationException(failureCode, "injected failure for $call")
        }
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
