package com.mundoinformaticacanaria.gymup.data.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression for cleanup of image directories after rollback/commit (#56). */
class SafeImageDirectoryCleanupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `flat files are removed without recursive traversal`() {
        val root = temp.newFolder("images")
        File(root, "image-a").writeBytes(byteArrayOf(1, 2))
        File(root, "image-b").writeBytes(byteArrayOf(3))

        assertTrue(deleteFlatImageDirectoryNoFollow(root))
        assertFalse(root.exists())
    }

    @Test
    fun `symlink entry is unlinked without deleting external directory contents`() {
        val external = temp.newFolder("external")
        val payload = File(external, "keep.jpg")
        val bytes = byteArrayOf(9, 8, 7)
        payload.writeBytes(bytes)
        val root = temp.newFolder("images")
        Files.createSymbolicLink(File(root, "linked").toPath(), external.toPath())

        assertTrue(deleteFlatImageDirectoryNoFollow(root))
        assertFalse(root.exists())
        assertArrayEquals(bytes, payload.readBytes())
    }

    @Test
    fun `linked root is rejected without traversing its destination`() {
        val external = temp.newFolder("external")
        val payload = File(external, "keep.jpg")
        val bytes = byteArrayOf(5, 4, 3)
        payload.writeBytes(bytes)
        val root = File(temp.root, "images-link")
        Files.createSymbolicLink(root.toPath(), external.toPath())

        assertFalse(deleteFlatImageDirectoryNoFollow(root))
        assertTrue(Files.isSymbolicLink(root.toPath()))
        assertArrayEquals(bytes, payload.readBytes())
    }

    @Test
    fun `unexpected nested directory fails closed and preserves contents`() {
        val root = temp.newFolder("images")
        val nested = File(root, "nested")
        assertTrue(nested.mkdir())
        val payload = File(nested, "keep.jpg")
        payload.writeBytes(byteArrayOf(1))

        assertFalse(deleteFlatImageDirectoryNoFollow(root))
        assertTrue(payload.exists())
    }
}
