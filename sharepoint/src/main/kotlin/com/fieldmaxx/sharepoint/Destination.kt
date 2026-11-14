package com.fieldmaxx.sharepoint

import java.io.File
import java.io.OutputStream

data class FileInfo(val size: Long)

data class ChunkSession(val uploadId: String, val offset: Long)

/**
 * [BUSY]: SharePoint holds the file for another open upload session (its lock), so a new session into it is refused: that
 * upload is in progress. Look again later; it is not a failure (R82).
 */
enum class DestinationErrorCode { REQUIRES_SIGN_IN, FORBIDDEN, NOT_FOUND, EXISTS, NETWORK, UPLOAD_SESSION_LOST, BUSY, UNEXPECTED }

class DestinationException(val code: DestinationErrorCode, message: String) : Exception(message)

/** All paths are relative to the Field Notes folder, `/`-separated; `""` is the root. */
interface PhotoDestination {
    fun rootName(): String
    fun listFolders(relPath: String): List<String>
    fun ensureFolder(relPath: String)
    fun fileInfo(relPath: String): FileInfo?

    /** Small files (exports). */
    fun upload(relPath: String, bytes: ByteArray)

    /**
     * [file] to [relPath], in chunks of [chunkBytes]. With no [resume], a new file (EXISTS when the name is taken); one that
     * needs more than a chunk is created empty first and its session reported (offset 0) before any chunk, then reported
     * after each one; it reads as 0 bytes until its last chunk is in (R10). With [resume], the upload goes on in that
     * session; an id this destination never saw, at offset 0, opens a fresh session into the existing file: how an empty
     * file of the same photo, left by another uploader, is filled instead of renamed around (R82). BUSY: SharePoint holds
     * the file for another open session.
     */
    fun uploadChunked(relPath: String, file: File, chunkBytes: Int, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit)
    fun download(relPath: String, out: OutputStream)
}

typealias DestinationFactory = (fieldNotesUrl: String) -> PhotoDestination
