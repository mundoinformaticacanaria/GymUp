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

    @Test fun `missing root without USER references is not an integrity defect`() = runBlocking {
        val inspector = UserImageReferenceIntegrityInspector(isolatedContext, database.exerciseDao())
        assertTrue(inspector.missingOrUnsafeStorageKeys().isEmpty())
        assertFalse(isolatedRoot.resolve("exercise-images").exists())
    }
}
