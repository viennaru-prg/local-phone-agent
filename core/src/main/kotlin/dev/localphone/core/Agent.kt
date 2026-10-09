package dev.localphone.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Local LLM. Returns the raw constrained JSON text for one decision. */
interface LanguageModel {
    suspend fun decide(prompt: ModelPrompt, grammar: String): String
}

data class OpenAppResult(val ok: Boolean, val message: String)

/** The device side: accessibility observation/actions and app launching (any profile, incl. Secure Folder). */
interface Phone {
    suspend fun observe(): Snapshot?
    suspend fun perform(view: ScreenView, action: AgentAction): Boolean
    /** A fresh observed-node tap when an app accepts ACTION_CLICK without responding. */
    suspend fun tap(view: ScreenView, id: Int): Boolean = perform(view, AgentAction.Click(id))
    suspend fun openApp(name: String): OpenAppResult
    fun matchesApp(view: ScreenView, name: String): Boolean = GoalText.normalize(view.snapshot.appLabel) == GoalText.normalize(name)
    suspend fun media(key: MediaKey): Boolean
    fun now(): Long
}

interface AgentListener {
    fun progress(text: String) {}
    fun step(record: StepRecord) {}
    /** Something only the user can do (unlock Secure Folder); the app should say it out loud. */
    fun needUser(text: String) {}
}

data class StepRecord(val index: Int, val source: String, val screen: String, val raw: String, val action: String,
                      val outcome: String, val note: String, val modelMs: Long, val totalMs: Long)

enum class Outcome { DONE, ASK, FAILED }
data class AgentResult(val outcome: Outcome, val say: String, val history: List<HistoryLine>, val learned: Boolean = false)

class AgentConfig(
    val maxSteps: Int = 30,
    val maxNoChange: Int = 4,
    val maxBadOutput: Int = 3,
    val settlePollMs: Long = 150,
    val changeWaitMs: Long = 2500,
    val appOpenWaitMs: Long = 5000,
    /** How long to wait for the user to unlock a lock screen (Secure Folder) before giving up. */
    val unlockWaitMs: Long = 45_000,
    /** Ask the model for a short reasoning note before each action (slower, sometimes more accurate). */
    val withNote: Boolean = true,
)

/**
 * One generic loop for every app: observe → model picks one action → execute → check the screen
 * changed → repeat. A recipe learned from an earlier success is replayed first while its labels still
 * match the live screen; the model takes over as soon as they do not.
 */
class Agent(
    private val model: LanguageModel,
    private val phone: Phone,
    private val recipes: RecipeBook,
    private val notes: () -> List<String>,
    private val config: AgentConfig = AgentConfig(),
    private val listener: AgentListener = object : AgentListener {},
    private val navigation: NavigationSession? = null,
) {
    private class Run(val goal: String, val started: Long) {
        val history = mutableListOf<HistoryLine>()
        val learned = mutableListOf<RecipeStep>()
        var noChange = 0
        var badOutput = 0
        var replayed = false
        var modelSteps = 0
        var rejectedDone = 0
        val ineffective = mutableMapOf<Pair<Int, String>, Int>()
    }

    /** The model's completion check: [ok] plus a one-line reason (or, when ok, the result to tell the user). */
    private data class Verdict(val ok: Boolean, val text: String)

    private suspend fun verify(goal: String, run: Run, view: ScreenView): Verdict {
        navigation?.let {
            val evidence = it.observe(view)
            return Verdict(evidence.complete, evidence.reason)
        }
        // Hard evidence first: a search is not done while the query appears nowhere on screen.
        if (Harness.openScreenEvidence(goal, view)) return Verdict(true, GoalText.spokenResult(goal, ""))
        if (!Harness.searchShown(goal, view)) return Verdict(false, "검색어가 화면에 아직 없음")
        if (!Harness.screenShown(goal, view)) return Verdict(false, "요청한 화면의 이름이 보이지 않음")
        listener.progress("전체 목표 완료 여부 확인 중…")
        val started = phone.now()
        val raw = model.decide(Prompts.verify(goal, notes(), run.history, view, config.withNote), Prompts.VERIFY_GRAMMAR)
        val json = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return Verdict(false, "확인 응답 형식 오류")
        val text = json.get("reason")?.asString.orEmpty().trim()
        val verdict = if (json.get("ok")?.asBoolean == true) Verdict(true, text) else Verdict(false, text.ifBlank { "목표와 다름" })
        listener.step(StepRecord(run.history.size, "model_verify", view.render(), raw, "verify",
            if (verdict.ok) "목표 완료 확인됨" else "검증 실패", verdict.text, phone.now() - started, phone.now() - run.started))
        return verdict
    }

    private fun finish(run: Run, goal: String, modelSay: String): AgentResult {
        val say = GoalText.spokenResult(goal, modelSay)
        val learned = run.learned.isNotEmpty() && (run.modelSteps > 1 || !run.replayed)
        if (learned) recipes.put(Recipe(GoalKey.of(goal), goal, run.learned.toList(), say, phone.now()))
        return AgentResult(Outcome.DONE, say, run.history, learned)
    }

    suspend fun run(goal: String, previous: List<HistoryLine> = emptyList()): AgentResult {
        val started = phone.now()
        val run = Run(goal, started)
        run.history += previous
        if (previous.isEmpty() && GoalText.isChatter(goal))
            return AgentResult(Outcome.FAILED, "할 일을 알아듣지 못했어요. 다시 말씀해 주세요.", run.history)
        var view = observe() ?: return fail(run, "화면을 읽을 수 없습니다. 접근성 서비스가 켜져 있는지 확인해 주세요.")
        navigation?.observe(view)

        // Resolve any explicitly named installed app through Android; never spend a model call
        // rediscovering the launcher. The complete goal remains unchanged for the screen loop.
        GoalText.namedApp(goal)?.let { app ->
            if (!phone.matchesApp(view, app)) {
                val (outcome, next) = execute(run, AgentAction.OpenApp(app), view)
                listener.step(StepRecord(0, "android_api", "", "", "open_app \"$app\"", outcome, "명령에 명시된 앱에서 전체 목표를 계속 수행", 0, phone.now() - started))
                view = next
            } else run.history += HistoryLine("open_app \"$app\"", "이미 요청한 앱이 전면에 있음")
        }

        if (previous.isEmpty()) recipes.find(goal)?.let { recipe ->
            listener.progress("기억한 방법으로 실행 중…")
            view = replay(run, recipe, view)
        }

        var index = run.history.size
        while (index < config.maxSteps) {
            currentCoroutineContext().ensureActive()
            index++
            navigation?.observe(view)?.takeIf { it.complete }?.let {
                listener.step(StepRecord(index, "navigation", view.render(), "", "verify", "완료", it.reason, 0, phone.now() - started))
                return finish(run, goal, "길안내를 시작했어요.")
            }
            if (Harness.openScreenEvidence(goal, view)) {
                listener.step(StepRecord(index, "harness", view.render(), "", "verify", "완료", "요청한 화면의 제목·선택 탭 확인", 0, phone.now() - started))
                return finish(run, goal, "")
            }
            // Lock screens are the user's: ask them to unlock and wait, never touch the pattern/PIN.
            if (Harness.authScreen(view)) {
                listener.needUser("${view.snapshot.appLabel} 잠금을 풀어 주세요.")
                listener.step(StepRecord(index, "harness", view.render(), "", "wait_for_user", "잠금 해제 요청", "", 0, phone.now() - started))
                val deadline = phone.now() + config.unlockWaitMs
                while (phone.now() < deadline) {
                    delay(500)
                    val now = observe() ?: continue
                    if (!Harness.authScreen(now)) { view = settle(now, waitForChange = false).first; break }
                }
                if (Harness.authScreen(view)) return fail(run, "잠금이 풀리지 않아 중단했어요.")
                run.history += HistoryLine("잠금 해제 기다림", "사용자가 잠금을 풂")
                continue
            }
            (navigation?.nextAction(view, phone.now()) ?: Harness.preDecide(goal, view, run.history))?.let { auto ->
                val (outcome, next) = execute(run, auto.action, view)
                run.history[run.history.lastIndex] = run.history.last().copy(note = auto.reason)
                listener.step(StepRecord(index, "harness", view.render(), "", auto.action.describe(view), outcome, auto.reason, 0, phone.now() - started))
                checkFinished(goal, run, auto.action, view, outcome, next, index, started)?.let { return it }
                view = next
                if (run.noChange >= config.maxNoChange) return fail(run, "화면이 더 이상 바뀌지 않아 중단했습니다.")
                continue
            }
            listener.progress("다음 동작 판단 중… ($index)")
            val prompt = Prompts.step(goal, notes(), run.history, view, config.withNote)
            val t0 = phone.now()
            val fingerprint = progressFingerprint(view, goal)
            val excluded = view.elements.filter { e ->
                (run.ineffective[fingerprint to "click \"${e.label}\""] ?: 0) > 0 ||
                    (run.ineffective[fingerprint to "long_click \"${e.label}\""] ?: 0) > 0
            }.map { it.id }.toSet()
            val raw = model.decide(prompt, ActionGrammar.forView(view, config.withNote, excluded))
            val modelMs = phone.now() - t0
            run.modelSteps++
            var decision = try { ActionParser.parse(raw, view) } catch (e: BadModelOutput) {
                run.history += HistoryLine("(잘못된 출력)", e.message ?: "형식 오류")
                listener.step(StepRecord(index, "model", view.render(), raw, "invalid", e.message.orEmpty(), "", modelMs, phone.now() - started))
                if (++run.badOutput >= config.maxBadOutput) return fail(run, "AI 응답을 해석하지 못했습니다.")
                continue
            }
            run.badOutput = 0
            val liveView = observe() ?: view
            if (liveView.snapshot.packageName != view.snapshot.packageName) {
                listener.step(StepRecord(index, "harness", "", raw, "stop", "전면 앱 전환으로 중단", "모델 계산 중 다른 앱이 전면에 나타나 이전 목표로 조작하지 않음", modelMs, phone.now() - started))
                return fail(run, "실행 중 다른 앱으로 전환되어 중단했어요. 원래 작업 화면에서 다시 요청해 주세요.")
            }
            val bound = ActionGrounding.rebind(decision.action, view, liveView)
            if (bound == null) {
                run.history += HistoryLine("판단 중 화면 변경", "최신 화면에서 다시 판단")
                listener.step(StepRecord(index, "harness", "", raw, "reobserve", "이전 화면 판단 폐기", "앱 전환 또는 조작 대상의 식별·상태가 바뀜", modelMs, phone.now() - started))
                view = liveView
                continue
            }
            if ((decision.action is AgentAction.Click && (decision.action as AgentAction.Click).id in excluded) ||
                (decision.action is AgentAction.LongClick && (decision.action as AgentAction.LongClick).id in excluded)) {
                record(run, index, "harness", view, raw, decision, "같은 상태에서 효과 없던 동작이라 재실행하지 않음", modelMs, started)
                if (++run.noChange >= config.maxNoChange) return fail(run, "다른 실행 경로를 찾지 못했습니다.")
                continue
            }
            view = liveView
            decision = decision.copy(action = bound)
            val action = bound
            when (action) {
                is AgentAction.Done -> {
                    // A second opinion on the same screen before declaring success or learning the path.
                    view = observe() ?: view
                    val verdict = verify(goal, run, view)
                    if (!verdict.ok) {
                        record(run, index, "model", view, raw, decision, "검증 실패: ${verdict.text}", modelMs, started)
                        if (++run.rejectedDone >= 2) return fail(run, "작업이 목표대로 끝났는지 확인하지 못했습니다. (${verdict.text})")
                        continue
                    }
                    record(run, index, "model", view, raw, decision, "완료", modelMs, started)
                    return finish(run, goal, action.say)
                }
                is AgentAction.Ask -> {
                    record(run, index, "model", view, raw, decision, "사용자에게 질문", modelMs, started)
                    return AgentResult(Outcome.ASK, action.question, run.history)
                }
                is AgentAction.Fail -> {
                    record(run, index, "model", view, raw, decision, "중단", modelMs, started)
                    return AgentResult(Outcome.FAILED, action.reason.ifBlank { "작업을 완료하지 못했습니다." }, run.history)
                }
                else -> Unit
            }
            val (outcome, next) = execute(run, action, view)
            record(run, index, "model", view, raw, decision, outcome, modelMs, started)
            checkFinished(goal, run, action, view, outcome, next, index, started)?.let { return it }
            view = next
            if (run.noChange >= config.maxNoChange) return fail(run, "화면이 더 이상 바뀌지 않아 중단했습니다.")
        }
        return fail(run, "단계 수 한도(${config.maxSteps})를 넘어 중단했습니다.")
    }

    /**
     * Small models rarely notice that a "start / save / play" press, reaching the requested screen, or
     * submitting a search already finished the job. Right after such a step changes the screen, the
     * harness asks for the completion check itself. Returns the result when the goal is done.
     */
    private suspend fun checkFinished(goal: String, run: Run, action: AgentAction, view: ScreenView, outcome: String,
                                      next: ScreenView, index: Int, started: Long): AgentResult? {
        val label = when (action) { is AgentAction.Click -> view.element(action.id)?.label; else -> null }.orEmpty()
        val target = GoalText.targetWords(goal)
        // Pressing the requested tab and seeing nothing change usually means it was already open.
        val alreadyThere = action is AgentAction.Click && outcome.startsWith("변화 없음") &&
            GoalText.opensScreen(goal) && GoalText.matches(label, target)
        if (!alreadyThere && !outcome.contains("바뀜") && !(action is AgentAction.OpenApp && outcome.startsWith("열림"))) return null
        val finishing = alreadyThere || when (action) {
            is AgentAction.Click -> Commit.isCommit(label) || (GoalText.opensScreen(goal) && GoalText.matches(label, target))
            is AgentAction.Type -> action.enter && GoalText.searchQuery(goal) != null
            // The app may open straight onto the requested tab ("시계 앱에서 타이머 화면 열어줘").
            is AgentAction.OpenApp -> GoalText.opensScreen(goal) && next.elements.any { it.selected && GoalText.matches(it.label, target) }
            else -> false
        }
        if (!finishing) return null
        // A submitted search whose query is now on screen is done; no model opinion needed.
        if (action is AgentAction.Type && !GoalScope.multiple(goal) && Harness.searchShown(goal, next)) {
            run.history += HistoryLine("완료 확인", "검색어가 화면에 보임")
            listener.step(StepRecord(index, "harness", next.render(), "", "verify", "완료", "검색 제출 확인", 0, phone.now() - started))
            return finish(run, goal, "")
        }
        if (action is AgentAction.Type && GoalScope.multiple(goal) && Harness.searchShown(goal, next)) {
            run.history += HistoryLine("일부 목표 확인", "검색어 제출만 확인됨. 전체 목표의 남은 작업을 계속해야 함")
            return null // A search submission is never the terminal action of a search-then-other-task goal.
        }
        val verdict = verify(goal, run, next)
        if (!verdict.ok) return null
        run.history += HistoryLine("완료 확인", "목표 달성", verdict.text)
        listener.step(StepRecord(index, "harness", next.render(), "", "verify", "완료", verdict.text, 0, phone.now() - started))
        return finish(run, goal, verdict.text)
    }

    /** Replays recorded steps while each one resolves on the live screen. Returns the latest view. */
    private suspend fun replay(run: Run, recipe: Recipe, start: ScreenView): ScreenView {
        var view = start
        var i = 0
        while (i < recipe.steps.size) {
            currentCoroutineContext().ensureActive()
            Harness.preDecide(run.goal, view, run.history)?.let { auto ->
                val (_, next) = execute(run, auto.action, view)
                run.history[run.history.lastIndex] = run.history.last().copy(note = auto.reason)
                view = next
            }
            val step = recipe.steps[i]
            var action = RecipeRecorder.toAction(step, view)
            // Screens can still be loading after the previous step; give the label a moment to appear.
            val deadline = phone.now() + 2500
            var ahead = -1
            while (action == null && phone.now() < deadline) {
                delay(config.settlePollMs); view = observe() ?: return view
                action = RecipeRecorder.toAction(step, view)
                // A recorded step that only appears sometimes (a pop-up) may be absent this time:
                // if one of the next two steps is already on screen, skip ahead to it.
                if (action == null) ahead = (i + 1..minOf(i + 2, recipe.steps.lastIndex)).firstOrNull {
                    recipe.steps[it].op in setOf("click", "long_click", "type") && RecipeRecorder.toAction(recipe.steps[it], view) != null
                } ?: -1
                if (ahead >= 0) break
            }
            if (action == null && ahead >= 0) {
                run.history += HistoryLine("(기억한 방법 ${i + 1}단계 '${step.label}' 없음 → 건너뜀)", "다음 단계가 화면에 있음")
                i = ahead; continue
            }
            if (action == null) {
                run.history += HistoryLine("(기억한 방법 ${i + 1}단계 '${step.label}' 화면에 없음)", "AI가 이어서 판단")
                return view
            }
            val (outcome, next) = execute(run, action, view)
            listener.step(StepRecord(run.history.size, "recipe", view.render(), "", action.describe(view), outcome, "", 0, 0))
            view = next
            run.replayed = true
            i++
        }
        recipes.used(recipe.key, phone.now())
        return view
    }

    /** Runs one device action, records it, and waits for the screen to react. */
    private suspend fun execute(run: Run, action: AgentAction, view: ScreenView): Pair<String, ScreenView> {
        val description = action.describe(view, ids = false)
        Guard.blocked(run.goal, action, view)?.let { reason ->
            run.history += HistoryLine(description, reason); run.noChange++
            return reason to view
        }
        val outcome: String
        val next: ScreenView
        when (action) {
            is AgentAction.OpenApp -> {
                // Small models often "re-open" the app that is already in front; skip it and say so.
                val current = GoalText.normalize(view.snapshot.appLabel)
                val wanted = GoalText.normalize(action.app)
                if (!view.snapshot.home && current.isNotEmpty() && wanted.isNotEmpty() && (current.contains(wanted) || wanted.contains(current))) {
                    val note = "이미 열려 있음 — 화면의 요소로 다음 단계를 진행"
                    run.history += HistoryLine(description, note)
                    return note to view
                }
                // After the requested app was not found, never substitute some other app (it opened
                // "Steam" for "ClipStream"); only apps named in the command or the notes are allowed.
                // The command names its app ("유튜브에서 …"): never open a different one.
                GoalText.namedApp(run.goal)?.let { named ->
                    val n = GoalText.normalize(named)
                    val sameScript = n.all { it.code < 128 } == wanted.all { it.code < 128 } // "크롬" vs "Chrome" can't be compared here
                    if (sameScript && !wanted.contains(n) && !n.contains(wanted)) {
                        val note = "명령에 적힌 앱('$named')이 아니라서 열지 않음"
                        run.history += HistoryLine(description, note); run.noChange++
                        return note to view
                    }
                }
                // Apps suggested by the failure message itself ("open_app \"보안 폴더\"") also count.
                val grounding = GoalText.normalize(run.goal + " " + GoalText.relevantNotes(run.goal, notes()).joinToString(" ") +
                    " " + run.history.filter { it.outcome.contains("찾지 못함") }.joinToString(" ") { it.outcome })
                if (run.history.any { it.action.startsWith("open_app") && it.outcome.contains("찾지 못함") } && !grounding.contains(wanted)) {
                    val note = "명령이나 메모에 없는 앱이라 열지 않음"
                    run.history += HistoryLine(description, note); run.noChange++
                    return note to view
                }
                val result = phone.openApp(action.app)
                val alreadyOpen = result.ok && result.message.startsWith("이미 열려 있음")
                if (alreadyOpen) {
                    val note = "${result.message} — 화면의 요소로 다음 단계를 진행"
                    run.history += HistoryLine(description, note); run.noChange++
                    return note to view
                }
                next = if (result.ok) waitForApp(view) else view
                outcome = result.message
                if (result.ok) { run.noChange = 0; RecipeRecorder.step(action, view)?.let(run.learned::add) } else run.noChange++
            }
            is AgentAction.Media -> {
                val ok = phone.media(action.key)
                outcome = if (ok) "미디어 키 전송됨" else "미디어 키 전송 실패"
                next = settle(view, waitForChange = false).first
                if (ok) RecipeRecorder.step(action, view)?.let(run.learned::add)
            }
            AgentAction.Wait -> {
                delay(if (navigation != null) 250 else 1200)
                val (after, changed) = settle(view, waitForChange = false)
                outcome = if (changed) "화면 바뀜" else "변화 없음"
                next = after
            }
            else -> {
                val ok = if (action is AgentAction.Click && navigation?.needsTap(view, action.id) == true)
                    phone.tap(view, action.id) else phone.perform(view, action)
                navigation?.recordAction(view, action, ok, phone.now())
                if (!ok) {
                    outcome = "실행 실패"; next = observe() ?: view; run.noChange++
                } else {
                    val (after, changed) = settle(view, waitForChange = true)
                    next = after
                    outcome = when {
                        changed && after.snapshot.packageName != view.snapshot.packageName -> "다른 앱 화면으로 바뀜 (${after.snapshot.appLabel})"
                        changed -> "화면 바뀜"
                        else -> "변화 없음"
                    }
                    if (changed) run.noChange = 0 else run.noChange++
                    // Text entry often does not change labels until submitted; still part of the path.
                    if (changed || action is AgentAction.Type) RecipeRecorder.step(action, view)?.let(run.learned::add)
                }
            }
        }
        run.history += HistoryLine(description, outcome)
        // GPS distances, elapsed time and other text changes do not prove the requested operation
        // advanced. Remember ineffective actions per control state and exclude them next time.
        if (action !is AgentAction.Wait && action !is AgentAction.Media && action !is AgentAction.OpenApp &&
            progressFingerprint(view, run.goal) == progressFingerprint(next, run.goal)) {
            val key = progressFingerprint(view, run.goal) to description
            run.ineffective[key] = (run.ineffective[key] ?: 0) + 1
            if (outcome.contains("바뀜")) {
                run.noChange++
                run.history[run.history.lastIndex] = run.history.last().copy(outcome = "변화 없음 (동적 텍스트만 바뀜)")
                return "변화 없음 (동적 텍스트만 바뀜)" to next
            }
        }
        return outcome to next
    }

    private fun record(run: Run, index: Int, source: String, view: ScreenView, raw: String, decision: Decision,
                       outcome: String, modelMs: Long, started: Long) {
        // Attach the model's note to the history line that execute() just appended (or add one for terminal actions).
        val last = run.history.lastOrNull()
        if (decision.action is AgentAction.Done || decision.action is AgentAction.Ask || decision.action is AgentAction.Fail) {
            run.history += HistoryLine(decision.action.describe(view, ids = false), outcome, decision.note)
        } else if (last != null) {
            run.history[run.history.lastIndex] = last.copy(note = decision.note)
        }
        listener.step(StepRecord(index, source, view.render(), raw, decision.action.describe(view), outcome, decision.note,
            modelMs, phone.now() - started))
    }

    private suspend fun observe(): ScreenView? {
        repeat(10) {
            phone.observe()?.let { return ScreenCompactor.compact(it) }
            delay(200)
        }
        return null
    }

    /**
     * Polls until two consecutive observations agree. With [waitForChange] it keeps waiting (up to
     * changeWaitMs) for a screen that differs from [before]; otherwise it just lets the screen settle.
     */
    private suspend fun settle(before: ScreenView, waitForChange: Boolean): Pair<ScreenView, Boolean> {
        val deadline = phone.now() + if (waitForChange) config.changeWaitMs else 1000
        var last = before
        var stable = 0
        while (phone.now() < deadline) {
            delay(config.settlePollMs)
            val current = observe() ?: continue
            stable = if (current.signature == last.signature) stable + 1 else 0
            last = current
            val changed = current.signature != before.signature
            if (stable >= 1 && (changed || !waitForChange)) return current to changed
        }
        return last to (last.signature != before.signature)
    }

    private suspend fun waitForApp(before: ScreenView): ScreenView {
        val deadline = phone.now() + config.appOpenWaitMs
        while (phone.now() < deadline) {
            delay(config.settlePollMs * 2)
            val current = observe() ?: continue
            if (current.snapshot.packageName != before.snapshot.packageName && current.elements.isNotEmpty())
                return settle(current, waitForChange = false).first
        }
        return observe() ?: before
    }

    private fun fail(run: Run, message: String) = AgentResult(Outcome.FAILED, message, run.history)

    private fun progressFingerprint(view: ScreenView, goal: String): Int {
        val controls = view.elements.filter { it.kind != Kind.TEXT || it.selected }
            .map { listOf(it.kind, it.label.replace(Regex("[0-9]+(?:[.:][0-9]+)*"), "#"), it.enabled, it.selected, it.checked, it.value) }
        val results = view.elements.filter { it.kind == Kind.TEXT && GoalText.matches(it.label, GoalText.targetWords(goal)) }
            .map { it.label }
        return listOf(view.snapshot.packageName, controls, results).hashCode()
    }
}

/**
 * Buttons that usually finish a task when pressed ("안내시작 10", "재생", "담기", ...). "저장", "확인" and
 * "설정" are left out: they also name tabs and menus (네이버 지도's "저장" is the saved-places list).
 */
object Commit {
    // English words only as whole words, and no "play": "Google Play 검색" is not a play button.
    private val words = Regex("시작|완료|종료|끝내|중지|닫기|적용|등록|담기|추가|재생|전송|\\bstart\\b|\\bdone\\b|\\bstop\\b|\\bexit\\b|\\bclose\\b", RegexOption.IGNORE_CASE)
    fun isCommit(label: String) = label.length <= 20 && words.containsMatchIn(label)
}

/** Actions the agent never performs on its own, whatever the screen text or model output says. */
object Guard {
    private val protected = Regex("결제|구매하기|송금|이체|비밀번호|인증번호|권한\\s*허용|^허용$|모두\\s*삭제|계정\\s*삭제|탈퇴|password|pay now|purchase", RegexOption.IGNORE_CASE)
    private val delete = Regex("삭제|delete|remove|제거|초기화|reset", RegexOption.IGNORE_CASE)
    private val swap = Regex("출발지.*도착지.*(?:전환|변경|바꾸기)|swap", RegexOption.IGNORE_CASE)
    fun blocked(goal: String, action: AgentAction, view: ScreenView): String? {
        if (action is AgentAction.Media && !Router.allowsMedia(goal)) return "명령에 없는 미디어 동작이라 실행하지 않음"
        val id = when (action) {
            is AgentAction.Click -> action.id
            is AgentAction.LongClick -> action.id
            is AgentAction.Type -> action.id
            else -> return null
        }
        val label = view.element(id)?.label.orEmpty()
        if (view.element(id)?.enabled != true) return "비활성 요소라 실행하지 않음"
        if (protected.containsMatchIn(label)) return "보호된 동작이라 실행하지 않음"
        // Swapping origin and destination silently reverses a route the user asked for.
        if (swap.containsMatchIn(label) && !Regex("출발").containsMatchIn(goal)) return "출발지와 도착지를 바꾸는 버튼이라 누르지 않음"
        // Deleting is allowed only when the user asked to remove something ("빼줘", "삭제해줘", ...).
        if (delete.containsMatchIn(label) && !Regex("삭제|지워|빼|제거|remove|delete", RegexOption.IGNORE_CASE).containsMatchIn(goal))
            return "목표에 없는 삭제 동작이라 실행하지 않음"
        return null
    }
}
