package com.mundoinformaticacanaria.gymup.feature.exercises

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoundedImageDecodeTest {
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun largeCompressedImageIsDecodedAsSmallThumbnail() {
        // A tiny PNG file may expand to millions of pixels when decoded.
        val result = decodeBoundedImageBytes(png(2048, 1024))
        assertNotNull(result)
        assertTrue(result!!.width <= 512)
        assertTrue(result.height <= 512)
        assertTrue(result.width > 0 && result.height > 0)
    }

    @Test
    fun smallImageKeepsItsOriginalDimensions() {
        val result = decodeBoundedImageBytes(png(64, 32))
        assertNotNull(result)
        assertEquals(64, result!!.width)
        assertEquals(32, result.height)
    }

    @Test
    fun malformedImageIsNotRendered() {
        assertNull(decodeBoundedImageBytes(byteArrayOf(1, 2, 3, 4)))
    }
}
