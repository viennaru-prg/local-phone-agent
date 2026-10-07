package dev.localphone.agent

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localphone.agent.data.*
import dev.localphone.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStorageTest {
    @Test fun roomSurvivesReopenWithoutPlaintextAndDeletionRemovesPlace() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test_${System.nanoTime()}.db"
        val cipher = LocalCipher()
        val place = UserPlace("home", "암호화검증", listOf("합성별칭"), Coordinates(36.123456, 128.123456), "합성비밀주소", "TEST", PlaceSource.MANUAL, 1, 1)
        var db = Room.databaseBuilder(context, PlacesDatabase::class.java, name).build()
        try {
            RoomUserPlacesRepository(db.places(), cipher).saveConfirmed(place)
            val raw = db.places().get("home")!!.encryptedPayload.toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("36.123456")); assertFalse(raw.contains("128.123456"))
            db.close()
            db = Room.databaseBuilder(context, PlacesDatabase::class.java, name).build()
            val repository = RoomUserPlacesRepository(db.places(), cipher)
            assertEquals(place, repository.get("home"))
            repository.delete("home")
            assertNull(repository.get("home"))
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun encryptedRecordsCannotBeSwappedOrTamperedWith() {
        val cipher = LocalCipher()
        val encrypted = cipher.encrypt("synthetic secret", "place:one")
        assertEquals("synthetic secret", cipher.decrypt(encrypted, "place:one"))
        try { cipher.decrypt(encrypted, "place:two"); fail("AAD must reject swapped records") } catch (_: javax.crypto.AEADBadTagException) {}
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        try { cipher.decrypt(encrypted, "place:one"); fail("GCM must reject tampered records") } catch (_: javax.crypto.AEADBadTagException) {}
    }
}
