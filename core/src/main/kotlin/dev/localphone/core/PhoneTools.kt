package dev.localphone.core

import com.google.gson.Gson

enum class SettingsPage(val key: String, val displayName: String, val phrases: List<String>) {
    GENERAL("general", "설정", listOf("설정", "settings")),
    WIFI("wifi", "와이파이 설정", listOf("와이파이", "wifi", "wi-fi")),
    BLUETOOTH("bluetooth", "블루투스 설정", listOf("블루투스", "bluetooth")),
    SOUND("sound", "소리 설정", listOf("소리", "음량", "sound", "volume")),
    DISPLAY("display", "화면 설정", listOf("화면", "디스플레이", "display")),
}

/** A schema and its decoder are registered together; the model cannot invent a runtime tool. */
class RegisteredTool(val name: String, val description: String, val properties: Map<String, Map<String, Any>>,
                     private val decoder: (Map<String, Any?>) -> Action?) {
    fun decode(arguments: Map<String, Any?>): Action? =
        if (arguments.keys == properties.keys) runCatching { decoder(arguments) }.getOrNull() else null

    fun schema(): Map<String, Any> = linkedMapOf(
        "name" to name, "description" to description,
        "parameters" to linkedMapOf("type" to "object", "properties" to properties,
            "required" to properties.keys.toList(), "additionalProperties" to false))
}

class ToolRegistry(tools: List<RegisteredTool>) {
    companion object { const val MAX_STEPS = 6 }
    val tools = tools.toList()
    private val byName = tools.associateBy { it.name }
    init { require(byName.size == tools.size) { "Duplicate tool names" } }
    fun decode(calls: List<RawToolCall>): ToolPlan {
        if (calls.isEmpty() || calls.size > MAX_STEPS) return rejected()
        val actions = calls.map { call -> byName[call.name]?.decode(call.arguments) ?: return rejected() }
        if (actions.distinct().size != actions.size) return rejected()
        if (actions.filterIsInstance<Action.Navigate>().size > 1) return ToolPlan(emptyList(), "아직 경유지는 지원하지 않습니다.")
        return ToolPlan(actions)
    }
    fun schemaJson(): List<String> = tools.map { Gson().toJson(it.schema()) }
    private fun rejected() = ToolPlan(emptyList(), "허용되지 않은 도구 또는 인자입니다. 실행하지 않았습니다.")
}

object PhoneTools {
    private fun text(max: Int) = mapOf<String, Any>("type" to "string", "minLength" to 1, "maxLength" to max)
    private fun integer(min: Int, max: Int) = mapOf<String, Any>("type" to "integer", "minimum" to min, "maximum" to max)
    private fun string(args: Map<String, Any?>, key: String, max: Int): String? {
        val value = (args[key] as? String)?.trim() ?: return null
        return value.takeIf { it.length in 1..max && !it.any(Char::isISOControl) }
    }
    private fun int(args: Map<String, Any?>, key: String, range: IntRange): Int? {
        val number = (args[key] as? Number)?.toDouble() ?: return null
        return number.takeIf { it.isFinite() && it == it.toInt().toDouble() && it.toInt() in range }?.toInt()
    }
    val registry = ToolRegistry(listOf(
        RegisteredTool("perform_app_task", "Continue the user's goal in an installed app using its observed UI when a structured function is unavailable. Preserve the user's original goal and app name; use an empty app name only for the foreground app.",
            mapOf("app_name" to mapOf<String, Any>("type" to "string", "maxLength" to 80), "goal" to text(1000))) { args ->
            val app = (args["app_name"] as? String)?.trim()
            val goal = string(args, "goal", 1000)
            if (app != null && app.length <= 80 && !app.any(Char::isISOControl) && goal != null) Action.AppTask(app, goal) else null
        },
        RegisteredTool("navigate", "Navigate to the place named by the user. Preserve the place phrase; never provide coordinates.",
            mapOf("destination" to text(160))) { args ->
            string(args, "destination", 160)?.takeUnless { it.contains(Regex("https?://|geo:|nmap:|[0-9]+\\.[0-9]+")) }?.let(Action::Navigate)
        },
        RegisteredTool("media_resume", "Resume the user's selected music session in the background.", emptyMap()) { Action.MediaResume },
        RegisteredTool("media_pause", "Pause the user's selected music session in the background.", emptyMap()) { Action.MediaPause },
        RegisteredTool("media_next", "Skip once to the next track in the user's selected music session.", emptyMap()) { Action.MediaNext },
        RegisteredTool("open_app", "Open an installed app by the name the user said. Do not supply a package, URL, or activity.",
            mapOf("app_name" to text(80))) { args ->
            string(args, "app_name", 80)?.takeUnless { it.contains(Regex("https?://|intent:|://|(?:^|\\s)(?:com|dev|org)\\.")) }?.let(Action::OpenApp)
        },
        RegisteredTool("set_alarm", "Request a one-time next-occurrence alarm at a 24-hour time using the Clock app. No date or repeat support.",
            mapOf("hour" to integer(0, 23), "minute" to integer(0, 59))) { args ->
            val hour = int(args, "hour", 0..23); val minute = int(args, "minute", 0..59)
            if (hour != null && minute != null) Action.SetAlarm(hour, minute) else null
        },
        RegisteredTool("set_timer", "Request a countdown timer using the Clock app.",
            mapOf("seconds" to integer(1, 86400))) { args -> int(args, "seconds", 1..86400)?.let(Action::SetTimer) },
        RegisteredTool("open_settings", "Open a system settings page for user review. This tool does not change a setting.",
            mapOf("page" to mapOf<String, Any>("type" to "string", "enum" to SettingsPage.entries.map { it.key }))) { args ->
            SettingsPage.entries.singleOrNull { it.key == args["page"] }?.let(Action::OpenSettings)
        },
    ))
}

/** Development grammar; this is not AI inference. Each successful match consumes the whole command. */
object PhoneCommands {
    private val alarm = Regex("^(?:(오전|오후)\\s*)?(\\d{1,2})시(?:\\s*(\\d{1,2})분)?\\s*(?:에\\s*)?알람(?:을)?\\s*(?:맞춰(?:줘)?|설정(?:해줘|해 줘)?|켜(?:줘)?)$")
    private val timer = Regex("^(?:(\\d{1,2})시간\\s*)?(?:(\\d{1,3})분\\s*)?(?:(\\d{1,3})초\\s*)?타이머(?:를)?\\s*(?:시작(?:해줘|해 줘)?|맞춰(?:줘)?|설정(?:해줘|해 줘)?|켜(?:줘)?)$")
    private val open = Regex("^(.+?)\\s*(?:앱(?:을)?\\s*)?(?:열어(?:줘| 줘)?|실행(?:해줘|해 줘)?|켜(?:줘| 줘)?)$")
    fun parse(utterance: String): ToolPlan? {
        val text = SpokenTime.normalize(utterance)
        if (text.contains("알람") && !Regex("열어|실행").containsMatchIn(text)) {
            val match = alarm.matchEntire(text) ?: return blocked("알람은 '오전 7시 30분 알람 맞춰줘'처럼 시간을 지정해 주세요. 날짜·반복 설정은 아직 지원하지 않습니다.")
            val period = match.groupValues[1]; val hour = match.groupValues[2].toInt(); val minute = match.groupValues[3].ifBlank { "0" }.toInt()
            if (minute !in 0..59 || hour !in 0..23 || (period.isNotBlank() && hour !in 1..12)) return blocked("유효한 알람 시간을 입력해 주세요.")
            if (period.isBlank() && hour in 1..12) return blocked("오전인지 오후인지 지정해 주세요. 예: 오전 7시 알람 맞춰줘")
            val converted = when (period) { "오전" -> hour % 12; "오후" -> hour % 12 + 12; else -> hour }
            return ToolPlan(listOf(Action.SetAlarm(converted, minute)))
        }
        if (text.contains("타이머") && !Regex("열어|실행").containsMatchIn(text)) {
            val match = timer.matchEntire(text) ?: return blocked("예: 5분 타이머 시작해줘")
            val values = (1..3).map { match.groupValues[it].ifBlank { "0" }.toInt() }
            val seconds = values[0] * 3600 + values[1] * 60 + values[2]
            return if (seconds in 1..86400) ToolPlan(listOf(Action.SetTimer(seconds))) else blocked("타이머는 1초부터 24시간까지 지정해 주세요.")
        }
        if (Regex("노래|음악|미디어").containsMatchIn(text) && !text.contains("앱")) return null
        val match = open.matchEntire(text) ?: return null
        val target = match.groupValues[1].trim().removeSuffix(" 앱").trim()
        if (target.contains("설정") || target.equals("settings", true)) {
            val page = SettingsPage.entries.filter { it != SettingsPage.GENERAL }.singleOrNull { page -> page.phrases.any { target.contains(it, true) } }
                ?: if (target in listOf("설정", "설정 앱") || target.equals("settings", true)) SettingsPage.GENERAL else return blocked("지원 설정: 와이파이 / 블루투스 / 소리 / 화면 / 설정")
            return ToolPlan(listOf(Action.OpenSettings(page)))
        }
        return PhoneTools.registry.decode(listOf(RawToolCall("open_app", mapOf("app_name" to target))))
    }
    private fun blocked(message: String) = ToolPlan(emptyList(), message)
}

data class AppCandidate(val id: String, val name: String, val aliases: List<String> = emptyList())
object AppNames {
    private fun normalize(value: String) = value.lowercase().filterNot(Char::isWhitespace)
    fun matching(name: String, apps: List<AppCandidate>): List<AppCandidate> {
        val input = normalize(name)
        return apps.filter { app -> (listOf(app.name) + app.aliases).any { normalize(it) == input } }.distinctBy { it.id }
    }
}

data class PreparedDeviceCommand(val action: Action.Device, val summary: String, val targetId: String? = null,
                                 val requiresConfirmation: Boolean = false)
sealed interface DeviceCheck {
    data class Ready(val command: PreparedDeviceCommand) : DeviceCheck
    data class Blocked(val message: String, val appChoices: List<AppCandidate> = emptyList()) : DeviceCheck
}
interface DevicePort {
    fun prepare(action: Action.Device, selectedAppId: String? = null): DeviceCheck
    fun canExecute(command: PreparedDeviceCommand): Boolean
    fun dispatch(command: PreparedDeviceCommand): Boolean
}
