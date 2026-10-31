package com.mundoinformaticacanaria.gymup.data.images

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.ExerciseImageEntity
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import com.mundoinformaticacanaria.gymup.data.seed.DatabaseSeeder
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserImageReferenceIntegrityInspectorTest {
    private lateinit var database: GymUpDatabase
    private lateinit var isolatedContext: Context
    private lateinit var isolatedRoot: java.io.File
    private var symlink: java.io.File? = null

    @Before fun setUp() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        isolatedRoot = base.cacheDir.resolve("gymup-image-inspection-${UUID.randomUUID()}")
        check(isolatedRoot.mkdirs())
        isolatedContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): java.io.File = isolatedRoot
        }
        database = Room.inMemoryDatabaseBuilder(base, GymUpDatabase::class.java)
            .allowMainThreadQueries().build()
        DatabaseSeeder(base, database).seedIfNeeded()
    }

    @After fun tearDown() {
        database.close()
        symlink?.let { Files.deleteIfExists(it.toPath()) }
        isolatedRoot.deleteRecursively()
    }

    private suspend fun insertUserImage(key: String, position: Int) {
        database.exerciseDao().insertImage(
            ExerciseImageEntity(
                id = UUID.randomUUID().toString(),
                exerciseId = database.exerciseDao().getAll().first().id,
                position = position,
                sourceType = "USER",
                storageKey = key,
                originalSourceUrl = null,
                author = null,
                license = null,
            ),
        )
    }

    @Test fun `missing root is reported when Room still references an image`() = runBlocking {
        val key = "user:${UUID.randomUUID()}"
        insertUserImage(key, 1001)

        val inspector = UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
        assertEquals(setOf(key), inspector.missingOrUnsafeStorageKeys())
        assertFalse(isolatedRoot.resolve("exercise-images").exists())
        // Diagnostic only: the Room reference remains intact, and no repair is attempted.
        assertEquals(1, database.exerciseDao().countUserImageStorageKeyReferences(key))
    }

    @Test fun `missing empty and symlinked references are reported without changing bytes`() = runBlocking {
        val images = isolatedRoot.resolve("exercise-images").apply { check(mkdirs()) }
        val keptName = UUID.randomUUID().toString().uppercase()
        val emptyName = UUID.randomUUID().toString()
        val missingName = UUID.randomUUID().toString()
        val linkedName = UUID.randomUUID().toString()
        val kept = images.resolve(keptName).apply { writeText("valid image bytes") }
        images.resolve(emptyName).createNewFile()
        val external = isolatedRoot.resolve("external").apply { writeText("external bytes") }
        symlink = images.resolve(linkedName)
        Files.createSymbolicLink(symlink!!.toPath(), external.toPath())

        val keptKey = "user:$keptName"
        val emptyKey = "user:$emptyName"
        val missingKey = "user:$missingName"
        val linkedKey = "user:$linkedName"
        listOf(keptKey, emptyKey, missingKey, linkedKey).forEachIndexed { index, key ->
            insertUserImage(key, 1001 + index)
        }

        val inspector = UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
        assertEquals(setOf(emptyKey, missingKey, linkedKey), inspector.missingOrUnsafeStorageKeys())
        assertEquals("valid image bytes", kept.readText())
        assertEquals("external bytes", external.readText())
        assertTrue(Files.isSymbolicLink(symlink!!.toPath()))
        assertEquals(4, database.exerciseDao().getAllUserImageStorageKeys().size)
    }

    @Test fun `shared missing USER image is reported once without dropping either Room reference`() = runBlocking {
        val key = "user:${UUID.randomUUID()}"
        insertUserImage(key, 1001)
        insertUserImage(key, 1002)

        val inspector = UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
        assertEquals(setOf(key), inspector.missingOrUnsafeStorageKeys())
        assertEquals(2, database.exerciseDao().countUserImageStorageKeyReferences(key))
        assertFalse(isolatedRoot.resolve("exercise-images").exists())
    }

    @Test fun `image size boundary is inclusive at ten MiB and rejects one extra byte`() = runBlocking {
        val images = isolatedRoot.resolve("exercise-images").apply { check(mkdirs()) }
        val acceptedName = UUID.randomUUID().toString()
        val rejectedName = UUID.randomUUID().toString()
        val acceptedFile = images.resolve(acceptedName)
        val rejectedFile = images.resolve(rejectedName)
        java.io.RandomAccessFile(acceptedFile, "rw").use { it.setLength(10L * 1024 * 1024) }
        java.io.RandomAccessFile(rejectedFile, "rw").use { it.setLength(10L * 1024 * 1024 + 1) }
        val acceptedKey = "user:$acceptedName"
        val rejectedKey = "user:$rejectedName"
        insertUserImage(acceptedKey, 1001)
        insertUserImage(rejectedKey, 1002)

        val inspector = UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
        assertEquals(setOf(rejectedKey), inspector.missingOrUnsafeStorageKeys())
        assertEquals(10L * 1024 * 1024, acceptedFile.length())
        assertEquals(10L * 1024 * 1024 + 1, rejectedFile.length())
        assertEquals(2, database.exerciseDao().getAllUserImageStorageKeys().size)
    }

    @Test fun `missing root without USER references is not an integrity defect`() = runBlocking {
        val inspector = UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
        assertTrue(inspector.missingOrUnsafeStorageKeys().isEmpty())
        assertFalse(isolatedRoot.resolve("exercise-images").exists())
    }

    @Test fun `symlinked private files directory is rejected without reading external images`() = runBlocking {
        val key = "user:${UUID.randomUUID()}"
        insertUserImage(key, 1001)
        val outside = isolatedRoot.resolve("external-parent").apply { check(mkdirs()) }
        val externalImages = outside.resolve("exercise-images").apply { check(mkdirs()) }
        val externalFile = externalImages.resolve(key.removePrefix("user:"))
            .apply { writeText("external bytes must remain private") }
        val linkedParent = isolatedRoot.resolve("linked-files")
        Files.createSymbolicLink(linkedParent.toPath(), outside.toPath())
        try {
            val base = ApplicationProvider.getApplicationContext<Context>()
            val linkedContext = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): java.io.File = linkedParent
            }
            val failure = runCatching {
                UserImageReferenceIntegrityInspector(linkedContext, database.exerciseDao())
                    .missingOrUnsafeStorageKeys()
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertEquals("external bytes must remain private", externalFile.readText())
            assertEquals(1, database.exerciseDao().countUserImageStorageKeyReferences(key))
        } finally {
            Files.deleteIfExists(linkedParent.toPath())
        }
    }

    @Test fun `oversized and malformed references are reported while safe files remain untouched`() = runBlocking {
        val images = isolatedRoot.resolve("exercise-images").apply { check(mkdirs()) }
        val goodName = UUID.randomUUID().toString().uppercase()
        val oversizedName = UUID.randomUUID().toString()
        val goodFile = images.resolve(goodName).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val oversizedFile = images.resolve(oversizedName)
        java.io.RandomAccessFile(oversizedFile, "rw").use {
            it.setLength(10L * 1024 * 1024 + 1)
        }
        val goodKey = "user:$goodName"
        val oversizedKey = "user:$oversizedName"
        val malformedKey = "user:../invalid"
        listOf(goodKey, oversizedKey, malformedKey).forEachIndexed { index, key ->
            insertUserImage(key, 1001 + index)
        }

        val inspector = UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
        assertEquals(
            setOf(oversizedKey, malformedKey),
            inspector.missingOrUnsafeStorageKeys(),
        )
        assertEquals(listOf<Byte>(1, 2, 3), goodFile.readBytes().toList())
        assertEquals(10L * 1024 * 1024 + 1, oversizedFile.length())
        assertEquals(3, database.exerciseDao().getAllUserImageStorageKeys().size)
    }

    @Test fun `symlinked image root is rejected without reading its target`() = runBlocking {
        val key = "user:${UUID.randomUUID()}"
        insertUserImage(key, 1001)
        val outside = isolatedRoot.resolve("outside-images").apply { check(mkdirs()) }
        val externalFile = outside.resolve(key.removePrefix("user:"))
            .apply { writeText("external bytes remain unchanged") }
        symlink = isolatedRoot.resolve("exercise-images")
        Files.createSymbolicLink(symlink!!.toPath(), outside.toPath())

        val failure = runCatching {
            UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
                .missingOrUnsafeStorageKeys()
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertEquals("external bytes remain unchanged", externalFile.readText())
        assertTrue(Files.isSymbolicLink(symlink!!.toPath()))
        assertEquals(1, database.exerciseDao().countUserImageStorageKeyReferences(key))
    }

    @Test fun `regular file in place of image root is rejected without modifying Room`() = runBlocking {
        val key = "user:${UUID.randomUUID()}"
        insertUserImage(key, 1001)
        val invalidRoot = isolatedRoot.resolve("exercise-images")
            .apply { writeText("not an image directory") }

        val failure = runCatching {
            UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
                .missingOrUnsafeStorageKeys()
        }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertEquals("not an image directory", invalidRoot.readText())
        assertEquals(1, database.exerciseDao().countUserImageStorageKeyReferences(key))
    }
}
