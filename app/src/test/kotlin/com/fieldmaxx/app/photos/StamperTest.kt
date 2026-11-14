package com.fieldmaxx.app.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.fieldmaxx.core.stamp.stampLayout
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

private val ZONE: ZoneId = ZoneId.of("America/Los_Angeles")

/** 11/05/26 13:35:47 at the job site (UTC-08:00), the spec's stamp. */
private val TAKEN = ZonedDateTime.of(2026, 11, 5, 13, 35, 47, 0, ZONE).toInstant().toEpochMilli()

/** The spec's stamp: 4.877 m is ±16 ft and 1350.26 m is 4430 ft; NE 47° from true north. */
private val META = CaptureMeta(
    jobName = "A05J9XE", pointLabel = "POLE 1", source = SOURCE_CAMERA, lat = 39.636130, lon = -119.691104, accuracyM = 4.877,
    altitudeM = 1350.26, headingDeg = 47.0, takenAt = TAKEN, gpsStale = false, headingTrueNorth = true, techId = "u1",
)

private val BLUE = Color.rgb(60, 110, 160)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StamperTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- the brief's tests ----

    @Test fun targetSizeCaps48MpTo16MpKeepingAspect() {
        assertEquals(4618 to 3464, targetSize(8000, 6000))
        assertEquals(3464 to 4618, targetSize(6000, 8000), "a portrait photo the same, turned")
        val (w, h) = targetSize(8000, 6000)
        assertTrue(w.toLong() * h <= 16_000_000, "never above 16 MP")
        assertEquals(8000.0 / 6000, w.toDouble() / h, 0.001, "keeps the aspect")
    }

    @Test fun keeps3000x4000() {
        assertEquals(3000 to 4000, targetSize(3000, 4000))
        assertEquals(4000 to 4000, targetSize(4000, 4000), "exactly 16 MP stays as it is")
    }

    @Test fun stampedFileHasExifGpsDirectionAndDate() {
        val original = jpeg("gps.jpg", flat(400, 300)) { exif ->
            exif.setAttribute(ExifInterface.TAG_MAKE, "Google")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Pixel 8")
        }
        val out = File(tmp.root, "stamped.jpg")
        Stamper.stamp(original, out, META, ZONE, 85)

        val exif = ExifInterface(out)
        val (lat, lon) = assertNotNull(exif.latLong)
        assertEquals(39.636130, lat, 1e-6)
        assertEquals(-119.691104, lon, 1e-6)
        assertEquals(1350.26, exif.getAltitude(Double.NaN), 0.01)
        assertEquals(4.877, exif.getAttributeDouble(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, Double.NaN), 0.001)
        assertEquals(47.0, exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, Double.NaN), 0.01)
        assertEquals("T", exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF), "true north (R51)")
        assertEquals("2026:11:05 13:35:47", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), "the stamp's own time, local")
        assertEquals("-08:00", exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals("Google", exif.getAttribute(ExifInterface.TAG_MAKE), "the camera that took it")
        assertEquals("Pixel 8", exif.getAttribute(ExifInterface.TAG_MODEL))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1), "the pixels are upright")

        // A magnetic reading is tagged as one (R51), and a photo with no camera EXIF gets this phone's make and model.
        val magnetic = File(tmp.root, "magnetic.jpg")
        Stamper.stamp(jpeg("plain.jpg", flat(400, 300)), magnetic, META.copy(headingTrueNorth = false), ZONE, 85)
        val m = ExifInterface(magnetic)
        assertEquals("M", m.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF))
        assertNotNull(m.getAttribute(ExifInterface.TAG_MAKE))
        assertNotNull(m.getAttribute(ExifInterface.TAG_MODEL))

        // No GPS and no compass: nothing made up.
        val none = File(tmp.root, "none.jpg")
        Stamper.stamp(original, none, META.copy(lat = null, lon = null, accuracyM = null, altitudeM = null, headingDeg = null), ZONE, 85)
        val n = ExifInterface(none)
        assertEquals(null, n.latLong)
        assertEquals(null, n.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION))
        assertEquals("2026:11:05 13:35:47", n.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
    }

    @Test fun stampDrawnInBottomLeft() {
        val source = flat(800, 600)
        val original = jpeg("flat.jpg", source)
        val out = File(tmp.root, "stamped.jpg")
        Stamper.stamp(original, out, META, ZONE, 95)

        val stamped = BitmapFactory.decodeFile(out.path)
        assertEquals(800, stamped.width)
        assertEquals(600, stamped.height)
        val layout = stampLayout(800, 600, 5)
        val stampBox = Rect(layout.x, layout.firstBaselineY - layout.fontPx, layout.x + 220, 600 - layout.fontPx / 2)
        val topRight = Rect(500, 0, 800, 200)
        assertTrue(changed(source, stamped, stampBox) > 0.10, "the stamp's text is burned in, bottom-left")
        assertEquals(0.0, changed(source, stamped, topRight), "the rest of the photo is untouched")
        assertEquals(0.0, changed(source, stamped, Rect(500, 400, 800, 600)), "bottom-right too")
    }

    @Test fun qualitySettingChangesSize() {
        val original = jpeg("noise.jpg", noise(400, 300), quality = 100)
        val standard = File(tmp.root, "85.jpg").also { Stamper.stamp(original, it, META, ZONE, 85) }
        val maximum = File(tmp.root, "95.jpg").also { Stamper.stamp(original, it, META, ZONE, 95) }
        assertTrue(maximum.length() > standard.length() * 1.2, "Maximum ${maximum.length()} bytes vs Standard ${standard.length()}")
    }

    // ---- controller rulings and the brief's step list ----

    /** R58: stamping that runs out of memory tries again at 8 MP, then 4 MP, before it fails. */
    @Test fun outOfMemoryStepsDownThePixelCap() {
        val original = jpeg("big.jpg", flat(2400, 1800)) // 4.32 MP
        val tried = mutableListOf<Int>()
        val out = File(tmp.root, "stamped.jpg")
        Stamper.stamp(original, out, META, ZONE, 85) { file, cap ->
            tried += cap
            if (cap > 4_000_000) throw OutOfMemoryError("no room for $cap pixels")
            decodeUpright(file, cap)
        }
        assertEquals(listOf(16_000_000, 8_000_000, 4_000_000), tried)
        assertEquals(targetSize(2400, 1800, 4_000_000), bounds(out))
        assertEquals(2309 to 1732, bounds(out))

        val failed = File(tmp.root, "failed.jpg")
        val e = assertFailsWith<IOException> {
            Stamper.stamp(original, failed, META, ZONE, 85) { _, cap -> throw OutOfMemoryError("no room for $cap pixels") }
        }
        assertEquals("Not enough memory to stamp this photo", e.message)
        assertFalse(failed.exists(), "nothing half-written is left")
        assertEquals(emptyList(), tmp.root.listFiles()!!.filter { it.name.endsWith(".tmp") }.map { it.name })
    }

    @Test fun exifRotationIsAppliedBeforeTheStamp() {
        // A landscape sensor frame held upright: the camera wrote "rotate 90" instead of turning the pixels.
        val frame = flat(400, 300).also { b -> Canvas(b).drawRect(0f, 0f, 100f, 75f, Paint().apply { color = Color.RED }) }
        val original = jpeg("rotated.jpg", frame) { it.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()) }
        val out = File(tmp.root, "stamped.jpg")
        Stamper.stamp(original, out, META, ZONE, 95)

        val upright = BitmapFactory.decodeFile(out.path)
        assertEquals(300 to 400, upright.width to upright.height, "portrait, as it was held")
        assertTrue(isRed(upright.getPixel(262, 37)), "the sensor's top-left is the photo's top-right once turned")
        assertFalse(isRed(upright.getPixel(37, 37)))
        val layout = stampLayout(300, 400, 5)
        val box = Rect(layout.x, layout.firstBaselineY - layout.fontPx, layout.x + 100, 400 - layout.fontPx / 2)
        assertTrue(brightPixels(upright, box) > 0.05, "the stamp sits bottom-left of the upright photo")
        assertEquals(0.0, brightPixels(upright, Rect(150, 300, 300, 400)), "not along a turned edge")
    }

    /** R58: a file no decoder can read fails for good (it stays stuck), unlike running out of memory. */
    @Test fun unreadableOriginalIsAFailureOfItsOwn() {
        val original = File(tmp.root, "original.jpg").apply { writeText("not a photo") }
        val out = File(tmp.root, "stamped.jpg")
        assertFailsWith<UnreadablePhotoException> { Stamper.stamp(original, out, META, ZONE, 85) }
        assertFalse(out.exists())
    }

    // ---- helpers ----

    private fun flat(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(BLUE) }

    private fun noise(w: Int, h: Int): Bitmap {
        val random = Random(7)
        val pixels = IntArray(w * h) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun jpeg(name: String, bitmap: Bitmap, quality: Int = 95, exif: (ExifInterface) -> Unit = {}): File {
        val file = File(tmp.root, name)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        ExifInterface(file).apply(exif).saveAttributes()
        return file
    }

    private fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    /** The share of [box]'s pixels that differ clearly (beyond JPEG noise) between [a] and [b]. */
    private fun changed(a: Bitmap, b: Bitmap, box: Rect): Double {
        var n = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            val d = abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            if (d > 60) n++
        }
        return n.toDouble() / (box.width() * box.height())
    }

    private fun brightPixels(b: Bitmap, box: Rect): Double {
        var n = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            val p = b.getPixel(x, y)
            if (Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) > 200) n++
        }
        return n.toDouble() / (box.width() * box.height())
    }

    private fun isRed(p: Int) = Color.red(p) > 180 && Color.green(p) < 80 && Color.blue(p) < 80
}
