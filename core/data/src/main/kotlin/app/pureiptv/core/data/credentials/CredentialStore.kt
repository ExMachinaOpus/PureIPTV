package app.pureiptv.core.data.credentials

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Secret part of a source. Never logged, never stored in the Room database. */
@Serializable
sealed interface SourceSecret {
    @Serializable
    data class M3u(val url: String) : SourceSecret

    @Serializable
    data class Xtream(val serverUrl: String, val username: String, val password: String) : SourceSecret
}

private val Context.credentialDataStore by preferencesDataStore(name = "credentials")

/**
 * Stores [SourceSecret]s encrypted with Tink AEAD (AES-256-GCM). The Tink keyset is itself
 * encrypted by a key in the Android Keystore, so secrets are unreadable outside this app/device.
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    suspend fun put(sourceId: Long, secret: SourceSecret) = withContext(Dispatchers.IO) {
        val plain = json.encodeToString(SourceSecret.serializer(), secret).toByteArray()
        val cipher = aead.encrypt(plain, associatedData(sourceId))
        context.credentialDataStore.edit { it[key(sourceId)] = Base64.encodeToString(cipher, Base64.NO_WRAP) }
    }

    suspend fun get(sourceId: Long): SourceSecret? = withContext(Dispatchers.IO) {
        val encoded = context.credentialDataStore.data.first()[key(sourceId)] ?: return@withContext null
        runCatching {
            val plain = aead.decrypt(Base64.decode(encoded, Base64.NO_WRAP), associatedData(sourceId))
            json.decodeFromString(SourceSecret.serializer(), String(plain))
        }.getOrNull()
    }

    /**
     * The guide (XMLTV) address a playlist advertises. Kept here, not in the database: these
     * links usually contain the provider login.
     */
    suspend fun putGuideUrl(sourceId: Long, url: String) = withContext(Dispatchers.IO) {
        val cipher = aead.encrypt(url.toByteArray(), guideData(sourceId))
        context.credentialDataStore.edit { it[guideKey(sourceId)] = Base64.encodeToString(cipher, Base64.NO_WRAP) }
    }

    suspend fun guideUrl(sourceId: Long): String? = withContext(Dispatchers.IO) {
        val encoded = context.credentialDataStore.data.first()[guideKey(sourceId)] ?: return@withContext null
        runCatching { String(aead.decrypt(Base64.decode(encoded, Base64.NO_WRAP), guideData(sourceId))) }.getOrNull()
    }

    suspend fun removeGuideUrl(sourceId: Long) {
        context.credentialDataStore.edit { it.remove(guideKey(sourceId)) }
    }

    suspend fun remove(sourceId: Long) {
        context.credentialDataStore.edit {
            it.remove(key(sourceId))
            it.remove(guideKey(sourceId))
        }
    }

    private fun key(sourceId: Long) = stringPreferencesKey("source_$sourceId")
    private fun associatedData(sourceId: Long) = "source_$sourceId".toByteArray()
    private fun guideKey(sourceId: Long) = stringPreferencesKey("guide_$sourceId")
    private fun guideData(sourceId: Long) = "guide_$sourceId".toByteArray()

    private companion object {
        const val KEYSET_NAME = "credential_keyset"
        const val KEYSET_PREFS = "credential_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://pureiptv_credentials"
    }
}
