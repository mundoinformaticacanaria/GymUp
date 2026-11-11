package com.mundoinformaticacanaria.gymup.feature.exercises

import com.mundoinformaticacanaria.gymup.data.images.ExerciseImageManager
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Arrays
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedImageInputTest {
    @Test
    fun smallImageIsReadExactly() {
        val data = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(data, readSelectedImageBytes(ByteArrayInputStream(data)))
    }

    @Test
    fun exactlyTenMiBIsAccepted() {
        val limit = ExerciseImageManager.MAX_IMAGE_BYTES
        val stream = SyntheticImageStream(limit)
        val bytes = readSelectedImageBytes(stream)
        assertEquals(limit, bytes.size)
        assertEquals(limit, stream.bytesRead)
        assertEquals(7.toByte(), bytes.first())
        assertEquals(7.toByte(), bytes.last())
    }

    @Test
    fun oversizedProviderStopsAfterOneBytePastLimit() {
        val limit = ExerciseImageManager.MAX_IMAGE_BYTES
        val stream = SyntheticImageStream(limit + 64 * 1024)
        val failure = runCatching { readSelectedImageBytes(stream) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("10 MiB") == true)
        assertEquals(limit + 1, stream.bytesRead)
    }

    private class SyntheticImageStream(private val length: Int) : InputStream() {
        var bytesRead: Int = 0
            private set

        override fun read(): Int =
            if (bytesRead < length) {
                bytesRead++
                7
            } else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= length) return -1
            val count = minOf(len, length - bytesRead)
            Arrays.fill(b, off, off + count, 7.toByte())
            bytesRead += count
            return count
        }
    }
}
