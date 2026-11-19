package com.impecabel.tapedeck.macrobenchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The library's manifest must not set the application's networkSecurityConfig: androidx.benchmark's manifest sets one,
 * and a conflict between two libraries fails the consuming module's manifest merge (tools:replace in a library manifest
 * only overrides lower-priority manifests). Tests run with the module directory as working directory.
 */
class LibraryManifestTest {

    @Test
    fun `the library manifest leaves the network security config to the test module`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android:networkSecurityConfig"))
        assertFalse(manifest.contains("tools:replace"))
        assertTrue(manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `the shipped network security config permits cleartext everywhere`() {
        val config = File("src/main/res/xml/tapedeck_network_security_config.xml").readText()
        assertTrue(config.contains("<base-config cleartextTrafficPermitted=\"true\""))
    }
}
