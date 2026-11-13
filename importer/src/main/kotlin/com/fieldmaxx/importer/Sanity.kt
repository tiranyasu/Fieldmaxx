package com.fieldmaxx.importer

import com.fieldmaxx.core.geo.LatLon
import com.fieldmaxx.core.geo.distanceMeters
import com.fieldmaxx.core.model.DraftPoint
import com.fieldmaxx.core.model.ImportFlag

private const val FAR_FROM_JOB_M = 50_000.0
private const val DUPLICATE_MIN_APART_M = 1.0

private fun median(v: List<Double>): Double {
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

/** Adds sign_mismatch, far_from_job and duplicate_label flags (keeping existing ones); never removes points. */
fun addSanityFlags(points: List<DraftPoint>): List<DraftPoint> {
    val located = points.filter { it.lat != null && it.lon != null }
    val extra = points.map { mutableSetOf<ImportFlag>() }
    if (located.isNotEmpty()) {
        val westCount = located.count { it.lon!! < 0 }
        val majorityWest = westCount * 2 >= located.size
        val center = LatLon(median(located.map { it.lat!! }), median(located.map { it.lon!! }))
        for ((i, p) in points.withIndex()) {
            val lat = p.lat ?: continue
            val lon = p.lon ?: continue
            if ((lon < 0) != majorityWest) extra[i].add(ImportFlag.sign_mismatch)
            if (distanceMeters(center, LatLon(lat, lon)) > FAR_FROM_JOB_M) extra[i].add(ImportFlag.far_from_job)
        }
    }
    for (idxs in points.indices.groupBy { points[it].label }.values) {
        if (idxs.size < 2) continue
        val apart = idxs.any { a ->
            idxs.any { b ->
                val pa = points[a]
                val pb = points[b]
                pa.lat != null && pa.lon != null && pb.lat != null && pb.lon != null &&
                    distanceMeters(LatLon(pa.lat!!, pa.lon!!), LatLon(pb.lat!!, pb.lon!!)) > DUPLICATE_MIN_APART_M
            }
        }
        if (apart) idxs.forEach { extra[it].add(ImportFlag.duplicate_label) }
    }
    return points.mapIndexed { i, p -> if (extra[i].isEmpty()) p else p.copy(flags = (p.flags + extra[i]).distinct()) }
}
