package dev.localphone.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import dev.localphone.agent.access.AgentAccessibilityService
import dev.localphone.agent.access.AndroidPhone
import dev.localphone.agent.access.AndroidTools
import dev.localphone.agent.speech.Speaker
import dev.localphone.core.*
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs one command at a time in the foreground, so the work continues while other apps are on screen.
 * Progress shows in a small accessibility overlay with a stop button.
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    /** Bumped by every new command, so a previous command's wrap-up never stops the service under it. */
    private var runId = 0
    private lateinit var speaker: Speaker

    override fun onCreate() { super.onCreate(); speaker = Speaker(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); speaker.shutdown(); AgentAccessibilityService.instance?.hideOverlay(); super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification("명령 처리 중"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        when (intent?.action) {
            ACTION_STOP -> { pending = null; job?.cancel(); finish("작업을 멈췄습니다.", speak = false, id = runId) }
            ACTION_RUN -> {
                pending = null
                start(intent.getStringExtra(EXTRA_GOAL).orEmpty(), emptyList(), intent)
            }
            ACTION_ANSWER -> pending?.let { p ->
                pending = null
                val answer = intent.getStringExtra(EXTRA_GOAL).orEmpty()
                // "어디로 안내할까요?" → "일요모임": the answer is the destination of a fresh command.
                if (p.history.any { it.action == "목적지 질문" }) start(GoalText.navigationGoalFrom(answer), emptyList(), intent)
                else start(p.goal, p.history + HistoryLine("사용자 답변", answer), intent)
            } ?: finish("이어갈 질문이 없습니다. 명령을 다시 말씀해 주세요.", speak = true, id = runId)
            // Debug builds: log what the agent would see on the current screen after a delay (other app in front).
            ACTION_DUMP -> if (BuildConfig.ADB_GOALS) scope.launch {
                delay(intent.getLongExtra(EXTRA_DELAY, 2000))
                val snapshot = AndroidPhone(this@AgentService).observe()
                val view = snapshot?.let(ScreenCompactor::compact)
                Log.i("AgentDump", "package=${snapshot?.packageName} label=${snapshot?.appLabel} home=${snapshot?.home} nodes=${snapshot?.nodes?.size} " +
                    "diag=${AgentAccessibilityService.instance?.observationDiagnostic}")
                view?.render()?.lines()?.forEach { Log.i("AgentDump", it) }
                snapshot?.nodes?.forEachIndexed { i, n ->
                    Log.d("AgentDumpRaw", "$i p=${n.parent} ${n.className.substringAfterLast('.')} t='${n.text.take(30)}' d='${n.desc.take(30)}' " +
                        "id=${n.viewId.substringAfterLast('/')} c=${n.clickable}${if (n.scrollable) " SCROLL" else ""} b=${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}")
                }
                Log.i("AgentDump", "END")
                if (job?.isActive != true) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
            else -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun start(goal: String, previous: List<HistoryLine>, intent: Intent) {
        if (goal.isBlank()) { stopSelf(); return }
        job?.cancel()
        val id = ++runId
        job = scope.launch {
            val trace = Trace(app.traces, goal, mapOf(
                "origin" to intent.getStringExtra(EXTRA_ORIGIN), "speech" to intent.getStringExtra(EXTRA_SPEECH),
                "version" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE,
                "model" to app.llm.modelFile()?.name, "gpu" to app.prefs.gpu, "threads" to app.prefs.threads,
                "note" to app.prefs.withNote, "testMode" to app.prefs.testMode))
            try {
            // After an app update or process restart the OS rebinds the service a few seconds later.
            val a11y = withTimeoutOrNull(8000) {
                while (AgentAccessibilityService.instance == null) delay(200)
                AgentAccessibilityService.instance
            }
            if (a11y == null) {
                Log.w("AgentStep", "RESULT FAILED: accessibility service not connected")
                val message = "화면 조작 권한(접근성)이 꺼져 있어요. 설정에서 켜 주세요."
                trace.finish(AgentResult(Outcome.FAILED, message, emptyList()))
                finish(message, speak = true, id = id); return@launch
            }
            a11y.onCancel = { if (id == runId) { job?.cancel(); finish("작업을 멈췄습니다.", speak = false, id = id) } }
            a11y.showStatus("“$goal”")
            val listener = object : AgentListener {
                override fun progress(text: String) { if (id == runId) AgentAccessibilityService.instance?.showStatus(text) }
                override fun needUser(text: String) { AgentAccessibilityService.instance?.showStatus(text); scope.launch { speaker.say(text) } }
                override fun step(record: StepRecord) {
                    trace.step(record, if (record.modelMs > 0) app.llm.lastStats else null)
                    AgentAccessibilityService.instance?.let { current ->
                        current.onCancel = { if (id == runId) { job?.cancel(); finish("작업을 멈췄습니다.", speak = false, id = id) } }
                        current.showStatus("${record.index}. ${record.action} → ${record.outcome}")
                    }
                }
            }
            val phone = AndroidPhone(this@AgentService)
            // The recognizer's other hypotheses ("수요 모임으로 안내해 줘" behind "수유 모임…") let the model
            // judge a misheard destination against the places the map actually lists.
            val alternatives = runCatching {
                val json = org.json.JSONObject(intent.getStringExtra(EXTRA_SPEECH) ?: "{}").optJSONArray("hypotheses")
                (0 until (json?.length() ?: 0)).map { json!!.getJSONObject(it).getString("text") }.drop(1)
            }.getOrDefault(emptyList())
            suspend fun runOne(command: String, before: List<HistoryLine>, heard: List<String>): AgentResult {
                val navigation = NavigationSession.forGoal(command, app.places.mentionedIn(command), heard)
                val agent = Agent(app.llm, phone,
                    if (app.prefs.useRecipes) app.recipes else RecipeBook({ null }, {}),
                    { app.prefs.noteLines },
                    config = AgentConfig(withNote = app.prefs.withNote),
                    listener = listener, navigation = navigation)
                // Tool first (deep links, media keys), the on-screen agent for everything else.
                val assistant = Assistant(app.llm, AndroidTools(this@AgentService, phone, navigation, listener), app.places, agent, listener)
                return try { assistant.run(command, before) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.e(TAG, "agent crashed", e)
                    AgentResult(Outcome.FAILED, "오류로 중단했어요: ${e.message}", emptyList())
                }
            }
            // "수원 집으로 안내해 주고 노래 틀어 줘": independent commands run one after another.
            val steps = if (previous.isEmpty()) CommandSplit.split(goal) else null
            var askedStep: String? = null
            val result = if (steps == null) runOne(goal, previous, alternatives) else {
                val says = mutableListOf<String>()
                var last = AgentResult(Outcome.FAILED, "", emptyList())
                for ((i, step) in steps.withIndex()) {
                    listener.progress("${i + 1}/${steps.size}: $step")
                    trace.step(StepRecord(0, "split", "", "", "step ${i + 1}/${steps.size}", step, "여러 명령을 차례로 실행", 0, 0), null)
                    last = runOne(step, emptyList(), emptyList())
                    if (last.outcome != Outcome.DONE) { askedStep = step; break }
                    says += last.say
                }
                // While driving, the map comes back once the other parts are done.
                if (last.outcome == Outcome.DONE && steps.dropLast(1).any { ShortcutGoals.navigationTarget(it) != null } &&
                    ShortcutGoals.navigationTarget(steps.last()) == null)
                    Harness.navigationApp().names.firstOrNull()?.let { phone.openApp(it) }
                if (last.outcome == Outcome.DONE) AgentResult(Outcome.DONE, says.joinToString(" "), last.history)
                else AgentResult(last.outcome, (says + last.say).joinToString(" "), last.history)
            }
            trace.finish(result)
            if (result.outcome == Outcome.FAILED) trace.observation(AgentAccessibilityService.instance?.observationDiagnostic)
            if (id != runId) return@launch
            a11y.showStatus(if (result.outcome == Outcome.DONE) "완료: ${result.say}" else result.say)
            when (result.outcome) {
                Outcome.ASK -> {
                    // A question in one part of several commands: the answer continues that part.
                    pending = Pending(askedStep ?: goal, result.history)
                    a11y.hideOverlay()
                    if (app.prefs.speak) speaker.say(result.say)
                    startActivity(Intent(this@AgentService, VoiceActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(VoiceActivity.EXTRA_QUESTION, result.say))
                    if (id == runId) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
                Outcome.DONE -> {
                    if (app.prefs.speak) speaker.say(result.say)
                    // Optional memory belongs in Settings; it must not leave a completed task 'running'
                    // for another 20 seconds waiting for a confirmation overlay.
                    if (result.learned && app.prefs.testMode) app.recipes.get(goal)?.takeIf { !it.confirmed }?.let { app.recipes.remove(it.key) }
                    finish(result.say, speak = false, id = id)
                }
                Outcome.FAILED -> finish(result.say, speak = true, id = id)
            }
            } catch (e: CancellationException) { trace.cancelled(); throw e }
            finally { trace.closed() }
        }
    }

    private fun finish(message: String, speak: Boolean, id: Int) {
        if (id != runId) return
        AgentAccessibilityService.instance?.let { it.showStatus(message); it.onCancel = null }
        scope.launch {
            if (speak && app.prefs.speak) speaker.say(message) else delay(600)
            if (id != runId) return@launch // a new command started meanwhile; it owns the service now
            AgentAccessibilityService.instance?.hideOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, AgentApp.CHANNEL)
            .setSmallIcon(R.drawable.ic_agent).setContentTitle("음성 비서").setContentText(text)
            .addAction(Notification.Action.Builder(null, "중지", stop).build()).setOngoing(true).build()
    }

    private data class Pending(val goal: String, val history: List<HistoryLine>)

    companion object {
        private const val TAG = "AgentService"
        const val ACTION_RUN = "run"
        const val ACTION_ANSWER = "answer"
        const val ACTION_STOP = "stop"
        const val ACTION_DUMP = "dump"
        const val EXTRA_DELAY = "delay"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_SPEECH = "speech_diagnostics"
        @Volatile private var pending: Pending? = null
        fun run(context: Context, goal: String, origin: String = "TEXT_TEST", speech: String? = null) = context.startForegroundService(
            Intent(context, AgentService::class.java).setAction(ACTION_RUN).putExtra(EXTRA_GOAL, goal)
                .putExtra(EXTRA_ORIGIN, origin).putExtra(EXTRA_SPEECH, speech))
        fun answer(context: Context, text: String, origin: String = "VOICE_ANSWER", speech: String? = null) = context.startForegroundService(
            Intent(context, AgentService::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_GOAL, text)
                .putExtra(EXTRA_ORIGIN, origin).putExtra(EXTRA_SPEECH, speech))
    }
}

/** One JSON line per step in Android/data/dev.localphone.agent/files/traces/, for debugging over adb. */
class Trace(dir: File, goal: String, metadata: Map<String, Any?> = emptyMap()) {
    private val gson = Gson()
    private val start = android.os.SystemClock.elapsedRealtime()
    private var finished = false
    private val session = java.util.UUID.randomUUID().toString()
    private val file = File(dir, SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + "-$session.jsonl")
    init { write(mapOf("type" to "start", "goal" to goal, "metadata" to metadata, "sessionId" to session)) }
    fun step(r: StepRecord, stats: Any?) {
        Log.i("AgentStep", "${r.index} [${r.source}] ${r.action} → ${r.outcome} (${r.note}) model=${r.modelMs}ms")
        write(mapOf("type" to "step", "record" to r, "stats" to stats))
    }
    fun finish(result: AgentResult) {
        finished = true
        Log.i("AgentStep", "RESULT ${result.outcome}: ${result.say}")
        val ms = android.os.SystemClock.elapsedRealtime() - start
        write(mapOf("type" to "result", "outcome" to result.outcome.name, "say" to result.say, "learned" to result.learned,
            "executionMs" to ms, "targetMs" to 10_000, "withinTarget" to (ms <= 10_000)))
    }
    fun cancelled() { write(mapOf("type" to "cancelled", "executionFinished" to finished)) }
    fun observation(diagnostic: String?) { write(mapOf("type" to "observation", "diagnostic" to diagnostic)) }
    fun closed() { write(mapOf("type" to "closed", "executionFinished" to finished, "elapsedMs" to (android.os.SystemClock.elapsedRealtime() - start))) }
    @Synchronized private fun write(value: Any) = runCatching { file.appendText(gson.toJson(value) + "\n") }
        .onFailure { Log.w("AgentStep", "trace write failed", it) }
}
