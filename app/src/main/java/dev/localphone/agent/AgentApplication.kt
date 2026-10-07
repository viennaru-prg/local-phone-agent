package dev.localphone.agent

import android.app.Application
import androidx.room.Room
import dev.localphone.agent.data.*
import dev.localphone.agent.runtime.*
import dev.localphone.core.PlaceResolver
import dev.localphone.core.PolicyGate
import java.io.File

class AgentApplication : Application() {
    private val cipher by lazy { LocalCipher() }
    val settings by lazy { SecureSettings(this, cipher) }
    val database by lazy { Room.databaseBuilder(this, PlacesDatabase::class.java, "user_places.db").build() }
    val places by lazy { RoomUserPlacesRepository(database.places(), cipher) }
    val resolver by lazy { PlaceResolver(places, NaverPlaceSearch(settings)) }
    val policy by lazy { PolicyGate(resolver) }
    val modelFile get() = File(noBackupFilesDir, "functiongemma.litertlm")
    val planner by lazy { FunctionGemmaPlanner(modelFile, File(noBackupFilesDir, "model_cache")) }
    val speechModel by lazy { SpeechModelRepository(this) }
    val invocationArbiter = InvocationArbiter()
    val invocationDebug by lazy { InvocationDebugStore(settings) }
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
    fun createSpeechInput(): SpeechInput {
        speechFactoryOverride?.let { return it() }
        if (android.os.Build.VERSION.SDK_INT >= 31 && settings.get("verified_native_speech") == "yes" &&
            android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return OnDeviceSpeechInput(this)
        return LocalSpeechInput(speechModel, timing = speechTiming())
    }
}
