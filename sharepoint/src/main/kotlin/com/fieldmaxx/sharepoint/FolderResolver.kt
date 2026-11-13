package com.fieldmaxx.sharepoint

import com.fieldmaxx.core.naming.PointKey
import com.fieldmaxx.core.naming.Where
import com.fieldmaxx.core.naming.categoryMatches
import com.fieldmaxx.core.naming.defaultFolderName
import com.fieldmaxx.core.naming.isQueryLayer
import com.fieldmaxx.core.naming.pointFolderMatches

sealed interface FolderResolution {
    data class Found(val path: String) : FolderResolution
    data class Created(val path: String) : FolderResolution
    data class Ambiguous(val candidates: List<String>) : FolderResolution
}

/** Finds (or creates) the OneDrive folder for a point. Folder listings are cached per resolver. */
class FolderResolver(private val dest: PhotoDestination) {
    private val listings = mutableMapOf<String, List<String>>()

    private fun list(path: String): List<String> = listings.getOrPut(path) { dest.listFolders(path) }

    fun resolve(p: PointKey): FolderResolution {
        val categoryName = if (isQueryLayer(p.layer)) p.layer.trim() else p.type
        val categories = list("").filter { categoryMatches(it, categoryName) }

        val inCategory = categories.flatMap { cat ->
            list(cat).filter { pointFolderMatches(it, p, Where.CATEGORY) }.map { "$cat/$it" }
        }
        if (inCategory.size == 1) return FolderResolution.Found(inCategory.single())
        if (inCategory.size > 1) return FolderResolution.Ambiguous(inCategory)

        val atTop = list("").filter { pointFolderMatches(it, p, Where.TOP) }
        if (atTop.size == 1) return FolderResolution.Found(atTop.single())
        if (atTop.size > 1) return FolderResolution.Ambiguous(atTop)

        val category = categories.firstOrNull() ?: categoryName
        val path = "$category/${defaultFolderName(p.label)}"
        dest.ensureFolder(path)
        if (categories.isEmpty()) listings.remove("")
        listings.remove(category)
        return FolderResolution.Created(path)
    }
}
