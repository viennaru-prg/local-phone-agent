package dev.localphone.testmap

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.widget.*

/** Real, separate Android UI fixture. No navigation/network/audio; never install in place of NAVER on a phone. */
class ReceiverActivity : Activity() {
    private val prefs get() = getSharedPreferences("fixture", 0)
    private val mode get() = prefs.getString("mode", "legacy")!!
    private lateinit var body: LinearLayout
    private var destination = "회사"
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }
    private fun count(key: String) { prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).commit() }
    private fun page(name: String) {
        prefs.edit().putString("screen", name).commit()
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 230, 36, 36) }
        setContentView(ScrollView(this).apply { addView(body) })
        text("실제 네이버지도 앱이 아닌 UI TEST FIXTURE")
    }
    private fun text(value: String) = TextView(this).apply { text = value; textSize = 18f; setPadding(0, 8, 0, 8) }.also(body::addView)
    private fun button(label: String, click: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { click() } }.also(body::addView)
    private fun render() {
        val uri = intent.data; prefs.edit().putString("uri", uri?.toString().orEmpty()).commit()
        if (mode == "legacy") {
            page("legacy")
            listOf("NAVIGATION TEST RECEIVER", "Android Intent 전달 검증 화면", "목적지: ${uri?.getQueryParameter("dname")}",
                "위도: ${uri?.getQueryParameter("dlat")}", "경도: ${uri?.getQueryParameter("dlng")}", "요청 앱: ${uri?.getQueryParameter("appname")}").forEach { text(it) }
            return
        }
        if (uri?.host == "navigation" && uri.getQueryParameter("dname") != null) {
            destination = uri.getQueryParameter("dname")!!; route(); return
        }
        if (uri?.host == "search") { search(uri.getQueryParameter("query").orEmpty(), false); return }
        page("map")
        text("지도")
        button("저장") { count("saved_clicks"); saved() }
        button("검색") { searchInput(false) }
        button("알림 메뉴") { count("generic_clicks"); page("notifications"); text("알림 메뉴"); text("알림 설정 화면") }
    }
    private fun saved() {
        page("saved"); text("저장 장소")
        when (mode) {
            "saved_search" -> { text("저장 목록"); searchInput(true, preserve = true) }
            "public_search", "public_office" -> text("저장한 장소 없음")
            "ambiguous_office" -> { place("회사", "서울시 테스트로 11", 36.111111, 128.111111); place("회사", "서울시 테스트로 22", 36.222222, 128.222222) }
            "saved_office", "failed_navigation", "no_coordinates" -> place("회사", "서울시 테스트로 11", 36.111111, 128.111111)
            else -> text("저장한 장소 없음")
        }
    }
    private fun searchInput(personal: Boolean, preserve: Boolean = false) {
        if (!preserve) page(if (personal) "saved_search_input" else "search_input")
        val input = EditText(this).apply { hint = if (personal) "저장 장소 검색" else "장소 검색"; isSingleLine = true; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        body.addView(input)
        input.setOnEditorActionListener { _, _, _ -> search(input.text.toString(), personal); true }
        button("검색") { search(input.text.toString(), personal) }
    }
    private fun search(query: String, personal: Boolean) {
        count("searches"); prefs.edit().putString("last_query", query).commit()
        page(if (personal) "saved_search_results" else "search_results")
        text(if (personal) "저장 장소 검색 결과" else "검색 결과")
        when {
            mode == "saved_search" && personal && query == "회사" -> place("회사", "서울시 테스트로 11", 36.111111, 128.111111)
            mode == "public_search" && query == "테스트역" -> place("테스트역", "서울시 테스트로 33", 36.333333, 128.333333)
            mode == "public_office" && !personal && query == "회사" -> place("회사", "서울시 공공검색로 99", 36.444444, 128.444444)
            mode == "no_coordinates" && query == "회사" -> place("회사", "서울시 테스트로 11", 36.111111, 128.111111)
            else -> text("검색 결과 없음")
        }
    }
    private fun place(name: String, address: String, latitude: Double, longitude: Double) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isClickable = true; isFocusable = true
            setPadding(18, 18, 18, 18)
            addView(TextView(this@ReceiverActivity).apply { text = name; textSize = 20f })
            addView(TextView(this@ReceiverActivity).apply { text = "주소: $address" })
            if (mode != "no_coordinates") {
                addView(TextView(this@ReceiverActivity).apply { text = "위도: $latitude" })
                addView(TextView(this@ReceiverActivity).apply { text = "경도: $longitude" })
            }
            setOnClickListener {
                destination = name; prefs.edit().putString("place", address).commit()
                page("place"); text(name); text("주소: $address"); button("도착") { route() }
            }
        }
        body.addView(row)
    }
    private fun route() {
        page("route"); text(destination); text("자동차 경로")
        button("안내 시작") {
            count("navigation_starts")
            if (mode != "failed_navigation") {
                page("guidance"); text(destination); text("안내 중 · 남은 거리 4km · 도착 예정 12:30"); button("안내 종료") { page("stopped") }
            }
        }
    }
}
