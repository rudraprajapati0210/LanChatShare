package dev.lanchatshare.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun firstLaunchRestartAndRename() = runBlocking {
        val file = dir.root.resolve("profile.preferences_pb")
        suspend fun <T> launchApp(block: suspend (ProfileRepository) -> T): T {
            val job = SupervisorJob()
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            return try { block(ProfileRepository(store)) } finally { job.cancelAndJoin() }
        }
        val initial = launchApp {
            assertNull(it.profile.first())
            it.save("  Rudra  ")
            it.profile.first()!!
        }
        launchApp {
            assertEquals(initial.copy(name = "Rudra"), it.profile.first())
            it.save("রুদ্র", 2)
        }
        launchApp { assertEquals(initial.copy(name = "রুদ্র", colour = 2), it.profile.first()) }
    }

    @Test fun checksVisibleCharacters() {
        assertNull(validatedName("   "))
        val family = "👩‍👩‍👦"
        assertEquals(family.repeat(24), validatedName(family.repeat(24)))
        assertNull(validatedName(family.repeat(25)))
    }
}
