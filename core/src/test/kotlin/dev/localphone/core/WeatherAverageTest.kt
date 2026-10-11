package dev.localphone.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeatherAverageTest {
    private fun w(goal: String) = QuickCommands.parse(goal) as? QuickRequest.Weather

    @Test fun averageQuestions() {
        fun check(goal: String, day: WeatherDay, focus: WeatherFocus, compare: WeatherCompare = WeatherCompare.NONE) {
            val r = w(goal)!!
            assertEquals(Triple(day, focus, compare), Triple(r.day, r.focus, r.compare), goal)
            assertNull(r.place, goal)
        }
        check("오늘 평균 기온 몇 도야", WeatherDay.TODAY, WeatherFocus.AVG_TEMP)
        check("내일 평균 기온 알려줘", WeatherDay.TOMORROW, WeatherFocus.AVG_TEMP)
        check("어제 평균 기온 몇 도였어", WeatherDay.YESTERDAY, WeatherFocus.AVG_TEMP)
        check("이번 주 평균 기온", WeatherDay.WEEK, WeatherFocus.AVG_TEMP)
        check("주말 평균 기온 어때", WeatherDay.WEEKEND, WeatherFocus.AVG_TEMP)
        check("지난주 평균 기온 몇 도였어", WeatherDay.LAST_WEEK, WeatherFocus.AVG_TEMP)
        check("요즘 평균 기온 어때", WeatherDay.RECENT, WeatherFocus.AVG_TEMP)
        check("이번 달 평균 기온", WeatherDay.THIS_MONTH, WeatherFocus.AVG_TEMP)
        check("지난달 평균 기온 몇 도였어", WeatherDay.LAST_MONTH, WeatherFocus.AVG_TEMP)
        check("평년 기온 몇 도야", WeatherDay.TODAY, WeatherFocus.AVG_TEMP, WeatherCompare.NORMAL)
        check("오늘 평년보다 따뜻해?", WeatherDay.TODAY, WeatherFocus.AVG_TEMP, WeatherCompare.NORMAL)
        check("어제보다 따뜻해?", WeatherDay.TODAY, WeatherFocus.AVG_TEMP, WeatherCompare.YESTERDAY)
        check("내일이 오늘보다 추워?", WeatherDay.TOMORROW, WeatherFocus.AVG_TEMP, WeatherCompare.TODAY)
        check("작년 이맘때 기온 어땠어", WeatherDay.TODAY, WeatherFocus.AVG_TEMP, WeatherCompare.LAST_YEAR)
        check("오늘 일교차 커?", WeatherDay.TODAY, WeatherFocus.DIURNAL)
        assertEquals("부산", w("부산 평균 기온 몇 도야")?.place)
    }

    // 10-01 .. 10-17: past 10 days and 7 ahead; means rise 1 degree a day from 10 on 10-01.
    private val today = LocalDate.of(2026, 10, 11)
    private val forecast = run {
        val dates = (0 until 17).map { LocalDate.of(2026, 10, 1).plusDays(it.toLong()) }
        fun list(f: (Int) -> String) = (0 until 17).joinToString(",") { f(it) }
        """{"current":{"temperature_2m":20,"weather_code":1,"precipitation":0},
          "daily":{"time":[${dates.joinToString(",") { "\"$it\"" }}],"weather_code":[${list { "1" }}],
            "temperature_2m_max":[${list { "${15 + it}" }}],"temperature_2m_min":[${list { "${5 + it}" }}],
            "temperature_2m_mean":[${list { "${10 + it}" }}],"precipitation_probability_max":[${list { "0" }}]}}"""
    }
    private fun say(goal: String, r: Weather.Report = Weather.report(forecast)) = Weather.say(w(goal)!!, r, null, today, 15)

    @Test fun averageAnswers() {
        assertEquals("오늘 평균 기온은 약 20.0도예요(최고 25도, 최저 15도).", say("오늘 평균 기온 몇 도야"))
        assertEquals("어제 평균 기온은 약 19.0도였어요(최고 24도, 최저 14도).", say("어제 평균 기온 몇 도였어"))
        assertEquals("오늘 평균 기온은 약 20.0도로 어제(약 19.0도)보다 1.0도 높아요. 최고 기온은 25도로 어제보다 1.0도 높아요. 전체적으로 따뜻해요.",
            say("어제보다 따뜻해?"))
        assertEquals("오늘 일교차는 10도(최고 25도, 최저 15도)로 커요. 아침저녁으로 쌀쌀하니 겉옷을 챙기세요.", say("오늘 일교차 커?"))
        // 2026-10-11 is a Sunday: last week is 9-28..10-4, only 10-01..10-04 are in this data.
        assertEquals(true, say("지난주 평균 기온 몇 도였어").startsWith("지난주(9월 28일~10월 4일) 평균 기온은 약 11.5도였어요."))
        assertEquals(true, say("이번 주 평균 기온").startsWith("앞으로 일주일 평균 기온은 약 23.0도예요."))
        val withNormal = Weather.report(forecast).copy(normal = 16.4)
        assertEquals("오늘 평균 기온은 약 20.0도로 평년(약 16.4도)보다 3.6도 높아요.", say("오늘 평년보다 따뜻해?", withNormal))
        val ly = Weather.Report(0.0, null, 0, 0.0, Weather.report(forecast).days, lastYear = Weather.Day(LocalDate.of(2025, 10, 11), 0, 21.0, 11.0, null, mean = 16.0))
        assertEquals("작년 이맘때(2025년 10월 11일) 평균 기온은 약 16.0도였어요(최고 21도, 최저 11도). 오늘(약 20.0도)은 작년보다 4.0도 높아요.",
            say("작년 이맘때 기온 어땠어", ly))
    }

    @Test fun similarReadsWithTheRightParticle() {
        // Tomorrow as warm as today at its highest: "오늘과 비슷해요", never "오늘보다 비슷해요".
        val flat = forecast.replace("\"temperature_2m_max\":[", "\"temperature_2m_max\":[").let { Weather.report(it) }
        val same = flat.copy(days = flat.days.map { if (it.date == today.plusDays(1)) it.copy(max = 25.2) else it })
        assertEquals(true, Weather.say(w("내일이 오늘보다 추워?")!!, same, null, today, 15).contains("최고 기온은 25도로 오늘과 비슷해요."))
    }

    @Test fun aPartOfTheDayAgainstTheNormalOrAnotherDay() {
        val r = w("내일 아침 기온 평년대비 어때?")!!
        assertEquals(Triple(WeatherDay.TOMORROW, DayPart.MORNING, WeatherCompare.NORMAL), Triple(r.day, r.part, r.compare))
        val withNormals = Weather.report(forecast).copy(normal = 16.4, normalMin = 10.6, normalMax = 21.3)
        // Tomorrow (10-12): low 16, high 26 in this data.
        assertEquals("내일 아침 최저 기온은 약 16.0도로 평년(약 10.6도)보다 5.4도 높아요.", say("내일 아침 기온 평년대비 어때?", withNormals))
        assertEquals("내일 낮 최고 기온은 약 26.0도로 평년(약 21.3도)보다 4.7도 높아요.", say("내일 낮 기온 평년보다 높아?", withNormals))
    }

    @Test fun aPartOfTheDayBetweenTheDayAndTheComparison() {
        val a = w("내일 아침이 오늘 아침보다 추워?")!!
        assertEquals(Triple(WeatherDay.TOMORROW, DayPart.MORNING, WeatherCompare.TODAY), Triple(a.day, a.part, a.compare))
        val b = w("오늘 저녁 기온 어제 저녁보다 어때?")!!
        assertEquals(Triple(WeatherDay.TODAY, DayPart.EVENING, WeatherCompare.YESTERDAY), Triple(b.day, b.part, b.compare))
    }

    @Test fun theNormalIsTheAverageAroundThatDayOfTheYear() {
        val json = """{"daily":{"time":["2020-10-08","2020-10-11","2020-10-20","2021-10-12"],"temperature_2m_mean":[14.0,16.0,8.0,18.0]}}"""
        assertEquals(16.0, Weather.normal(json, LocalDate.of(2026, 10, 11)))
    }
}
