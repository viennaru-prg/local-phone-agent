package dev.localphone.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

/** Which day a weather question is about. */
enum class WeatherDay { TODAY, TOMORROW, DAY_AFTER, WEEKEND, WEEK }

/** Part of the day a question names ("내일 아침", "저녁에"). */
enum class DayPart(val label: String, val hours: IntRange) {
    MORNING("아침", 6..10), AFTERNOON("낮", 11..17), EVENING("저녁", 18..21), NIGHT("밤", 22..23)
}

/** What it asks. */
enum class WeatherFocus {
    GENERAL, RAIN, RAIN_WHEN, RAIN_STOP, RAIN_AMOUNT, SNOW, SNOW_AMOUNT, TEMPERATURE, DUST,
    WIND, HUMIDITY, UV, SUN, CLOTHES, CAR_WASH, LAUNDRY
}

/**
 * Weather questions answered from a forecast service, without the screen or the model (the screen agent
 * once opened 빗썸 for "오늘 날씨 알려줘", and a weather app for "내일 비 언제 와"): the day, when rain
 * starts and stops and how much, snow, temperature by part of the day, wind, humidity, UV, sunrise and
 * sunset, what to wear, whether to wash the car or dry the laundry, fine dust.
 */
object Weather {
    fun parse(goal: String): QuickRequest.Weather? {
        val g = goal.trim()
        val words = g.split(Regex("\\s+"))
        // "비" as a word ("비 와", "비가", "비는 언제"), never inside 비행기 or 비밀번호.
        val rain = words.any { Regex("^비(?:$|가|는|도|와|오|올|온|내|소식|언제|몇|얼마|그|좀|많이|\\?)").containsMatchIn(it) } ||
            Regex("우산|소나기|강수|장마|빗").containsMatchIn(g)
        val snow = words.any { Regex("^눈(?:$|이|은|도|와|오|올|온|내|소식|언제|얼마|많이|\\?)").containsMatchIn(it) } &&
            Regex("와|오|올|온|내리|내려|소식|얼마|많이|쌓|언제|몇").containsMatchIn(g) || Regex("적설|폭설|함박눈").containsMatchIn(g)
        val focus = when {
            Regex("미세\\s*먼지|초미세|황사|공기\\s*(?:질|어때|안\\s*좋)").containsMatchIn(g) -> WeatherFocus.DUST
            Regex("해\\s*(?:가\\s*)?(?:뜨|떠|지는|져|언제\\s*(?:떠|져|뜨|지))|일출|일몰|해돋이|해넘이").containsMatchIn(g) -> WeatherFocus.SUN
            Regex("자외선|선크림|썬크림").containsMatchIn(g) -> WeatherFocus.UV
            Regex("세차").containsMatchIn(g) -> WeatherFocus.CAR_WASH
            Regex("빨래").containsMatchIn(g) -> WeatherFocus.LAUNDRY
            Regex("뭐\\s*(?:입|걸치)|옷차림|겉옷|외투|패딩|반팔|긴팔|입고\\s*나가|두껍게|얇게").containsMatchIn(g) -> WeatherFocus.CLOTHES
            snow -> if (Regex("얼마|많이|몇\\s*(?:cm|센티)|적설|쌓").containsMatchIn(g)) WeatherFocus.SNOW_AMOUNT else WeatherFocus.SNOW
            rain -> when {
                Regex("그치|그쳐|그칠|멈춰|멎|그만\\s*(?:와|오)|개").containsMatchIn(g) -> WeatherFocus.RAIN_STOP
                Regex("얼마나|많이|몇\\s*(?:mm|밀리)|강수량|양이").containsMatchIn(g) -> WeatherFocus.RAIN_AMOUNT
                Regex("언제|몇\\s*시|부터|시간대|종일|내내").containsMatchIn(g) -> WeatherFocus.RAIN_WHEN
                else -> WeatherFocus.RAIN
            }
            Regex("바람").containsMatchIn(g) -> WeatherFocus.WIND
            Regex("습도|습해|습하|꿉꿉|눅눅|건조").containsMatchIn(g) -> WeatherFocus.HUMIDITY
            Regex("기온|몇\\s*도|온도|더워|덥|추워|춥|쌀쌀|따뜻|선선|시원").containsMatchIn(g) -> WeatherFocus.TEMPERATURE
            Regex("날씨|일기\\s*예보|기상").containsMatchIn(g) -> WeatherFocus.GENERAL
            else -> return null
        }
        // "몇 도" in a timer or a volume command, "시원한 노래", an air conditioner: not the weather.
        if (focus == WeatherFocus.TEMPERATURE && Regex("볼륨|소리|타이머|알람|에어컨|보일러|난방|냉장고|노래|음악").containsMatchIn(g)) return null
        val day = when {
            Regex("주말|토요일|일요일").containsMatchIn(g) -> WeatherDay.WEEKEND
            Regex("이번\\s*주|일주일|주간").containsMatchIn(g) -> WeatherDay.WEEK
            Regex("모레").containsMatchIn(g) -> WeatherDay.DAY_AFTER
            Regex("내일").containsMatchIn(g) -> WeatherDay.TOMORROW
            else -> WeatherDay.TODAY
        }
        val part = when {
            Regex("아침|오전|출근").containsMatchIn(g) -> DayPart.MORNING
            Regex("점심|낮|오후").containsMatchIn(g) -> DayPart.AFTERNOON
            Regex("저녁|퇴근").containsMatchIn(g) -> DayPart.EVENING
            Regex("밤|새벽").containsMatchIn(g) -> DayPart.NIGHT
            else -> null
        }
        return QuickRequest.Weather(day, place(g), focus, part, allDay = Regex("종일|내내").containsMatchIn(g))
    }

    /** Time words, never a place ("내일 하루 종일 비 와" was looked up as a place called 종일). */
    private val notPlace = Regex("^(?:오늘|내일|모레|글피|지금|현재|이번|다음|주말|주간|이번주|요즘|요새|오전|오후|아침|점심|낮|저녁|밤|새벽|" +
        "하루|종일|하루종일|온종일|내내|밖|바깥|여기|거기|우리|동네|우리동네|날씨|출근|퇴근|출근길|퇴근길|외출|산책|나들이|등산|오늘은|내일은|" +
        "지금은|정말|진짜|혹시|많이|얼마나|언제|계속|아직|벌써|그럼|그러면)$")

    /** "부산 날씨", "내일 서울 비 와?" → 부산, 서울. Null for here. */
    private fun place(g: String): String? {
        val m = Regex("([가-힣]{2,10}?)(?:의|에|은|는)?\\s*(?:오늘|내일|모레|지금|주말|이번\\s*주)?\\s*(?:날씨|기온|미세\\s*먼지|비|눈)").find(g) ?: return null
        return m.groupValues[1].trim().takeIf { !notPlace.matches(it) }
    }

    /** One hour of the forecast. [precipitation] mm, [snowfall] cm. */
    data class Hour(val time: LocalDateTime, val temperature: Double, val code: Int, val rainChance: Int?, val precipitation: Double,
                    val snowfall: Double, val humidity: Int?)

    /** One day of the forecast. [rainSum] mm, [snowSum] cm, [wind] m/s. */
    data class Day(val date: LocalDate, val code: Int, val max: Double, val min: Double, val rainChance: Int?,
                   val rainSum: Double? = null, val snowSum: Double? = null, val sunrise: LocalTime? = null, val sunset: LocalTime? = null,
                   val uv: Double? = null, val wind: Double? = null)

    data class Report(val temperature: Double, val feelsLike: Double?, val code: Int, val precipitation: Double, val days: List<Day>,
                      val pm10: Double? = null, val pm25: Double? = null, val hours: List<Hour> = emptyList(),
                      val humidity: Int? = null, val wind: Double? = null)

    /** The query the app sends (Open-Meteo forecast, wind in m/s). */
    const val FIELDS = "&current=temperature_2m,apparent_temperature,weather_code,precipitation,relative_humidity_2m,wind_speed_10m" +
        "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation,snowfall,relative_humidity_2m" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,snowfall_sum," +
        "sunrise,sunset,uv_index_max,wind_speed_10m_max&wind_speed_unit=ms&timezone=auto&forecast_days=7"

    /** Open-Meteo forecast JSON (current, daily, and hourly when asked for). */
    fun report(forecastJson: String, airJson: String? = null): Report {
        val j = JsonParser.parseString(forecastJson).asJsonObject
        val now = j.getAsJsonObject("current")
        val daily = j.getAsJsonObject("daily")
        fun JsonObject.num(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asDouble
        fun JsonArray?.num(i: Int) = this?.get(i)?.takeUnless { it.isJsonNull }?.asDouble
        fun JsonArray?.int(i: Int) = this?.get(i)?.takeUnless { it.isJsonNull }?.asInt
        fun JsonArray?.time(i: Int) = this?.get(i)?.takeUnless { it.isJsonNull }?.asString?.let { LocalDateTime.parse(it).toLocalTime() }
        val dates = daily.getAsJsonArray("time").map { LocalDate.parse(it.asString) }
        val days = dates.indices.map { i ->
            Day(dates[i], daily.getAsJsonArray("weather_code").int(i) ?: 0, daily.getAsJsonArray("temperature_2m_max").num(i) ?: 0.0,
                daily.getAsJsonArray("temperature_2m_min").num(i) ?: 0.0, daily.getAsJsonArray("precipitation_probability_max").int(i),
                daily.getAsJsonArray("precipitation_sum").num(i), daily.getAsJsonArray("snowfall_sum").num(i),
                daily.getAsJsonArray("sunrise").time(i), daily.getAsJsonArray("sunset").time(i),
                daily.getAsJsonArray("uv_index_max").num(i), daily.getAsJsonArray("wind_speed_10m_max").num(i))
        }
        val hours = j.getAsJsonObject("hourly")?.let { h ->
            val times = h.getAsJsonArray("time")
            (0 until times.size()).map { i ->
                Hour(LocalDateTime.parse(times[i].asString), h.getAsJsonArray("temperature_2m").num(i) ?: 0.0, h.getAsJsonArray("weather_code").int(i) ?: 0,
                    h.getAsJsonArray("precipitation_probability").int(i), h.getAsJsonArray("precipitation").num(i) ?: 0.0,
                    h.getAsJsonArray("snowfall").num(i) ?: 0.0, h.getAsJsonArray("relative_humidity_2m").int(i))
            }
        }.orEmpty()
        val air = airJson?.let { runCatching { JsonParser.parseString(it).asJsonObject.getAsJsonObject("current") }.getOrNull() }
        return Report(now.get("temperature_2m").asDouble, now.num("apparent_temperature"), now.get("weather_code").asInt,
            now.num("precipitation") ?: 0.0, days, air?.num("pm10"), air?.num("pm2_5"), hours,
            now.num("relative_humidity_2m")?.roundToInt(), now.num("wind_speed_10m"))
    }

    /** WMO weather code in plain Korean. */
    fun sky(code: Int): String = when (code) {
        0 -> "맑음"; 1 -> "대체로 맑음"; 2 -> "구름 조금"; 3 -> "흐림"; 45, 48 -> "안개"
        51, 53, 55, 56, 57 -> "이슬비"; 61, 63, 65, 66, 67 -> "비"; 71, 73, 75, 77 -> "눈"
        80, 81, 82 -> "소나기"; 85, 86 -> "눈 소나기"; 95, 96, 99 -> "뇌우"; else -> "알 수 없음"
    }

    private fun rainy(code: Int) = code in 51..67 || code in 80..82 || code in 95..99
    private fun snowy(code: Int) = code in 71..77 || code in 85..86
    private fun deg(v: Double) = "${v.roundToInt()}도"
    private fun umbrella(chance: Int?) = when {
        chance == null -> ""
        chance >= 60 -> " 우산을 챙기세요."
        chance >= 30 -> " 혹시 모르니 작은 우산이 있으면 좋아요."
        else -> ""
    }

    /** "오전 9시", "오후 3시", "밤 12시". */
    private fun hourWord(h: Int): String = when (h) {
        0 -> "자정"; in 1..5 -> "새벽 ${h}시"; in 6..11 -> "오전 ${h}시"; 12 -> "낮 12시"; in 22..23 -> "밤 ${h - 12}시"; else -> "오후 ${h - 12}시"
    }

    /** Korean grades: PM10 30/80/150, PM2.5 15/35/75 ㎍/㎥. */
    fun dustGrade(value: Double, fine: Boolean): String {
        val limits = if (fine) listOf(15.0, 35.0, 75.0) else listOf(30.0, 80.0, 150.0)
        return when { value <= limits[0] -> "좋음"; value <= limits[1] -> "보통"; value <= limits[2] -> "나쁨"; else -> "매우 나쁨" }
    }

    /** An hour counts as rain when some falls or rain is likely with a rainy sky. */
    private fun wet(h: Hour) = h.precipitation >= 0.1 && h.snowfall < 0.05 || (h.rainChance ?: 0) >= 60 && rainy(h.code)

    /** No rain in the forecast amount, but a high chance of it (an evening of 50-75%). */
    private fun likely(h: Hour) = !wet(h) && (h.rainChance ?: 0) >= 50

    /** "오후 4시부터 8시쯤까지는 비 올 확률이 높아요(최대 75%)", or "" when no hour is likely. */
    private fun chanceText(hours: List<Hour>): String {
        val runs = periods(hours, ::likely)
        if (runs.isEmpty()) return ""
        val top = hours.filter(::likely).mapNotNull { it.rainChance }.maxOrNull() ?: 0
        val when_ = runs.joinToString(", ") { span(it) }
        return "${when_}${if (when_.endsWith("까지")) "는" else "에는"} 비 올 확률이 높아요(최대 $top%)"
    }

    /** Runs of rainy hours: (first hour, hour after the last). */
    private fun periods(hours: List<Hour>, wet: (Hour) -> Boolean): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start: Int? = null
        hours.forEachIndexed { i, h ->
            if (wet(h)) { if (start == null) start = h.time.hour }
            else start?.let { out += it to h.time.hour; start = null }
        }
        start?.let { out += it to ((hours.lastOrNull()?.time?.hour ?: 23) + 1) }
        return out
    }

    /** "오전 9시쯤", "오후 4시부터 8시쯤까지" (the second 오후 left out). */
    private fun span(p: Pair<Int, Int>): String {
        if (p.second - p.first <= 1) return "${hourWord(p.first)}쯤"
        val from = hourWord(p.first)
        val to = hourWord(p.second % 24)
        val half = from.substringBefore(" ")
        return "${from}부터 ${if (from.contains(" ") && to.startsWith("$half ")) to.removePrefix("$half ") else to}쯤까지"
    }

    private fun rainAmount(mm: Double): String = when {
        mm < 1 -> "아주 조금(1mm 미만)"; mm < 5 -> "조금(약 ${mm.roundToInt()}mm)"; mm < 20 -> "꽤(약 ${mm.roundToInt()}mm)"
        mm < 50 -> "많이(약 ${mm.roundToInt()}mm)"; else -> "매우 많이(약 ${mm.roundToInt()}mm)"
    }

    private fun windWord(ms: Double) = when { ms < 4 -> "약해요"; ms < 9 -> "조금 불어요"; ms < 14 -> "강해요"; else -> "매우 강해요. 외출에 주의하세요" }

    private fun uvWord(uv: Double) = when {
        uv < 3 -> "낮음"; uv < 6 -> "보통"; uv < 8 -> "높음"; uv < 11 -> "매우 높음"; else -> "위험"
    }

    /** What to wear for the day's warmest temperature (a common Korean guide). */
    private fun clothes(t: Double): String = when {
        t >= 28 -> "반팔과 반바지 같은 얇은 옷"; t >= 23 -> "반팔이나 얇은 셔츠"; t >= 20 -> "얇은 긴팔이나 가디건"
        t >= 17 -> "맨투맨이나 얇은 니트"; t >= 12 -> "자켓이나 가디건"; t >= 9 -> "트렌치코트나 니트"
        t >= 5 -> "코트와 따뜻한 속옷"; else -> "패딩과 목도리"
    }

    /** Rain chance while people are out (7-21 h), from the hours of [date] still ahead; the daily figure without hours. */
    private fun outChance(r: Report, date: LocalDate, fromHour: Int, fallback: Int?): Int? =
        r.hours.filter { it.time.toLocalDate() == date && it.time.hour in maxOf(7, fromHour)..21 }.mapNotNull { it.rainChance }.maxOrNull()
            ?: fallback.takeIf { r.hours.isEmpty() }

    /** The spoken answer. [today]: the local date; [nowHour]: the local hour (for "지금", "그쳐"). */
    fun say(request: QuickRequest.Weather, r: Report, placeName: String?, today: LocalDate, nowHour: Int = 12): String {
        val where = placeName?.let { "$it " }.orEmpty()
        fun dayOf(date: LocalDate) = r.days.firstOrNull { it.date == date }
        val offset = when (request.day) { WeatherDay.TOMORROW -> 1L; WeatherDay.DAY_AFTER -> 2L; else -> 0L }
        val date = today.plusDays(offset)
        val label = when (offset) { 1L -> "내일"; 2L -> "모레"; else -> "오늘" }
        val d = dayOf(date) ?: r.days.firstOrNull() ?: return "날씨 예보를 받지 못했어요."
        // The hours that matter: the rest of today, or the whole of that day; narrowed to a part of it.
        val dayHours = r.hours.filter { it.time.toLocalDate() == date && (offset > 0 || it.time.hour >= nowHour) }
            .filter { h -> request.part?.let { h.time.hour in it.hours } ?: true }
        val partLabel = request.part?.let { " ${it.label}" }.orEmpty()
        // Umbrella advice from the hours one is out, not a shower at 1 a.m.
        val outRain = outChance(r, date, if (offset == 0L) nowHour else 0, d.rainChance)
        fun summary(lbl: String, x: Day) =
            "$lbl ${sky(x.code)}, 최고 ${deg(x.max)} 최저 ${deg(x.min)}${x.rainChance?.let { ", 비 올 확률 $it%" }.orEmpty()}"
        return when (request.focus) {
            WeatherFocus.DUST ->
                if (r.pm10 == null && r.pm25 == null) "미세먼지 정보를 받지 못했어요."
                else "지금 ${where}미세먼지는 ${r.pm10?.let { "${dustGrade(it, false)}(${it.roundToInt()}㎍/㎥)" } ?: "정보 없음"}, " +
                    "초미세먼지는 ${r.pm25?.let { "${dustGrade(it, true)}(${it.roundToInt()}㎍/㎥)" } ?: "정보 없음"}이에요." +
                    if (listOfNotNull(r.pm10?.let { dustGrade(it, false) }, r.pm25?.let { dustGrade(it, true) }).any { it.contains("나쁨") }) " 마스크를 챙기세요." else ""

            WeatherFocus.RAIN_WHEN -> {
                val runs = periods(dayHours, ::wet)
                val chance = chanceText(dayHours)
                when {
                    runs.isEmpty() && chance.isNotEmpty() -> "$label$partLabel ${where}비는 예보되지 않았지만 $chance.".replace("  ", " ") + umbrella(outRain)
                    runs.isEmpty() -> "$label$partLabel ${where}비 소식은 없어요.".replace("  ", " ") +
                        (d.rainChance?.takeIf { it > 0 }?.let { " 비 올 확률은 최대 $it%예요." } ?: "")
                    offset == 0L && runs.first().first <= nowHour -> "지금 ${where}비가 오고 있어요. " +
                        "${hourWord(runs.first().second % 24)}쯤 그칠 것 같아요." + (runs.drop(1).takeIf { it.isNotEmpty() }?.let { " ${it.joinToString(", ") { p -> span(p) }}에 또 올 것 같아요." } ?: "")
                    else -> {
                        val wetHours = runs.sumOf { it.second - it.first }
                        val lead = if (request.allDay) (if (wetHours >= 12) "하루 종일 올 것 같아요. " else "하루 종일은 아니고, ") else ""
                        val night = runs.all { it.second <= 7 || it.first >= 22 } && chance.isEmpty()
                        val light = dayHours.filter(::wet).sumOf { it.precipitation } < 1.0
                        "$label$partLabel ${where}${lead}비는 ${runs.joinToString(", ") { span(it) }} ${if (light) "조금 " else ""}".replace("  ", " ") +
                            (if (chance.isEmpty()) "올 것 같아요." else "오고, $chance.") +
                            if (night) (if (offset == 0L) " 밤사이 잠깐 내리고 그칠 것 같아요." else " 밤사이에 내리고 낮에는 그칠 것 같아요.") else umbrella(outRain)
                    }
                }
            }

            WeatherFocus.RAIN_STOP -> {
                val runs = periods(dayHours, ::wet)
                when {
                    offset == 0L && (rainy(r.code) || r.precipitation > 0.0) -> {
                        val now = runs.firstOrNull { it.first <= nowHour }
                        if (now == null) "지금 ${where}비가 오고 있는데, 곧 그칠 것 같아요."
                        else "지금 ${where}비는 ${hourWord(now.second % 24)}쯤 그칠 것 같아요." +
                            (runs.filter { it.first > nowHour }.takeIf { it.isNotEmpty() }?.let { " ${it.joinToString(", ") { p -> span(p) }}에 다시 올 것 같아요." } ?: "")
                    }
                    offset == 0L -> "지금 ${where}비는 오지 않아요." + (runs.firstOrNull()?.let { " ${span(it)} 올 것 같아요." } ?: "")
                    runs.isEmpty() -> "$label ${where}비 소식은 없어요."
                    else -> "$label ${where}비는 ${hourWord(runs.last().second % 24)}쯤 그칠 것 같아요." +
                        chanceText(dayHours.filter { it.time.hour >= runs.last().second }).takeIf { it.isNotEmpty() }?.let { " 다만 $it." }.orEmpty()
                }
            }

            WeatherFocus.RAIN_AMOUNT -> {
                val mm = if (request.part != null) dayHours.sumOf { it.precipitation } else d.rainSum ?: dayHours.sumOf { it.precipitation }
                if (mm < 0.1) "$label$partLabel ${where}비 소식은 없어요.".replace("  ", " ")
                else "$label$partLabel ${where}비는 ${rainAmount(mm)} 올 것 같아요.".replace("  ", " ") + umbrella(outRain)
            }

            WeatherFocus.SNOW_AMOUNT -> {
                val cm = d.snowSum ?: dayHours.sumOf { it.snowfall }
                if (cm < 0.1) "$label ${where}눈 소식은 없어요."
                else "$label ${where}눈은 약 ${if (cm < 1) "1cm 미만" else "${cm.roundToInt()}cm"} 쌓일 것 같아요." +
                    (periods(dayHours) { it.snowfall >= 0.05 }.takeIf { it.isNotEmpty() }?.let { " ${it.joinToString(", ") { p -> span(p) }} 내릴 것 같아요." } ?: "")
            }

            WeatherFocus.SNOW ->
                if (offset == 0L) (if (snowy(r.code)) "지금 ${where}눈이 오고 있어요." else "지금 ${where}눈은 오지 않아요.") +
                    (if (snowy(d.code) || (d.snowSum ?: 0.0) > 0) " 오늘 중에 눈 소식이 있어요." else "")
                else if (snowy(d.code) || (d.snowSum ?: 0.0) > 0) "$label ${where}눈 예보가 있어요." else "$label ${where}눈 예보는 없어요."

            WeatherFocus.TEMPERATURE -> when {
                request.part != null && dayHours.isNotEmpty() ->
                    "$label${partLabel} ${where}기온은 약 ${deg(dayHours.map { it.temperature }.average())}예요."
                offset == 0L -> "지금 ${where}${deg(r.temperature)}예요." + (r.feelsLike?.let { " 체감 ${deg(it)}." } ?: "") +
                    " 오늘 최고 ${deg(d.max)}, 최저 ${deg(d.min)}예요."
                else -> "$label ${where}최고 ${deg(d.max)}, 최저 ${deg(d.min)}예요."
            }

            WeatherFocus.WIND -> {
                val ms = if (offset == 0L && request.part == null) r.wind else d.wind
                if (ms == null) "바람 정보를 받지 못했어요."
                else "${if (offset == 0L && request.part == null) "지금" else label} ${where}바람은 초속 ${ms.roundToInt()}m로 ${windWord(ms)}."
            }

            WeatherFocus.HUMIDITY -> {
                val h = if (offset == 0L && request.part == null) r.humidity else dayHours.mapNotNull { it.humidity }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                if (h == null) "습도 정보를 받지 못했어요."
                else "${if (offset == 0L && request.part == null) "지금" else "$label$partLabel"} ${where}습도는 $h%로 ${when { h < 40 -> "건조해요"; h < 70 -> "적당해요"; else -> "습해요" }}."
            }

            WeatherFocus.UV -> d.uv?.let { uv ->
                "$label ${where}자외선 지수는 ${uv.roundToInt()}, ${uvWord(uv)}이에요." + if (uv >= 6) " 선크림을 바르세요." else ""
            } ?: "자외선 정보를 받지 못했어요."

            WeatherFocus.SUN -> if (d.sunrise == null || d.sunset == null) "일출·일몰 정보를 받지 못했어요."
                else "$label ${where}해는 ${clock(d.sunrise)}에 뜨고 ${clock(d.sunset)}에 져요."

            WeatherFocus.CLOTHES -> {
                val warm = if (request.part != null && dayHours.isNotEmpty()) dayHours.maxOf { it.temperature } else d.max
                val wear = clothes(warm)
                val c = wear.last()
                "$label ${where}최고 ${deg(d.max)}, 최저 ${deg(d.min)}라 $wear${if (c in '가'..'힣' && (c - '가') % 28 != 0) "이" else "가"} 좋겠어요." +
                    (if (d.max - d.min >= 10) " 일교차가 커서 겉옷을 챙기세요." else "") + umbrella(outRain)
            }

            WeatherFocus.CAR_WASH -> {
                val next = r.days.filter { !it.date.isBefore(today) }.take(3)
                // Today counts only for the hours still ahead (the morning's drizzle is over by evening).
                val wetDay = next.firstOrNull { x ->
                    if (x.date == today && r.hours.isNotEmpty()) r.hours.any { it.time.toLocalDate() == today && it.time.hour >= nowHour && (wet(it) || it.snowfall >= 0.05) }
                    else rainy(x.code) || snowy(x.code) || (x.rainChance ?: 0) >= 50
                }
                if (wetDay == null) "${where}앞으로 사흘 동안 비 소식이 없어 세차하기 좋아요."
                else {
                    val first = r.hours.firstOrNull { it.time.toLocalDate() == wetDay.date && (wetDay.date != today || it.time.hour >= nowHour) && wet(it) }
                    "${dayName(wetDay.date, today)} ${first?.let { "${hourWord(it.time.hour)}쯤 " }.orEmpty()}비 소식이 있어 세차는 그 뒤로 미루는 게 좋아요."
                }
            }

            WeatherFocus.LAUNDRY -> {
                val wetToday = if (r.hours.isNotEmpty()) dayHours.any(::wet) else rainy(d.code) || (d.rainChance ?: 0) >= 50
                val humid = (r.humidity ?: 50) >= 70
                when {
                    wetToday -> "$label ${where}${dayHours.firstOrNull(::wet)?.let { "${hourWord(it.time.hour)}쯤 " }.orEmpty()}비 소식이 있어 빨래는 실내에 너는 게 좋아요."
                    humid -> "$label ${where}비는 없지만 습해서 빨래가 잘 안 마를 수 있어요."
                    else -> "$label ${where}${sky(d.code)}이고 비 소식이 없어 빨래 말리기 좋아요."
                }
            }

            WeatherFocus.RAIN -> when (request.day) {
                WeatherDay.TODAY -> (if (rainy(r.code) || r.precipitation > 0.0) "지금 ${where}비가 오고 있어요." else "지금 ${where}비는 오지 않아요.") +
                    (d.rainChance?.let { " 오늘 비 올 확률은 $it%예요." + umbrella(it) } ?: "")
                WeatherDay.TOMORROW, WeatherDay.DAY_AFTER -> "$label ${where}비 올 확률은 ${d.rainChance ?: 0}%예요." +
                    (if (rainy(d.code)) " ${sky(d.code)} 예보가 있어요." else "") +
                    umbrella(outRain).ifEmpty { if ((outRain ?: 0) < 30) " 우산은 필요 없어 보여요." else "" }
                else -> general(request, r, where, today)
            }

            WeatherFocus.GENERAL -> when (request.day) {
                WeatherDay.TODAY -> "지금 ${where}날씨는 ${sky(r.code)}, ${deg(r.temperature)}예요." +
                    " 오늘 최고 ${deg(d.max)}, 최저 ${deg(d.min)}${d.rainChance?.let { c -> ", 비 올 확률 $c%" }.orEmpty()}예요." + umbrella(d.rainChance)
                WeatherDay.TOMORROW, WeatherDay.DAY_AFTER -> "${summary("$label $where".trim(), d)}예요." + umbrella(outRain)
                else -> general(request, r, where, today)
            }
        }
    }

    /** Weekend and week overviews. */
    private fun general(request: QuickRequest.Weather, r: Report, where: String, today: LocalDate): String {
        fun dayOf(date: LocalDate) = r.days.firstOrNull { it.date == date }
        fun summary(lbl: String, x: Day) =
            "$lbl ${sky(x.code)}, 최고 ${deg(x.max)} 최저 ${deg(x.min)}${x.rainChance?.let { ", 비 올 확률 $it%" }.orEmpty()}"
        return if (request.day == WeatherDay.WEEKEND) {
            // This weekend: today counts when it is Saturday or Sunday.
            var sat = today
            while (sat.dayOfWeek != DayOfWeek.SATURDAY && sat.dayOfWeek != DayOfWeek.SUNDAY) sat = sat.plusDays(1)
            val weekend = listOf(sat, sat.plusDays(1)).filter { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
                .mapNotNull { date -> dayOf(date)?.let { date to it } }
            if (weekend.isEmpty()) "주말 예보를 받지 못했어요."
            else "${where}주말은 " + weekend.joinToString(", ") { (date, x) -> summary(if (date.dayOfWeek == DayOfWeek.SATURDAY) "토요일" else "일요일", x) } + "예요." +
                umbrella(weekend.mapNotNull { it.second.rainChance }.maxOrNull())
        } else {
            val wetDays = r.days.filter { rainy(it.code) || (it.rainChance ?: 0) >= 60 }
            "${where}이번 주는 최고 ${deg(r.days.maxOf { it.max })}, 최저 ${deg(r.days.minOf { it.min })}예요. " +
                if (wetDays.isEmpty()) "비 소식은 없어요." else "${wetDays.joinToString("·") { names[it.date.dayOfWeek.value - 1] + "요일" }}에 비 소식이 있어요."
        }
    }

    private val names = listOf("월", "화", "수", "목", "금", "토", "일")

    private fun dayName(date: LocalDate, today: LocalDate) = when (date) {
        today -> "오늘"; today.plusDays(1) -> "내일"; today.plusDays(2) -> "모레"; else -> names[date.dayOfWeek.value - 1] + "요일"
    }

    private fun clock(t: LocalTime): String {
        val h = t.hour % 12
        return "${if (t.hour < 12) "오전" else "오후"} ${if (h == 0) 12 else h}시${if (t.minute > 0) " ${t.minute}분" else ""}"
    }
}
