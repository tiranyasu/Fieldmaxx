package com.fieldmaxx.importer

/** One piece of a job file in reading order. [page] is null for formats without fixed pages (DOCX). */
sealed interface DocBlock { val page: Int? }

/** A paragraph. [listLabel] is the item number Word renders from list numbering (for example `14.`), or null. */
data class ParaBlock(val text: String, val listLabel: String?, override val page: Int?) : DocBlock

data class ImageBlock(val imageId: String, override val page: Int?) : DocBlock

class ExtractedImage(val id: String, val mime: String /* image/png|image/jpeg */, val bytes: ByteArray)

class ImportException(val code: String /* unreadable_pdf|unreadable_docx|unsupported_type|too_many_pages */, message: String) : Exception(message)

data class DocxExtract(val blocks: List<DocBlock>, val images: List<ExtractedImage>)
