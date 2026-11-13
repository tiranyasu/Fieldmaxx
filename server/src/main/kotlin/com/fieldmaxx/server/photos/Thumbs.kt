package com.fieldmaxx.server.photos

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

const val THUMB_MAX_SIDE = 320
private const val THUMB_QUALITY = 0.8f

/**
 * Writes a JPEG thumbnail of [src] to [dst] (longest side at most [THUMB_MAX_SIDE], quality 0.8, never enlarged).
 * Returns false when [src] is not a readable image. Works headless; the file appears atomically.
 */
fun makeThumbnail(src: Path, dst: Path): Boolean {
    val image = runCatching { Files.newInputStream(src).use(ImageIO::read) }.getOrNull() ?: return false
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
    val tmp = dst.resolveSibling(dst.fileName.toString() + ".tmp")
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
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: Exception) {
        Files.deleteIfExists(tmp)
        return false
    } finally {
        writer.dispose()
    }
    return true
}
