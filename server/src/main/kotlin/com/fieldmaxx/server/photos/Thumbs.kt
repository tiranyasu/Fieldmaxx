package com.fieldmaxx.server.photos

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.slf4j.LoggerFactory

const val THUMB_MAX_SIDE = 320
private const val THUMB_QUALITY = 0.8f
private val log = LoggerFactory.getLogger("com.fieldmaxx.server.photos.Thumbs")

/** Decodes [src] subsampled so its longest side stays under about twice the thumbnail size (a 16 MP photo never fully decodes). */
private fun readSmall(src: Path): BufferedImage? {
    ImageIO.createImageInputStream(Files.newInputStream(src)).use { iis ->
        val readers = ImageIO.getImageReaders(iis)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = iis
            val longest = maxOf(reader.getWidth(0), reader.getHeight(0))
            val param = reader.defaultReadParam.apply {
                val step = maxOf(1, longest / (2 * THUMB_MAX_SIDE))
                setSourceSubsampling(step, step, 0, 0)
            }
            return reader.read(0, param)
        } finally {
            reader.dispose()
        }
    }
}

/**
 * Writes a JPEG thumbnail of [src] to [dst] (longest side at most [THUMB_MAX_SIDE], quality 0.8, never enlarged).
 * Returns false (and logs why) when [src] is not a readable image. Works headless; the file appears atomically.
 */
fun makeThumbnail(src: Path, dst: Path): Boolean {
    val tmp = dst.resolveSibling(dst.fileName.toString() + ".tmp")
    return try {
        val image = readSmall(src) ?: run { log.warn("No thumbnail for {}: not an image", src); return false }
        val scale = minOf(1.0, THUMB_MAX_SIDE.toDouble() / maxOf(image.width, image.height))
        val w = maxOf(1, Math.round(image.width * scale).toInt())
        val h = maxOf(1, Math.round(image.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB) // RGB: JPEG has no alpha
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(image.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null)
        } finally {
            g.dispose()
        }
        Files.createDirectories(dst.parent)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = THUMB_QUALITY
            }
            ImageIO.createImageOutputStream(Files.newOutputStream(tmp)).use { os ->
                writer.output = os
                writer.write(null, IIOImage(out, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (e: Exception) { // never Error: an OutOfMemoryError must not look like a bad image
        log.warn("Thumbnail failed for {}", src, e)
        runCatching { Files.deleteIfExists(tmp) }
        false
    }
}
