package dev.localphone.agent.runtime

import android.os.Build
import com.google.gson.Gson
import dev.localphone.agent.BuildConfig
import java.io.File
import java.security.MessageDigest

/** Private constant phone-catalog prefill only. No reply, utterance or observed screen cache. */
internal class QwenInputCache(private val directory: File, private val info: EmbeddedModelInfo) {
    private val state = File(directory, "phone-input.state")
    private val metadata = File(directory, "phone-input.json")
    val staging = File(directory, "phone-input.part")
    private val limit = 128L * 1024 * 1024
    private data class Metadata(val key: String, val bytes: Long, val sha256: String)
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(256 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun key(prompt: ByteArray) = hash(("phone-input-v1|${info.sha256}|${BuildConfig.VERSION_CODE}|${Build.SUPPORTED_ABIS.first()}|bd4eeaa047006cb1fe71999fbd11134b5836e167|4096|").toByteArray() + prompt)
    fun restore(handle: Long, prompt: ByteArray): Boolean = runCatching {
        if (!state.isFile || !metadata.isFile || metadata.length() !in 1..8192) return false
        val saved = Gson().fromJson(metadata.readText(), Metadata::class.java)
        if (saved.key != key(prompt) || saved.bytes !in 1..limit || state.length() != saved.bytes || hash(state) != saved.sha256) return false
        LlamaNative.restoreInput(handle, state.absolutePath.toByteArray(), prompt)
    }.getOrDefault(false)
    fun begin(): ByteArray { directory.mkdirs(); staging.delete(); return staging.absolutePath.toByteArray() }
    // Called only after a real successful startup health generation, never after a user plan.
    fun commit(prompt: ByteArray) {
        try {
            if (!staging.isFile || staging.length() !in 1..limit) return
            val value = Metadata(key(prompt), staging.length(), hash(staging))
            if (!staging.renameTo(state)) return
            val temporary = File(directory, "phone-input.json.part")
            temporary.writeText(Gson().toJson(value)); temporary.renameTo(metadata)
        } finally { staging.delete() }
    }
}
