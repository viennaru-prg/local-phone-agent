package dev.localphone.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelUseTest {
    @Test fun everydayCommandsDoNotWarmTheModel() {
        listOf("회사로 안내해줘", "내비게이션 꺼줘", "회사까지 얼마나 걸려", "지금 몇 시야", "볼륨 올려줘", "음악 멈춰줘",
            "더 크로스 틀어줘", "지금 나오는 노래 뭐야", "클립스트림 열어줘", "설정에서 소프트웨어 정보 보여줘",
            "네이버 지도에서 근처 주유소 찾아줘", "수원집으로 안내해 주고 노래 틀어 줘")
            .forEach { assertFalse(ModelUse.likely(it), it) }
    }

    @Test fun openEndedCommandsDo() {
        listOf("유튜브에서 최근 본 영상 다시 틀어줘", "카카오톡에서 엄마한테 온 메시지 읽어줘")
            .forEach { assertTrue(ModelUse.likely(it), it) }
    }
}
