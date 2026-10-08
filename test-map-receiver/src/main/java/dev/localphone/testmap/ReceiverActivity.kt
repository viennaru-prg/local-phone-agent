package dev.localphone.testmap

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*

/** Real, separate Android UI fixture. No navigation/network/audio; never install in place of NAVER on a phone. */
class ReceiverActivity : Activity() {
    private val prefs get() = getSharedPreferences("fixture", 0)
    private val mode get() = prefs.getString("mode", "legacy")!!
    private lateinit var body: LinearLayout
    private var destination = "회사"
    @Suppress("DEPRECATION") override fun onBackPressed() {
        when (prefs.getString("screen", "")) {
            "saved", "home_work", "frequent", "my" -> { count("back_actions"); render() }
            else -> super.onBackPressed()
        }
    }
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
        if (mode == "ai_notes") {
            page("ai_notes_start"); text("테스트 메모")
            button("메모 저장 시작") {
                count("generic_clicks"); page("ai_notes_edit"); text("테스트 메모 내용")
                button("메모 저장") { count("generic_clicks"); page("ai_notes_done"); text("테스트 메모 저장 완료") }
            }
            return
        }
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
        if (mode == "my_favorites") button("MY") { count("my_clicks"); page("my"); button("즐겨찾기") { count("favorites_clicks"); personal("home_work") } }
        button("검색") { searchInput(false) }
        button("알림 메뉴") { count("generic_clicks"); page("notifications"); text("알림 메뉴"); text("알림 설정 화면") }
    }
    private fun saved() {
        page("saved"); text("저장 장소")
        if (mode.startsWith("personal_") || mode.startsWith("frequent_") || mode == "my_favorites") {
            personalTabs()
            if (mode == "personal_priority") place("회사", "서울시 다른즐겨찾기로 99", 36.999999, 128.999999)
            else text("일반 즐겨찾기 목록에는 목적지 없음")
            return
        }
        when (mode) {
            "saved_search" -> { text("저장 목록"); searchInput(true, preserve = true) }
            "public_search", "public_office" -> text("저장한 장소 없음")
            "ambiguous_office" -> { place("회사", "서울시 테스트로 11", 36.111111, 128.111111); place("회사", "서울시 테스트로 22", 36.222222, 128.222222) }
            "saved_office", "failed_navigation", "no_coordinates", "delayed_navigation", "countdown_navigation", "auto_navigation", "wrong_auto_navigation",
            "acknowledged_navigation", "semantic_button_navigation", "hidden_destination_auto_navigation", "wrong_hidden_destination_auto_navigation" -> place("회사", "서울시 테스트로 11", 36.111111, 128.111111)
            else -> text("저장한 장소 없음")
        }
    }
    /** Reproduce the observed unlabelled clickable Compose parent / labelled child shape. */
    private fun composeTab(label: String, detail: String? = null, click: () -> Unit) {
        val target = LinearLayout(this).apply { isClickable = true; isFocusable = true; setPadding(8, 8, 8, 8)
            addView(TextView(this@ReceiverActivity).apply { text = label; textSize = 18f })
            detail?.let { addView(TextView(this@ReceiverActivity).apply { text = it }) }
            setOnClickListener { click() }
        }
        body.addView(target)
    }
    private fun personalTabs() {
        val tabs = HorizontalScrollView(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabs.addView(row)
        for ((label, area) in listOf("집/회사" to "home_work", "자주 가는 곳" to "frequent", "장소" to "saved")) {
            row.addView(LinearLayout(this).apply {
                isClickable = true; isFocusable = true; setPadding(28, 16, 28, 16)
                addView(TextView(this@ReceiverActivity).apply { text = label; textSize = 18f })
                setOnClickListener { count(area + "_clicks"); if (area == "saved") saved() else personal(area) }
            })
        }
        body.addView(tabs)
    }
    private fun personal(area: String) {
        page(area); text("즐겨찾기"); personalTabs()
        if (area == "home_work") {
            if (mode in listOf("personal_priority", "personal_home", "my_favorites")) {
                place("집", "서울시 합성집로 10", 36.101010, 128.101010)
                place("회사", "서울시 전용회사로 20", 36.202020, 128.202020)
            } else {
                composeTab("회사", "등록") { count("registration_clicks"); page("registration"); text("회사 등록"); text("주소 검색") }
                text("회사 등록")
            }
        } else {
            when (mode) {
                "frequent_office" -> place("회사", "서울시 자주가는회사로 30", 36.303030, 128.303030)
                "frequent_named" -> place("테스트치과", "서울시 자주가는치과로 40", 36.404040, 128.404040)
                "frequent_duplicate" -> { place("본가", "서울시 합성본가로 50", 36.505050, 128.505050); place("본가", "서울시 합성본가로 60", 36.606060, 128.606060) }
                "frequent_scroll" -> {
                    repeat(14) { text("합성 장소 $it").apply { height = 130 } }
                    place("테스트치과", "서울시 목록아래로 70", 36.707070, 128.707070)
                }
                else -> text("자주 가는 곳에 목적지 없음")
            }
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
            mode in listOf("public_office", "personal_missing") && !personal && query == "회사" -> place("회사", "서울시 공공검색로 99", 36.444444, 128.444444)
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
        fun start() {
            count("navigation_starts")
            if (mode != "failed_navigation") {
                if (mode == "wrong_auto_navigation" || mode == "wrong_hidden_destination_auto_navigation") destination = "엉뚱한 다른 목적지"
                page("guidance")
                if (mode in listOf("acknowledged_navigation", "semantic_button_navigation", "hidden_destination_auto_navigation", "wrong_hidden_destination_auto_navigation")) {
                    if (mode == "wrong_hidden_destination_auto_navigation") text("목적지: $destination")
                    text("0 km/h"); text("4.2 km"); text("12:30 도착"); button("음성 안내") { }
                } else { text("목적지: $destination"); text("안내 중 · 남은 거리 4km · 도착 예정 12:30"); button("안내 종료") { page("stopped") } }
            }
        }
        if (mode == "delayed_navigation") {
            // Route page is present before its start control appears.
            val pageBody = body
            text("경로 정보 준비 중")
            body.postDelayed({ if (!isFinishing && body === pageBody) button("안내 시작") { start() } }, 3200)
            return
        }
        if (mode == "acknowledged_navigation") {
            body.addView(object : Button(this) {
                override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_CLICK) { count("ignored_accessibility_clicks"); return true }
                    return super.performAccessibilityAction(action, arguments)
                }
            }.apply { text = "안내 시작"; setOnClickListener { count("gesture_taps"); start() } })
            return
        }
        if (mode == "semantic_button_navigation") {
            body.addView(Button(this).apply {
                text = "안내 시작"; isClickable = false
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_UP) { count("gesture_taps"); start() }
                    true
                }
            })
            return
        }
        if (mode == "hidden_destination_auto_navigation" || mode == "wrong_hidden_destination_auto_navigation") {
            // No recognized start button, and the active page hides the destination and End.
            text("잠시 후 경로를 안내할게요")
            val pageBody = body
            body.postDelayed({ if (!isFinishing && body === pageBody) start() }, 3000)
            return
        }
        val startButton = button(if (mode == "countdown_navigation") "안내 시작 (3초)" else "안내 시작") { start() }
        if (mode in listOf("countdown_navigation", "auto_navigation", "wrong_auto_navigation")) {
            val pageBody = body; startButton.isEnabled = false
            fun tick(seconds: Int) {
                if (isFinishing || body !== pageBody) return
                startButton.text = if (mode == "countdown_navigation") "안내 시작 (${seconds}초)" else "${seconds}초 후 안내 시작"
                body.postDelayed({
                    if (body !== pageBody || isFinishing) return@postDelayed
                    if (seconds > 1) tick(seconds - 1)
                    else if (mode == "countdown_navigation") { startButton.text = "안내 시작 (0초)"; startButton.isEnabled = true }
                    else start()
                }, 1000)
            }
            tick(3)
        }
    }
}
