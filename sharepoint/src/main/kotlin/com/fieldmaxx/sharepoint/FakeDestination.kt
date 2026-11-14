package com.fieldmaxx.sharepoint

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory [PhotoDestination] for tests of the server and the app. Thread-safe: uploads may overlap. Uploads behave as
 * SharePoint's through the link (R10, R82): a file that needs more than one chunk is created empty first, its session is
 * reported (offset 0) before the first chunk, and it reads as 0 bytes until its last chunk is in. A session that stops
 * short stays open (until [expireSessions]); a fresh session into a file another one holds is accepted, or refused BUSY
 * with [busyWhileUploading].
 */
class FakeDestination(private val root: String = "Field Notes") : PhotoDestination {
    val folders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val files: MutableMap<String, ByteArray> = ConcurrentHashMap()
    val calls: MutableList<String> = CopyOnWriteArrayList()
    @Volatile
    var uploadDelayMs: Long = 0

    /** One-shot: after this many chunks of one `uploadChunked` call, throw NETWORK (progress already reported). */
    @Volatile
    var dropChunkAfter: Int? = null

    /** R82: a new session into a file another open session writes to is refused with BUSY (SharePoint's lock); else accepted. */
    @Volatile
    var busyWhileUploading = false

    /** An open upload session: the file it writes to, and the bytes it holds so far. */
    private class Session(val path: String) {
        val data = ByteArrayOutputStream()
    }

    private val sessions = ConcurrentHashMap<String, Session>()
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
        val data = file.readBytes()
        if (resume == null && data.size <= chunkBytes) { // one request, as SharePoint takes a small file
            checkTarget(relPath)
            delay()
            store(relPath, data)
            return
        }
        val (id, session) = when {
            resume == null -> {
                val id = "upload-${nextUploadId.getAndIncrement()}"
                val session = synchronized(lock) {
                    checkTarget(relPath)
                    files[relPath] = ByteArray(0) // R10: the empty file first
                    Session(relPath).also { sessions[id] = it }
                }
                onProgress(ChunkSession(id, 0))
                id to session
            }
            sessions[resume.uploadId]?.path == relPath -> resume.uploadId to sessions.getValue(resume.uploadId)
            resume.offset == 0L -> resume.uploadId to openInto(relPath, resume.uploadId)
            else -> throw DestinationException(DestinationErrorCode.UPLOAD_SESSION_LOST, "upload session ${resume.uploadId} is gone")
        }
        var offset = session.data.size()
        var sent = 0
        while (offset < data.size) {
            if (shouldDrop(sent)) {
                throw DestinationException(DestinationErrorCode.NETWORK, "connection dropped")
            }
            delay()
            val end = minOf(data.size, offset + chunkBytes)
            session.data.write(data, offset, end - offset)
            offset = end
            sent++
            if (offset == data.size) finish(id, session) // the last chunk: the file is whole before its progress is reported
            onProgress(ChunkSession(id, offset.toLong()))
        }
    }

    /** Every open upload session is forgotten, as SharePoint times them out: their files stay as they are (empty, say). */
    fun expireSessions() = sessions.clear()

    /** R82: a fresh session into the existing file [relPath] (filling an empty file another uploader left). */
    private fun openInto(relPath: String, id: String): Session = synchronized(lock) {
        if (relPath !in files) throw notFound(relPath)
        if (busyWhileUploading && sessions.values.any { it.path == relPath }) {
            throw DestinationException(DestinationErrorCode.BUSY, "$relPath is held by another upload")
        }
        Session(relPath).also { sessions[id] = it }
    }

    /** FinishUpload: the session's bytes become the file's. */
    private fun finish(id: String, session: Session) = synchronized(lock) {
        files[session.path] = session.data.toByteArray()
        sessions.remove(id)
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
