package com.fieldmaxx.app.point

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import java.io.IOException

/** One of a query point's reference images: its id, and its address ([com.fieldmaxx.app.sync.refImageUrl]). */
internal data class RefImage(val id: String, val url: String)

/**
 * "Client query · reference" (spec §6.4): the query file's images for this point, full width. They load through
 * [imageLoader], the app's signed-in loader, from the disk cache that opening the job filled, so they show without signal
 * (R21); one that never reached the phone says so.
 */
@Composable
internal fun QueryReference(images: List<RefImage>, imageLoader: ImageLoader) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(PointTags.REFERENCE)) {
        Text("Client query · reference", style = MaterialTheme.typography.titleMedium)
        if (images.isEmpty()) {
            Text("The query file has no images for this point.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        images.forEachIndexed { i, image -> ReferenceImage(image, "Reference image ${i + 1}", imageLoader) }
    }
}

@Composable
private fun ReferenceImage(image: RefImage, description: String, imageLoader: ImageLoader) {
    var state by remember(image.url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    // No fixed height on the image itself: Coil sizes the request from it, and a full-width image must decode full width.
    Box(Modifier.fillMaxWidth().testTag(PointTags.refImage(image.id))) {
        AsyncImage(
            model = image.url,
            contentDescription = description,
            imageLoader = imageLoader,
            onState = { state = it },
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
        val s = state
        if (s !is AsyncImagePainter.State.Success) {
            Box(
                Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        s !is AsyncImagePainter.State.Error -> "Loading…"
                        s.result.throwable is IOException -> "Not on this phone yet: it loads once there's signal."
                        else -> "This image couldn't be loaded."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
