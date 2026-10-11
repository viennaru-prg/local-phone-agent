package dev.localphone.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WeatherHourlyTest {
    private fun w(goal: String) = QuickCommands.parse(goal) as? QuickRequest.Weather
    private fun focus(goal: String) = w(goal)?.focus

    @Test fun manyKindsOfQuestions() {
        assertEquals(WeatherFocus.RAIN_WHEN, focus("내일 비 언제 와"))
        assertEquals(WeatherFocus.RAIN_WHEN, focus("내일 비 몇시에와"))
        assertEquals(WeatherFocus.RAIN_STOP, focus("내일 비 언제 그쳐"))
        assertEquals(WeatherFocus.RAIN_AMOUNT, focus("내일 비 얼마나 와"))
        assertEquals(WeatherFocus.SNOW_AMOUNT, focus("내일 눈 얼마나 와"))
        // "종일" is a time, not a place.
        assertEquals(QuickRequest.Weather(WeatherDay.TOMORROW, null, WeatherFocus.RAIN_WHEN, allDay = true), w("내일 하루 종일 비 와"))
        assertEquals(QuickRequest.Weather(WeatherDay.TOMORROW, null, WeatherFocus.TEMPERATURE, DayPart.MORNING), w("내일 아침 몇 도야"))
        assertEquals(WeatherFocus.WIND, focus("밖에 바람 세?"))
        assertEquals(WeatherFocus.HUMIDITY, focus("오늘 습도 어때"))
        assertEquals(WeatherFocus.UV, focus("자외선 어때"))
        assertEquals(WeatherFocus.SUN, focus("내일 해 언제 떠"))
        assertEquals(WeatherFocus.CLOTHES, focus("내일 뭐 입고 나가"))
        assertEquals(WeatherFocus.CAR_WASH, focus("세차해도 돼?"))
        assertEquals(WeatherFocus.LAUNDRY, focus("빨래 말려도 될까"))
        // Not weather.
        assertNull(w("비행기 표 찾아줘"))
        assertNull(w("눈이 아파"))
        assertNull(w("시원한 노래 틀어줘"))
    }

    // Tomorrow (10-12): rain 9 h-14 h (2 mm an hour), clear before and after.
    private val forecast = run {
        val times = (0 until 48).map { i -> "\"2026-10-${if (i < 24) "11" else "12"}T%02d:00\"".format(i % 24) }
        fun wet(i: Int) = i in 33..37
        val temps = (0 until 48).map { i -> if (i % 24 in 6..10) 12.0 else if (i % 24 in 18..21) 16.0 else 20.0 }
        """{"current":{"temperature_2m":17.6,"apparent_temperature":16.2,"weather_code":3,"precipitation":0.0,"relative_humidity_2m":64,"wind_speed_10m":3.2},
        "hourly":{"time":[${times.joinToString(",")}],"temperature_2m":[${temps.joinToString(",")}],
          "weather_code":[${(0 until 48).joinToString(",") { if (wet(it)) "61" else "1" }}],
          "precipitation_probability":[${(0 until 48).joinToString(",") { if (wet(it)) "80" else "5" }}],
          "precipitation":[${(0 until 48).joinToString(",") { if (wet(it)) "2.0" else "0.0" }}],
          "snowfall":[${(0 until 48).joinToString(",") { "0.0" }}],"relative_humidity_2m":[${(0 until 48).joinToString(",") { "70" }}]},
        "daily":{"time":["2026-10-11","2026-10-12"],"weather_code":[1,61],"temperature_2m_max":[21.4,18.2],"temperature_2m_min":[12.1,11.4],
          "precipitation_probability_max":[5,80],"precipitation_sum":[0.0,10.0],"snowfall_sum":[0.0,0.0],
          "sunrise":["2026-10-11T06:32","2026-10-12T06:33"],"sunset":["2026-10-11T18:02","2026-10-12T18:01"],
          "uv_index_max":[4.1,6.3],"wind_speed_10m_max":[5.0,9.6]}}"""
    }
    private val today = LocalDate.of(2026, 10, 11)
    private fun say(goal: String, hour: Int = 20) = Weather.say(w(goal)!!, Weather.report(forecast), null, today, hour)

    @Test fun answersFromTheHourlyForecast() {
        assertEquals("내일 비는 오전 9시부터 오후 2시쯤까지 올 것 같아요. 우산을 챙기세요.", say("내일 비 언제 와"))
        assertEquals("내일 비는 오후 2시쯤 그칠 것 같아요.", say("내일 비 언제 그쳐"))
        assertEquals("내일 비는 꽤(약 10mm) 올 것 같아요. 우산을 챙기세요.", say("내일 비 얼마나 와"))
        assertEquals("내일 눈 소식은 없어요.", say("내일 눈 얼마나 와"))
        assertEquals("내일 아침 기온은 약 12도예요.", say("내일 아침 몇 도야"))
        assertEquals("내일 해는 오전 6시 33분에 뜨고 오후 6시 1분에 져요.", say("내일 해 언제 떠"))
        assertEquals("내일 자외선 지수는 6, 높음이에요. 선크림을 바르세요.", say("내일 자외선 어때"))
        assertEquals("내일 바람은 초속 10m로 강해요.", say("내일 바람 세?"))
        assertEquals("지금 바람은 초속 3m로 약해요.", say("바람 세?"))
        assertEquals("지금 습도는 64%로 적당해요.", say("습도 어때"))
        assertEquals("내일 최고 18도, 최저 11도라 맨투맨이나 얇은 니트가 좋겠어요. 우산을 챙기세요.", say("내일 뭐 입고 나가"))
        assertEquals("내일 오전 9시쯤 비 소식이 있어 세차는 그 뒤로 미루는 게 좋아요.", say("세차해도 돼?"))
        assertEquals("내일 하루 종일은 아니고, 비는 오전 9시부터 오후 2시쯤까지 올 것 같아요. 우산을 챙기세요.", say("내일 하루 종일 비 와"))
        // Tonight there is no rain left today: no car-wash or laundry warning from this morning's rain.
        assertEquals("오늘 비 소식은 없어요. 비 올 확률은 최대 5%예요.", say("오늘 비 언제 와"))
    }

    @Test fun aLikelyWindowWithoutForecastRainIsSaid() {
        // Tomorrow: a little drizzle after midnight, then 70% from 4 to 8 p.m. with no rain amount forecast.
        val times = (0 until 24).map { "\"2026-10-12T%02d:00\"".format(it) }
        fun at(h: Int, wet: String, dry: String) = if (h in 0..1) wet else dry
        val json = """{"current":{"temperature_2m":20,"weather_code":1,"precipitation":0},
          "hourly":{"time":[${times.joinToString(",")}],"temperature_2m":[${(0 until 24).joinToString(",") { "20" }}],
            "weather_code":[${(0 until 24).joinToString(",") { at(it, "51", "1") }}],
            "precipitation_probability":[${(0 until 24).joinToString(",") { if (it in 16..19) "70" else at(it, "20", "10") }}],
            "precipitation":[${(0 until 24).joinToString(",") { at(it, "0.2", "0.0") }}],"snowfall":[${(0 until 24).joinToString(",") { "0" }}],
            "relative_humidity_2m":[${(0 until 24).joinToString(",") { "60" }}]},
          "daily":{"time":["2026-10-12"],"weather_code":[51],"temperature_2m_max":[25],"temperature_2m_min":[17],
            "precipitation_probability_max":[70],"precipitation_sum":[0.4],"snowfall_sum":[0]}}"""
        assertEquals("내일 비는 자정부터 새벽 2시쯤까지 조금 오고, 오후 4시부터 8시쯤까지는 비 올 확률이 높아요(최대 70%). 우산을 챙기세요.",
            Weather.say(w("내일 비 언제 와")!!, Weather.report(json), null, LocalDate.of(2026, 10, 11), 20))
    }
}
