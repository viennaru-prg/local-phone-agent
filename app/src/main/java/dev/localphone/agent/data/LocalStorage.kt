package dev.localphone.agent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import com.google.gson.Gson
import dev.localphone.core.UserPlace
import dev.localphone.core.UserPlacesRepository
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class LocalCipher {
    private val alias = "local_phone_agent_v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(value: String, recordId: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(recordId.toByteArray(Charsets.UTF_8))
        return cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    }
    fun decrypt(value: ByteArray, recordId: String): String {
        require(value.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        cipher.updateAAD(recordId.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(value.copyOfRange(12, value.size)).toString(Charsets.UTF_8)
    }
}

@Entity(tableName = "user_places")
data class PlaceRecord(@PrimaryKey val id: String, val encryptedPayload: ByteArray)

@Dao interface PlacesDao {
    @Query("SELECT * FROM user_places ORDER BY id") suspend fun all(): List<PlaceRecord>
    @Query("SELECT * FROM user_places WHERE id = :id") suspend fun get(id: String): PlaceRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(record: PlaceRecord)
    @Query("DELETE FROM user_places WHERE id = :id") suspend fun delete(id: String)
}

@Database(entities = [PlaceRecord::class], version = 1, exportSchema = false)
abstract class PlacesDatabase : RoomDatabase() { abstract fun places(): PlacesDao }

class RoomUserPlacesRepository(private val dao: PlacesDao, private val cipher: LocalCipher) : UserPlacesRepository {
    private val gson = Gson()
    private fun decode(record: PlaceRecord) = gson.fromJson(cipher.decrypt(record.encryptedPayload, "place:${record.id}"), UserPlace::class.java)
    override suspend fun all() = dao.all().map(::decode)
    override suspend fun get(id: String) = dao.get(id)?.let(::decode)
    override suspend fun saveConfirmed(place: UserPlace) {
        dao.save(PlaceRecord(place.id, cipher.encrypt(gson.toJson(place), "place:${place.id}")))
    }
    override suspend fun delete(id: String) = dao.delete(id)
}

class SecureSettings(context: Context, private val cipher: LocalCipher) {
    private val prefs = context.getSharedPreferences("local_settings", Context.MODE_PRIVATE)
    @Synchronized fun get(key: String): String {
        val value = prefs.getString(key, null) ?: return ""
        return cipher.decrypt(Base64.decode(value, Base64.NO_WRAP), "setting:$key")
    }
    @Synchronized fun put(key: String, value: String) {
        if (value.isBlank()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, Base64.encodeToString(cipher.encrypt(value, "setting:$key"), Base64.NO_WRAP)).apply()
    }
    /** Used only by the background diagnostic writer so a completed flush survives process death. */
    @Synchronized fun putDurable(key: String, value: String) {
        val editor = prefs.edit()
        if (value.isBlank()) editor.remove(key)
        else editor.putString(key, Base64.encodeToString(cipher.encrypt(value, "setting:$key"), Base64.NO_WRAP))
        check(editor.commit()) { "Encrypted setting could not be persisted" }
    }
}
