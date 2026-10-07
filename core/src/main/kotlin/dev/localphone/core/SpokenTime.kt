package dev.localphone.core

/** Normalize spoken numbers only next to a time unit in an alarm/timer request. */
object SpokenTime {
    private val sino = listOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private fun sinoNumber(number: Int): String = buildString {
        val hundreds = number / 100; val tens = number / 10 % 10; val ones = number % 10
        if (hundreds > 0) { if (hundreds > 1) append(sino[hundreds]); append("백") }
        if (tens > 0) { if (tens > 1) append(sino[tens]); append("십") }
        append(sino[ones])
    }
    private val values = buildMap {
        put("영", 0); put("공", 0)
        (1..999).forEach { put(sinoNumber(it), it) }
        val ones = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
        val tens = listOf("", "열", "스무", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔")
        (1..99).forEach {
            val ten = if (it / 10 == 2 && it % 10 > 0) "스물" else tens[it / 10]
            put(ten + ones[it % 10], it)
        }
        put("하나", 1); put("둘", 2); put("셋", 3); put("넷", 4); put("스물", 20)
    }
    private val words = Regex("(?<![가-힣])(" + values.keys.sortedByDescending { it.length }.joinToString("|") + ")\\s*(시간|시|분|초)(?![가-힣])")
    fun normalize(input: String): String {
        if (!input.contains("알람") && !input.contains("타이머")) return input.trim()
        var result = words.replace(input.trim()) { "${values.getValue(it.groupValues[1])}${it.groupValues[2]}" }
        result = result.replace(Regex("(\\d)\\s+(시간|시|분|초)"), "$1$2")
        result = result.replace(Regex("(시작|설정)\\s+해\\s*줘"), "$1해줘")
            .replace(Regex("(맞춰|켜)\\s+줘"), "$1줘")
        return result
    }
}
