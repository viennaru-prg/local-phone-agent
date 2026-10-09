package dev.localphone.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.localphone.agent.llm.LlamaModel
import dev.localphone.core.AppProfiles
import dev.localphone.core.PlaceBook
import dev.localphone.core.RecipeBook
import java.io.File

class AgentApp : Application() {
    val updates by lazy { dev.localphone.agent.updates.AppUpdates(this) }
    lateinit var prefs: Prefs; private set
    lateinit var llm: LlamaModel; private set
    lateinit var recipes: RecipeBook; private set
    lateinit var places: PlaceBook; private set
    val traces get() = File(getExternalFilesDir(null), "traces").apply { mkdirs() }
    val modelsDir get() = File(getExternalFilesDir(null), "models").apply { mkdirs() }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        llm = LlamaModel(this)
        val recipeFile = File(filesDir, "recipes.json")
        recipes = RecipeBook({ recipeFile.takeIf { it.exists() }?.readText() }, { recipeFile.writeText(it) })
        val placeFile = File(filesDir, "places.json")
        places = PlaceBook({ placeFile.takeIf { it.exists() }?.readText() }, { placeFile.writeText(it) })
        // What the agent learned about each app (how its rows play / delete) survives restarts.
        val learnedFile = File(filesDir, "app-profiles-learned.json")
        runCatching {
            val json = org.json.JSONObject(learnedFile.takeIf { it.exists() }?.readText() ?: "{}")
            AppProfiles.restore(json.keys().asSequence().associateWith { pkg ->
                json.getJSONObject(pkg).let { facts -> facts.keys().asSequence().associateWith { facts.getString(it) } }
            })
        }
        AppProfiles.persist = { learned ->
            runCatching { learnedFile.writeText(org.json.JSONObject(learned.mapValues { org.json.JSONObject(it.value) }).toString()) }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "음성 비서 작업", NotificationManager.IMPORTANCE_LOW))
    }

    companion object { const val CHANNEL = "agent" }
}

val android.content.Context.app get() = applicationContext as AgentApp
