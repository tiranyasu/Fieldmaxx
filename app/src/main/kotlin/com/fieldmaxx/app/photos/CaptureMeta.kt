package com.fieldmaxx.app.photos

import androidx.exifinterface.media.ExifInterface
import com.fieldmaxx.app.platform.Fix
import com.fieldmaxx.app.platform.Heading
import com.fieldmaxx.core.stamp.StampMeta
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** [CaptureMeta.source] of a photo taken with the app's camera. */
const val SOURCE_CAMERA = "camera"

/** [CaptureMeta.source] of a photo picked from the phone's gallery: its stamp's time line ends "(from gallery)". */
const val SOURCE_GALLERY = "gallery"

/** A fix older than this at the shutter is stamped "(GPS stale)" (spec §6.6). */
const val GPS_STALE_MS = 10_000L

/**
 * What the phone records about a photo as it is saved (spec §6.6 step 1), and what Task 28 makes its stamp and EXIF from.
 * [takenAt] is epoch ms: the shutter press, or a gallery photo's own EXIF time when it has one. [gpsStale]: the fix was
 * older than [GPS_STALE_MS] at the shutter. [headingTrueNorth] (R51): [headingDeg] is from true north, so EXIF gets
 * GPSImgDirectionRef "T"; false means magnetic ("M").
 */
@Serializable
data class CaptureMeta(
    val jobName: String,
    val pointLabel: String,
    val source: String, // camera|gallery
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val headingDeg: Double?,
    val takenAt: Long,
    val gpsStale: Boolean,
    val headingTrueNorth: Boolean = false,
)

/** The stamp's input (Task 3) for this photo, its time shown in [zone]. */
fun CaptureMeta.stampMeta(zone: ZoneId) = StampMeta(
    jobName = jobName,
    pointLabel = pointLabel,
    lat = lat,
    lon = lon,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    headingDeg = headingDeg,
    takenAtMs = takenAt,
    zone = zone,
    gpsStale = gpsStale,
    fromGallery = source == SOURCE_GALLERY,
)

/**
 * What a shutter press at [now] records: the fix and the compass reading of that moment (spec §6.6: "the heading stamped
 * is the reading at the moment the shutter is pressed"). An unreliable reading is still recorded; the overlay asks for a
 * calibration meanwhile.
 */
fun cameraMeta(jobName: String, pointLabel: String, fix: Fix?, heading: Heading?, now: Long) = CaptureMeta(
    jobName = jobName,
    pointLabel = pointLabel,
    source = SOURCE_CAMERA,
    lat = fix?.lat,
    lon = fix?.lon,
    accuracyM = fix?.knownAccuracy(),
    altitudeM = fix?.altitudeM,
    headingDeg = heading?.deg,
    takenAt = now,
    gpsStale = fix != null && isStale(fix, now),
    headingTrueNorth = heading?.trueNorth ?: false,
)

/**
 * What a photo picked from the gallery records, from its own EXIF where it has it (spec §6.6: "Gallery picks use the
 * photo's own EXIF GPS/time if present"). Its position, altitude, accuracy and direction are taken only when
 * [locationAllowed] (R52): otherwise Android has hidden them, and [fallback], the phone's fix when the photo was picked,
 * stands in, marked "(GPS stale)" as on the camera when older than 10 s at [now]. A file whose location was hidden by
 * zeroing it counts as having none. Its time is the EXIF DateTimeOriginal (or DateTimeDigitized), at its EXIF offset or
 * else in [zone]; [now] without one. A picked photo has no compass reading of its own beyond the EXIF direction.
 */
internal fun galleryMeta(
    exif: ExifInterface?,
    jobName: String,
    pointLabel: String,
    fallback: Fix?,
    locationAllowed: Boolean,
    now: Long,
    zone: ZoneId,
): CaptureMeta {
    val takenAt = exif?.let { takenAt(it, zone) } ?: now
    val own = if (locationAllowed) exif?.let(::ownPosition) else null
    return if (own != null) {
        CaptureMeta(
            jobName, pointLabel, SOURCE_GALLERY, own.lat, own.lon, own.accuracyM, own.altitudeM, own.headingDeg, takenAt,
            gpsStale = false, headingTrueNorth = own.trueNorth,
        )
    } else {
        CaptureMeta(
            jobName, pointLabel, SOURCE_GALLERY, fallback?.lat, fallback?.lon, fallback?.knownAccuracy(), fallback?.altitudeM,
            headingDeg = null, takenAt = takenAt, gpsStale = fallback != null && isStale(fallback, now),
        )
    }
}

private fun isStale(fix: Fix, now: Long) = now - fix.at > GPS_STALE_MS

/** A fix the phone gave no accuracy for has [Fix.accuracyM] MAX_VALUE: no "±" line rather than an absurd one. */
private fun Fix.knownAccuracy(): Double? = accuracyM.takeIf { it.isFinite() && it >= 0 && it != Double.MAX_VALUE }

private class OwnPosition(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val headingDeg: Double?,
    val trueNorth: Boolean,
)

private fun ownPosition(exif: ExifInterface): OwnPosition? {
    val (lat, lon) = exif.latLong?.takeIf { it.size == 2 } ?: return null
    val valid = lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180 && !(lat == 0.0 && lon == 0.0)
    if (!valid) return null // zeroed (hidden) or damaged
    fun double(tag: String) = exif.getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() }
    return OwnPosition(
        lat = lat,
        lon = lon,
        accuracyM = double(ExifInterface.TAG_GPS_H_POSITIONING_ERROR)?.takeIf { it >= 0 },
        altitudeM = exif.getAltitude(Double.NaN).takeIf { it.isFinite() },
        headingDeg = double(ExifInterface.TAG_GPS_IMG_DIRECTION),
        trueNorth = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION_REF)?.trim() == "T",
    )
}

private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

/** When the photo was taken; EXIF keeps local time, with its UTC offset in a tag of its own when the camera wrote one. */
private fun takenAt(exif: ExifInterface, zone: ZoneId): Long? {
    val tags = listOf(
        ExifInterface.TAG_DATETIME_ORIGINAL to ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED to ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    )
    for ((timeTag, offsetTag) in tags) {
        val local = exif.getAttribute(timeTag)?.trim()?.let { text ->
            try {
                LocalDateTime.parse(text, EXIF_TIME)
            } catch (e: DateTimeParseException) {
                null // blank ("    :  :     :  :  ") or damaged
            }
        } ?: continue
        val offset = exif.getAttribute(offsetTag)?.trim()?.let { text ->
            try {
                ZoneOffset.of(text)
            } catch (e: DateTimeException) {
                null
            }
        }
        val instant = if (offset != null) local.toInstant(offset) else local.atZone(zone).toInstant()
        return instant.toEpochMilli()
    }
    return null
}
