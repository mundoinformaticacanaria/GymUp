package com.mundoinformaticacanaria.gymup.data.seed

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mundoinformaticacanaria.gymup.data.local.GymUpDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseSeederTest {
    private lateinit var database: GymUpDatabase
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, GymUpDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { database.close() }

    @Test fun seedIsCompleteAndIdempotent() = runBlocking {
        val seeder = DatabaseSeeder(context, database)
        seeder.seedIfNeeded(); seeder.seedIfNeeded()
        assertEquals(61, database.exerciseDao().count())
        assertEquals(7, database.masterDataDao().getSessionTypes().size)
        assertEquals(10, database.masterDataDao().getMuscleGroups().size)
        assertEquals(10, database.masterDataDao().getEquipment().size)
        val lateralRaise = database.exerciseDao().findByNormalizedName("machine lateral raise")
        assertNotNull(lateralRaise)
        assertEquals("Elevación lateral en máquina", lateralRaise?.nameEs)
    }

    @Test fun normalizedExerciseNamesAreUniqueAtDatabaseLevel() = runBlocking {
        DatabaseSeeder(context, database).seedIfNeeded()
        val original = database.exerciseDao().getAll().first()
        try {
            database.exerciseDao().insert(original.copy(id = "different-id"))
            fail("Expected unique normalized-name constraint to reject duplicate")
        } catch (_: SQLiteConstraintException) {
            // Expected.
        }
    }
}
