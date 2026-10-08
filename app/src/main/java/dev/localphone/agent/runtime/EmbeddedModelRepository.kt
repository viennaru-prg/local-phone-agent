package dev.localphone.agent.runtime

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

class EmbeddedModelRepository(private val context: Context) {
    private data class Manifest(val schemaVersion: Int, val models: List<EmbeddedModelInfo>)
    val models: List<EmbeddedModelInfo> = context.assets.open("agent-models.json").bufferedReader().use {
        Gson().fromJson(it, Manifest::class.java).also { manifest ->
            require(manifest.schemaVersion == 1 && manifest.models.map { model -> model.id }.toSet() == setOf("functiongemma", "qwen3"))
        }.models
    }
    private val directory = File(context.noBackupFilesDir, "embedded_agent_models")
    private val verifiedInProcess = mutableSetOf<String>()
    fun info(id: LocalModelId) = models.single { it.id == id.key }
    fun extractedFile(id: LocalModelId): File = info(id).let { File(directory, it.id + "-" + it.sha256.take(16) + "." + it.asset.substringAfterLast('.')) }
    fun installed(id: LocalModelId) = runCatching { context.assets.openFd(info(id).asset).use { it.length == info(id).bytes } }.getOrDefault(false)

    /** Selective extraction: no network and no memory-sized byte array. Publish only a synced, verified file. */
    suspend fun extract(id: LocalModelId): File = withContext(Dispatchers.IO) {
        val model = info(id)
        require(installed(id)) { "APK에 ${model.name} 가중치가 없거나 크기가 다릅니다." }
        check(directory.isDirectory || directory.mkdirs()) { "모델 저장 폴더를 만들지 못했습니다." }
        val destination = extractedFile(id)
        val valid = LocalModelId.entries.map { extractedFile(it).name }.toSet()
        // The new APK can reconstruct its pinned inputs. Old revisions and interrupted copies
        // cannot serve as a rollback model in this process, and must not consume extraction space.
        directory.listFiles()?.filter { it.isFile && (it.name.endsWith(".part") || it.name !in valid) }?.forEach { it.delete() }
        if (destination.isFile && destination.length() == model.bytes &&
            (model.sha256 in verifiedInProcess || checksum(destination) == model.sha256)) {
            verifiedInProcess += model.sha256
            return@withContext destination
        }
        if (destination.exists()) require(destination.isFile && destination.delete()) { "손상된 모델 복사본을 교체하지 못했습니다." }
        require(directory.usableSpace >= model.bytes + 128L * 1024 * 1024) {
            "${model.name} 준비에 ${model.bytes / 1048576 + 128}MB 이상의 여유 공간이 필요합니다."
        }
        val temporary = File(directory, destination.name + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            context.assets.open(model.asset).use { input -> FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    copied += count; require(copied <= model.bytes) { "모델 파일 크기가 다릅니다." }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                }
                output.fd.sync()
            } }
            require(copied == model.bytes && hex(digest.digest()) == model.sha256) { "${model.name} SHA-256 검사가 실패했습니다." }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            verifiedInProcess += model.sha256
            // Only obsolete revisions are removed; both current models remain available for offline switches.
            directory.listFiles()?.filter { it.name !in valid && !it.name.endsWith(".part") }?.forEach { it.delete() }
            destination
        } finally { temporary.delete() }
    }
    private suspend fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { coroutineContext.ensureActive(); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return hex(digest.digest())
    }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
