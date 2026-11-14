package com.fieldmaxx.app.photos

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** How far a photo got on its way to OneDrive, as its thumbnail shows it (spec §6.6.2: ⏳ ⬆ ✓ ⚠). */
enum class PhotoUiStatus { ON_PHONE, UPLOADING, IN_ONEDRIVE, STUCK }

/**
 * One of a point's photos as the point sheet shows it. [thumb] is a `java.io.File` (the copy on this phone) or a URL
 * String (the server's thumbnail); [backedUp]: the server's backup is in place; [error]: why it is stuck; [destPath]: its
 * place in OneDrive; [ownerId]: the tech who took it.
 */
data class PhotoView(
    val id: String,
    val thumb: Any,
    val status: PhotoUiStatus,
    val backedUp: Boolean,
    val error: String?,
    val destPath: String?,
    val ownerId: String,
)

/** The point's photos. A stub until Task 29 joins this phone's photos with the server's: none so far. */
fun photoViewsFor(pointId: String): Flow<List<PhotoView>> = flowOf(emptyList())
