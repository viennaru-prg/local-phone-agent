package dev.localphone.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
    private fun w(goal: String) = QuickCommands.parse(goal) as? QuickRequest.Weather

    @Test fun questionsAreRecognized() {
        assertEquals(QuickRequest.Weather(WeatherDay.TODAY, null, WeatherFocus.GENERAL), w("오늘 날씨 어때"))
        assertEquals(QuickRequest.Weather(WeatherDay.TODAY, null, WeatherFocus.GENERAL), w("오늘 날씨 알려 줘"))
        assertEquals(QuickRequest.Weather(WeatherDay.TOMORROW, null, WeatherFocus.RAIN), w("내일 비 와?"))
        assertEquals(QuickRequest.Weather(WeatherDay.TODAY, null, WeatherFocus.RAIN), w("우산 챙겨야 돼?"))
        assertEquals(QuickRequest.Weather(WeatherDay.TODAY, null, WeatherFocus.TEMPERATURE), w("지금 몇 도야"))
        assertEquals(QuickRequest.Weather(WeatherDay.TODAY, null, WeatherFocus.DUST), w("미세먼지 어때"))
        assertEquals(QuickRequest.Weather(WeatherDay.WEEKEND, "부산", WeatherFocus.GENERAL), w("부산 주말 날씨 알려줘"))
        assertEquals(QuickRequest.Weather(WeatherDay.TOMORROW, "서울", WeatherFocus.RAIN), w("내일 서울 비 와?"))
        // Not weather.
        assertNull(w("지금 몇 시야"))
        assertNull(w("볼륨 좀 내려줘"))
        assertTrue(QuickCommands.parse("타이머 1분 맞춰줘") is QuickRequest.Timer)
    }

    private val forecast = """{"current":{"temperature_2m":17.6,"apparent_temperature":16.2,"weather_code":3,"precipitation":0.0},
        "daily":{"time":["2026-10-11","2026-10-12","2026-10-13","2026-10-14","2026-10-15","2026-10-16","2026-10-17"],
        "weather_code":[3,61,1,0,2,63,0],"temperature_2m_max":[21.4,18.2,22,23,22,19,24],"temperature_2m_min":[12.1,13.4,11,10,12,14,11],
        "precipitation_probability_max":[20,80,10,0,10,70,0]}}"""
    private val air = """{"current":{"pm10":42.0,"pm2_5":38.0}}"""
    private val today = LocalDate.of(2026, 10, 11)

    @Test fun answersInPlainKorean() {
        val r = Weather.report(forecast, air)
        assertEquals("지금 수원시 날씨는 흐림, 18도예요. 오늘 최고 21도, 최저 12도, 비 올 확률 20%예요.",
            Weather.say(w("오늘 날씨 어때")!!, r, "수원시", today))
        assertEquals("내일 비 올 확률은 80%예요. 비 예보가 있어요. 우산을 챙기세요.", Weather.say(w("내일 비 와?")!!, r, null, today))
        assertEquals("지금 18도예요. 체감 16도. 오늘 최고 21도, 최저 12도예요.", Weather.say(w("지금 몇 도야")!!, r, null, today))
        assertEquals("지금 미세먼지는 보통(42㎍/㎥), 초미세먼지는 나쁨(38㎍/㎥)이에요. 마스크를 챙기세요.", Weather.say(w("미세먼지 어때")!!, r, null, today))
        // Sunday: this weekend is today only.
        assertEquals("주말은 일요일 흐림, 최고 21도 최저 12도, 비 올 확률 20%예요.", Weather.say(w("주말 날씨 어때")!!, r, null, today))
        assertEquals("이번 주는 최고 24도, 최저 10도예요. 월요일·금요일에 비 소식이 있어요.", Weather.say(w("이번 주 날씨 어때")!!, r, null, today))
    }
}
