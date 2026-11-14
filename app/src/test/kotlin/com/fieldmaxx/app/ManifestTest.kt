package com.fieldmaxx.app

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private const val FINE = "android.permission.ACCESS_FINE_LOCATION"
private const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
private const val BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION"
private const val FGS_LOCATION = "android.permission.FOREGROUND_SERVICE_LOCATION"

/** Location is foreground only: checked on the merged manifest, so a library can't bring background location in. */
class ManifestTest {
    @Test fun noBackgroundLocation() {
        val manifest = mergedManifest()
        val permissions = (manifest.elements("uses-permission") + manifest.elements("uses-permission-sdk-23"))
            .map { it.getAttributeNS(ANDROID_NS, "name") }

        assertTrue(FINE in permissions && COARSE in permissions, "fine and coarse location expected in $permissions")
        assertFalse(BACKGROUND in permissions, "background location must never be requested")
        assertFalse(FGS_LOCATION in permissions, "no location foreground service")
        val locationServices = manifest.elements("service")
            .filter { "location" in it.getAttributeNS(ANDROID_NS, "foregroundServiceType").split('|') }
            .map { it.getAttributeNS(ANDROID_NS, "name") }
        assertEquals(emptyList(), locationServices, "no service may run as a location foreground service")
    }

    /** AGP writes the merged manifest's path into test_config.properties when unit tests include Android resources. */
    private fun mergedManifest(): Document {
        val config = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!.use(config::load)
        val path = config.getProperty("android_merged_manifest")!!
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(path))
    }

    private fun Document.elements(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
}
