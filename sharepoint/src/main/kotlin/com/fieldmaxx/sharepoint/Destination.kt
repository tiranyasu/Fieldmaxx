package com.fieldmaxx.sharepoint

import java.io.File
import java.io.OutputStream

data class FileInfo(val size: Long)

data class ChunkSession(val uploadId: String, val offset: Long)

enum class DestinationErrorCode { REQUIRES_SIGN_IN, FORBIDDEN, NOT_FOUND, EXISTS, NETWORK, UPLOAD_SESSION_LOST, UNEXPECTED }

class DestinationException(val code: DestinationErrorCode, message: String) : Exception(message)

/** All paths are relative to the Field Notes folder, `/`-separated; `""` is the root. */
interface PhotoDestination {
    fun rootName(): String
    fun listFolders(relPath: String): List<String>
    fun ensureFolder(relPath: String)
    fun fileInfo(relPath: String): FileInfo?

    /** Small files (exports). */
    fun upload(relPath: String, bytes: ByteArray)
    fun uploadChunked(relPath: String, file: File, chunkBytes: Int, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit)
    fun download(relPath: String, out: OutputStream)
}

typealias DestinationFactory = (fieldNotesUrl: String) -> PhotoDestination
