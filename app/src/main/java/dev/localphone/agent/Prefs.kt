package dev.localphone.agent

import android.content.Context

/** User-editable knowledge and runtime options. Notes are plain sentences given to the AI as hints. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("agent", Context.MODE_PRIVATE)
    private var sessionTestMode = false
    init { sp.edit().remove("test_mode").apply() }

    var notes: String
        get() = sp.getString("notes", null) ?: DEFAULT_NOTES
        set(value) = sp.edit().putString("notes", value).apply()
    val noteLines get() = notes.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }

    /** File name inside Android/data/dev.localphone.agent/files/models/. Empty = first .gguf found. */
    var modelFile: String
        get() = sp.getString("model", "") ?: ""
        set(value) = sp.edit().putString("model", value).apply()
    var threads: Int
        get() = sp.getInt("threads", 6)
        set(value) = sp.edit().putInt("threads", value).apply()
    /** Run the model on the Adreno GPU (OpenCL). Falls back to CPU when off. */
    var gpu: Boolean
        get() = sp.getBoolean("gpu", true)
        set(value) = sp.edit().putBoolean("gpu", value).apply()
    /** Automated test runs: no "remember this path?" prompt and nothing is learned. */
    var testMode: Boolean
        get() = sessionTestMode
        set(value) { sessionTestMode = value }
    /**
     * The model also writes `expect`/`note` after each action. The action is generated first, so these
     * do not change the decision; off (default) = only action + check, several seconds faster per step.
     */
    var withNote: Boolean
        get() = sp.getBoolean("note_v2", false)
        set(value) = sp.edit().putBoolean("note_v2", value).apply()
    /** Let Qwen3 reason briefly (bounded to [thinkChars] characters) before each action. */
    var think: Boolean
        get() = sp.getBoolean("think", false)
        set(value) = sp.edit().putBoolean("think", value).apply()
    var thinkChars: Int
        get() = sp.getInt("think_chars", 240)
        set(value) = sp.edit().putInt("think_chars", value).apply()
    var keepLoadedMinutes: Int
        get() = sp.getInt("keep_minutes", 15)
        set(value) = sp.edit().putInt("keep_minutes", value).apply()
    var speak: Boolean
        get() = sp.getBoolean("speak", true)
        set(value) = sp.edit().putBoolean("speak", value).apply()
    var useRecipes: Boolean
        get() = sp.getBoolean("recipes", true)
        set(value) = sp.edit().putBoolean("recipes", value).apply()

    companion object {
        val DEFAULT_NOTES = """
            # 한 줄에 하나씩, AI에게 주는 힌트입니다. '#'으로 시작하면 무시합니다.
            # 앞에 [키워드, ...]를 붙이면 명령에 그 단어가 있을 때만 AI에게 보여줍니다.
            [안내, 가자, 가줘, 길, 지도, 네비, 내비] 길안내·지도는 네이버 지도 앱을 쓴다. 집, 회사 같은 장소는 네이버 지도의 '길찾기'를 누르면 바로 보이니 검색보다 먼저 확인한다.
            [안내, 가자, 가줘, 길, 지도, 네비, 내비] 네이버 지도에서 목적지를 고른 뒤 경로가 나오면 '안내 시작'을 눌러야 길안내가 시작된다.
            [안내, 가자, 가줘, 길, 지도, 네비, 내비] 네이버 지도가 지난 안내를 이어서 받을지 물으면 '아니요'를 누른다.
            [안내, 가자, 가줘, 길, 지도, 네비, 내비] 네이버 지도가 이미 길안내(주행) 중인데 다른 곳으로 가야 하면, 먼저 뒤로 가기로 안내를 끝낸 뒤 '길찾기'를 연다. 지도 위 도로명 글자는 누르지 않는다.
            [알람, 타이머, 스톱워치, 세계시각] 알람, 타이머, 스톱워치, 세계 시각은 시계 앱에 있다.
            [와이파이, 블루투스, 배터리, 디스플레이, 밝기, 소리, 진동, 알림, 저장공간, 핫스팟] 휴대폰 설정 항목은 설정 앱에 있다.
            [와이파이, 블루투스, 핫스팟, 테더링, 비행기, nfc] 와이파이, 블루투스, 핫스팟, 비행기 탑재 모드는 설정 첫 화면의 '연결' 안에 있다. 스크롤하지 말고 '연결'을 누른다.
            [음악, 노래, 곡, 재생, 틀어, 플레이, 멈춰, 정지, 재생목록, clipstream, 클립스트림] 음악은 ClipStream 앱을 쓴다. 이 앱은 보안 폴더 안에 있어서, 보안 폴더 앱을 열고 그 안의 ClipStream을 누른다.
        """.trimIndent()
    }
}
