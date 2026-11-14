package com.fieldmaxx.app.photos

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)
private val EXIF_OFFSET = DateTimeFormatter.ofPattern("xxx", Locale.US)

/**
 * The stamped copy's EXIF (spec §6.6): GPS position, altitude and accuracy, which way the camera faced (GPSImgDirectionRef
 * "T" from true north, else "M", R51), when it was taken, and the camera's make and model. Its pixels are upright by
 * now, so the orientation is "normal". EXIF keeps local time: it is the stamp's time in [zone], its UTC offset beside it.
 * What the photo doesn't have (no fix, no compass reading) is left out, never made up.
 */
object ExifWriter {
    fun write(file: File, meta: CaptureMeta, make: String?, model: String?, zone: ZoneId = ZoneId.systemDefault()) {
        val exif = ExifInterface(file)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        val lat = meta.lat
        val lon = meta.lon
        if (lat != null && lon != null && abs(lat) <= 90 && abs(lon) <= 180) {
            exif.setLatLong(lat, lon)
            meta.altitudeM?.takeIf { it.isFinite() }?.let(exif::setAltitude)
            meta.accuracyM?.takeIf { it.isFinite() && it >= 0 }?.let { exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, rational(it)) }
        }
        meta.headingDeg?.takeIf { it.isFinite() }?.let { deg ->
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION, rational(((deg % 360) + 360) % 360))
            exif.setAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF, if (meta.headingTrueNorth) "T" else "M")
        }
        val taken = Instant.ofEpochMilli(meta.takenAt).atZone(zone)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_TIME.format(taken))
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, EXIF_OFFSET.format(taken))
        make?.let { exif.setAttribute(ExifInterface.TAG_MAKE, it) }
        model?.let { exif.setAttribute(ExifInterface.TAG_MODEL, it) }
        exif.saveAttributes()
    }

    /** EXIF keeps distances and angles as unsigned rationals: thousandths here. */
    private fun rational(value: Double) = "${Math.round(value * 1000)}/1000"
}
