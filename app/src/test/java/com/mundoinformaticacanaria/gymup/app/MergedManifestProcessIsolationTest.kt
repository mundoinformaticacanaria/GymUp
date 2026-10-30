package com.mundoinformaticacanaria.gymup.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JVM/Robolectric guard against accidentally introducing a secondary process through
 * the DEBUG merged manifest (including dependency manifests).
 *
 * The #56 domain barrier and journal lock are process-local. This does not prove
 * runtime single-process exclusivity, release-manifest parity or resistance to
 * external processes; device/API 35-36 verification remains mandatory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MergedManifestProcessIsolationTest {
    @Suppress("DEPRECATION")
    @Test fun `merged debug manifest keeps application components in the default process`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageName = context.packageName
        val packageInfo = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )

        assertEquals(packageName, packageInfo.applicationInfo?.processName ?: packageName)

        val activities = packageInfo.activities.orEmpty()
        val providers = packageInfo.providers.orEmpty()
        assertTrue("No MainActivity found in the test merged manifest", activities.any {
            it.name == "$"+"{packageName}.MainActivity"
        })
        assertTrue("No FileProvider found in the test merged manifest", providers.any {
            it.name == "androidx.core.content.FileProvider"
        })

        val components = activities.map { "Activity "+ "$"+"{it.name}" to it.processName } +
            packageInfo.services.orEmpty().map { "Service "+ "$"+"{it.name}" to it.processName } +
            packageInfo.receivers.orEmpty().map { "Receiver "+ "$"+"{it.name}" to it.processName } +
            providers.map { "Provider "+ "$"+"{it.name}" to it.processName }
        components.forEach { (label, process) ->
            assertEquals("$"+"label must share the default process", packageName, process ?: packageName)
        }
    }
}
