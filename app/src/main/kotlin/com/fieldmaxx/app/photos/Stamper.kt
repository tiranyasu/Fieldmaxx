package com.fieldmaxx.app.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.fieldmaxx.core.stamp.stampLayout
import com.fieldmaxx.core.stamp.stampLines
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Photos above this are scaled down to it (spec §6.6: "images above 16 megapixels scaled to 16 MP (memory safety)"). */
const val MAX_STAMP_PIXELS = 16_000_000

/** R58: the pixel caps a stamp tries in turn while the phone runs out of memory. */
internal val STAMP_PIXEL_CAPS = listOf(MAX_STAMP_PIXELS, 8_000_000, 4_000_000)

private const val SHADOW = 0xE6000000.toInt()
private const val WRITE_BUFFER = 64 * 1024

/** The original can't be decoded at all (damaged, or not a picture): stamping it again won't help (R58). */
class UnreadablePhotoException(message: String) : IOException(message)

/** The largest size of [w]:[h]'s shape with at most [maxPixels] pixels; a photo within it keeps its size. */
fun targetSize(w: Int, h: Int, maxPixels: Int = MAX_STAMP_PIXELS): Pair<Int, Int> {
    require(w > 0 && h > 0 && maxPixels > 0) { "No size" }
    if (w.toLong() * h <= maxPixels) return w to h
    val scale = sqrt(maxPixels.toDouble() / (w.toDouble() * h))
    var tw = max(1, floor(w * scale).toInt())
    var th = max(1, floor(h * scale).toInt())
    while (tw.toLong() * th > maxPixels) if (tw >= th) tw-- else th-- // floating-point rounding never pushes it over
    return tw to th
}

/**
 * Makes the upload copy of a photo (spec §6.6 step 2): decoded by its content whatever the file is called (JPEG, HEIC, PNG
 * or WebP), sampled down by powers of two and then scaled exactly to at most 16 MP, turned upright by its EXIF
 * orientation, the stamp drawn bottom-left in white bold over a dark shadow (Task 3's lines and layout), then saved as a
 * JPEG of [quality] with its EXIF ([ExifWriter]; make and model from the original's own EXIF, else this phone's).
 *
 * [out] is either absent or the whole stamped photo: it is written beside it as `<name>.tmp`, synced, then renamed. A
 * phone that runs out of memory tries again at 8 MP, then 4 MP (R58), and else fails with an IOException; a file that
 * can't be decoded fails with [UnreadablePhotoException].
 */
object Stamper {
    fun stamp(original: File, out: File, meta: CaptureMeta, zone: ZoneId, quality: Int) =
        stamp(original, out, meta, zone, quality, ::decodeUpright)

    /** [decode] gives the upright photo at most its cap's pixels (tests stand in for it to run out of memory). */
    internal fun stamp(original: File, out: File, meta: CaptureMeta, zone: ZoneId, quality: Int, decode: (File, Int) -> Bitmap) {
        val lines = stampLines(meta.stampMeta(zone))
        val (make, model) = cameraOf(original)
        val tmp = File(out.parentFile, out.name + ".tmp")
        try {
            var outOfMemory: OutOfMemoryError? = null
            val written = STAMP_PIXEL_CAPS.any { cap ->
                try {
                    render(decode(original, cap), lines, tmp, quality)
                    true
                } catch (e: OutOfMemoryError) {
                    outOfMemory = e
                    false
                }
            }
            if (!written) throw IOException("Not enough memory to stamp this photo", outOfMemory)
            ExifWriter.write(tmp, meta, make ?: Build.MANUFACTURER, model ?: Build.MODEL, zone)
            FileOutputStream(tmp, true).use { it.fd.sync() }
            if (!tmp.renameTo(out)) throw IOException("Couldn't save the stamped photo")
        } finally {
            tmp.delete() // gone already once renamed
        }
    }

    private fun render(bitmap: Bitmap, lines: List<String>, to: File, quality: Int) {
        try {
            drawStamp(bitmap, lines)
            BufferedOutputStream(FileOutputStream(to), WRITE_BUFFER).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) throw IOException("Couldn't write the stamped photo")
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawStamp(bitmap: Bitmap, lines: List<String>) {
        val layout = stampLayout(bitmap.width, bitmap.height, lines.size)
        val font = layout.fontPx.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = font
            setShadowLayer(max(1, layout.shadowBlurPx).toFloat(), font / 16, font / 16, SHADOW)
        }
        val canvas = Canvas(bitmap)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, layout.x.toFloat(), (layout.firstBaselineY + i * layout.lineHeightPx).toFloat(), paint)
        }
    }

    /** The camera that took the photo, as its own EXIF names it; a picked photo keeps its camera's name. */
    private fun cameraOf(original: File): Pair<String?, String?> = try {
        ExifInterface(original).let { it.getAttribute(ExifInterface.TAG_MAKE) to it.getAttribute(ExifInterface.TAG_MODEL) }
    } catch (e: IOException) {
        null to null
    } catch (e: RuntimeException) {
        null to null
    }
}

/**
 * The photo in [file] upright, with at most [maxPixels] pixels and mutable: decoded with the largest power-of-two sample
 * that still covers the target size, then scaled exactly and turned (or mirrored) as its EXIF orientation says.
 * BitmapFactory reads the format from the content and never applies the orientation itself, on every Android version.
 */
internal fun decodeUpright(file: File, maxPixels: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) throw UnreadablePhotoException("This photo can't be read")
    val (tw, th) = targetSize(w, h, maxPixels)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(w, h, tw, th)
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
    val decoded = BitmapFactory.decodeFile(file.path, options) ?: throw UnreadablePhotoException("This photo can't be read")
    val (degrees, flipped) = orientationOf(file)
    if (decoded.width == tw && decoded.height == th && degrees == 0 && !flipped) return decoded
    try {
        val m = Matrix()
        m.postScale(tw.toFloat() / decoded.width, th.toFloat() / decoded.height)
        if (flipped) m.postScale(-1f, 1f)
        m.postRotate(degrees.toFloat())
        val box = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat()).also { m.mapRect(it) }
        m.postTranslate(-box.left, -box.top)
        val (ow, oh) = if (degrees % 180 == 0) tw to th else th to tw
        return Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888).also { Canvas(it).drawBitmap(decoded, m, Paint(Paint.FILTER_BITMAP_FLAG)) }
    } finally {
        decoded.recycle()
    }
}

/** The largest power of two that still decodes at least [tw]×[th] from [w]×[h]: what's left is a gentle downscale. */
internal fun sampleSize(w: Int, h: Int, tw: Int, th: Int): Int {
    var s = 1
    while (w / (s * 2) >= tw && h / (s * 2) >= th) s *= 2
    return s
}

/** Clockwise degrees and whether to mirror first, from the EXIF orientation; a file without EXIF is upright. */
private fun orientationOf(file: File): Pair<Int, Boolean> = try {
    ExifInterface(file).let { it.rotationDegrees to it.isFlipped }
} catch (e: IOException) {
    0 to false
} catch (e: RuntimeException) {
    0 to false
}
