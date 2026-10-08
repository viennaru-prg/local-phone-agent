package dev.localphone.agent

import android.app.Application
import androidx.room.Room
import dev.localphone.agent.data.*
import dev.localphone.agent.runtime.*
import dev.localphone.core.PlaceResolver
import dev.localphone.core.PolicyGate
import java.io.File

class AgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A successfully installed update no longer needs its large installer cache; user data is retained.
        File(cacheDir, "updates").listFiles()?.filter { file ->
            Regex("[0-9]+-[a-f0-9]{12}\\.apk(?:\\.part)?").matches(file.name) &&
                (file.name.substringBefore('-').toLongOrNull() ?: Long.MAX_VALUE) <= BuildConfig.VERSION_CODE
        }?.forEach { it.delete() }
    }
    private val cipher by lazy { LocalCipher() }
    val settings by lazy { SecureSettings(this, cipher) }
    val database by lazy { Room.databaseBuilder(this, PlacesDatabase::class.java, "user_places.db").build() }
    val places by lazy { RoomUserPlacesRepository(database.places(), cipher) }
    val resolver by lazy { PlaceResolver(places, NaverPlaceSearch(settings)) }
    val policy by lazy { PolicyGate(resolver) }
    val embeddedModels by lazy { EmbeddedModelRepository(this) }
    val modelFile get() = embeddedModels.extractedFile(LocalModelId.FUNCTIONGEMMA)
    val planner by lazy { LocalAgentModels(embeddedModels, settings, File(noBackupFilesDir, "model_cache")) {
        invocationArbiter.isActive || uiAutomation.active
    } }
    // Regression tests can explicitly exercise RULE_BASED; production always uses the selected embedded model.
    internal var agentModelEnabledOverride: Boolean? = null
    val useAgentModel get() = agentModelEnabledOverride ?: true
    val speechModel by lazy { SpeechModelRepository(this) }
    val invocationArbiter = InvocationArbiter()
    val invocationDebug by lazy { InvocationDebugStore(settings) }
    val speechDiagnostics = SpeechDiagnostics()
    val uiAutomation by lazy { AccessibilityRuntime(this, settings) }
    // In-process instrumentation seam. No exported intent or remote input can replace speech.
    internal var speechFactoryOverride: (() -> SpeechInput)? = null
    internal var focusFactoryOverride: (() -> CaptureFocus)? = null
    internal var updateClientFactoryOverride: ((android.content.Context) -> dev.localphone.agent.updates.AppUpdateClient)? = null
    fun speechTiming() = runCatching { SpeechTiming(
        settings.get("speech_start_ms").toIntOrNull() ?: 4500,
        settings.get("speech_silence_ms").toIntOrNull() ?: 900,
        settings.get("speech_max_ms").toIntOrNull() ?: 18000,
    ) }.getOrDefault(SpeechTiming())
    fun createSpeechInput(context: dev.localphone.core.SpeechContext = dev.localphone.core.SpeechContext()): SpeechInput {
        speechFactoryOverride?.let { return it() }
        if (settings.get("speech_engine") == "bundled") return LocalSpeechInput(speechModel, timing = speechTiming())
        if (android.os.Build.VERSION.SDK_INT >= 31) return OnDeviceSpeechInput(this, context.biasStrings(), speechTiming().maxUtteranceMs + 7000L)
        return object : SpeechInput {
            override fun start(listener: SpeechInput.Listener) = listener.onFailure(SpeechFailure(SpeechError.STT_FAILED,
                "Android 온디바이스 음성 인식은 Android 12 이상에서 지원됩니다. 음성 설정에서 기존 포함 모델을 직접 선택할 수 있습니다."))
            override fun cancel() = Unit
        }
    }
    fun createCaptureFocus(): CaptureFocus = focusFactoryOverride?.invoke() ?: CaptureAudioFocus(this,
        if (settings.get("stt_focus_mode") == "pause") CaptureFocusMode.TEMPORARY_PAUSE else CaptureFocusMode.DUCK)
}
