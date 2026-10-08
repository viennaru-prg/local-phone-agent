package dev.localphone.core

/** Korean ASR varies auxiliary-verb spacing. Share the request grammar across entry points. */
object NavigationLanguage {
    private const val REQUEST = "(?:네비(?:게이션)?\\s*(?:찍(?:어\\s*줘|어|고)|켜(?:\\s*줘)?|시작(?:해\\s*줘)?)|가자|가\\s*줘|(?:길\\s*)?안내\\s*해\\s*(?:줘|주세요))"
    val whole = Regex("^(.+?)\\s*$REQUEST\\s*$")
    val frame = Regex("^(.+?)(\\s*(?:$REQUEST|가면서|가고).*)$")
    fun simpleEnding(text: String) = Regex("^$REQUEST$").matches(text.trim())
}
