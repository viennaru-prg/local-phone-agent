package dev.localphone.core

/** Hangul decomposition with silent vowel onsets, spacing invariance and weighted phoneme edits. */
object KoreanPhonetics {
    private val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private val vowels = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private val finals = listOf("", "ㄱ", "ㄲ", "ㄱㅅ", "ㄴ", "ㄴㅈ", "ㄴㅎ", "ㄷ", "ㄹ", "ㄹㄱ", "ㄹㅁ", "ㄹㅂ", "ㄹㅅ", "ㄹㅌ", "ㄹㅍ", "ㄹㅎ", "ㅁ", "ㅂ", "ㅂㅅ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ")
    private val near = listOf("ㄱㄲㅋ", "ㄷㄸㅌ", "ㅂㅃㅍ", "ㅅㅆ", "ㅈㅉㅊ", "ㅐㅔ", "ㅒㅖ", "ㅙㅚㅞ")
    fun units(text: String): List<Char> = buildList {
        for (char in PlaceText.normalize(text)) {
            if (char in '가'..'힣') {
                val code = char.code - 0xac00
                val initial = initials[code / 588]
                if (initial != 'ㅇ') add(initial)
                add(vowels[code / 28 % 21]); addAll(finals[code % 28].toList())
            } else add(char)
        }
    }
    fun similarity(a: String, b: String): Float {
        val left = units(a); val right = units(b)
        if (left == right) return 1f
        if (left.isEmpty() || right.isEmpty() || maxOf(left.size, right.size) > 3000) return 0f
        var previous = FloatArray(right.size + 1) { it.toFloat() }
        for (i in left.indices) {
            val row = FloatArray(right.size + 1); row[0] = (i + 1).toFloat()
            for (j in right.indices) {
                val substitution = if (left[i] == right[j]) 0f else if (near.any { left[i] in it && right[j] in it }) .25f else 1f
                row[j + 1] = minOf(row[j] + 1f, previous[j + 1] + 1f, previous[j] + substitution)
            }
            previous = row
        }
        return (1f - previous.last() / maxOf(left.size, right.size)).coerceIn(0f, 1f)
    }
}
