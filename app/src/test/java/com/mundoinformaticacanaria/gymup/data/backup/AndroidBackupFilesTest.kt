package com.mundoinformaticacanaria.gymup.data.backup

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBackupFilesTest {
    @Test fun `accepts document exactly at the configured limit`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, readBoundedBackupDocument(ByteArrayInputStream(bytes), maxBytes = 4))
    }

    @Test fun `rejects oversized document without reading the entire provider stream`() {
        val stream = ByteArrayInputStream(ByteArray(20) { it.toByte() })
        val error = runCatching { readBoundedBackupDocument(stream, maxBytes = 4) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("tamaño máximo"))
        assertEquals(15, stream.available())
    }

    @Test fun `accepts short reads and rejects the first byte over the limit`() {
        val stream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, minOf(len, 1))
        }
        val error = runCatching { readBoundedBackupDocument(stream, maxBytes = 4) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, stream.available())
    }
}
