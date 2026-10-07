# 한국어 음성 입력 결과 — 0.6.0

2026-10-08 사용자가 자연 발화 평가를 생략하고 사용·배포하는 것으로 요청을 변경했다. 50개 발화를 요청하거나 평가 화면을 강제로 열지 않는다. 아래 구현 검증과 실제 음성 정확도를 구분한다. 일반 호출은 중앙 마이크/Back Tap으로 사용할 수 있고 평가는 설정의 선택 기능이다.

| 요청한 보고 항목 | 구현·관측과 한계 |
|---|---|
| 1. 기존 STT engine | sherpa-onnx 1.12.20 Korean Zipformer, CPU 2 threads. 수정 전 S25 설치 앱 factory도 LocalSpeechInput |
| 2. 수정 후 STT engine | Android createOnDeviceSpeechRecognizer 기본. 사용자가 기존 포함 모델을 직접 선택 가능. 새 ASR 모델 추가 없음 |
| 3. 실제 on-device 여부 | S25 지원 API와 native 생성 성공은 확인. 일반 createSpeechRecognizer/cloud fallback 없음. 실제 자연 발화 인식은 이번에 미측정 |
| 4. Airplane Mode | S25 native 발화 검사는 생략. AVD의 포함 모델 fixture offline 검사는 별도이며 S25 native 성공으로 해석하지 않음 |
| 5. 한국어 지원 | S25 checkRecognitionSupport installed=[ko-KR], pending=[], online=[], Korean ready=true |
| 6. N-best | 최대 5개 요청, 반환된 전체 후보·rank 보존 및 deterministic rescore. OS의 실제 반환 개수는 기기·발화에 따라 다름 |
| 7. confidence | CONFIDENCE_SCORES 보존. 미제공/-1/NaN/범위 밖은 null; true 0은 유지. 점수와 deterministic evidence score는 구분 |
| 8. contextual bias | API 33 EXTRA_BIASING_STRINGS 전달. API가 있어도 엔진의 실효 반영은 보장하지 않으며 실제 bias A/B는 미측정 |
| 9. dynamic vocabulary | 현재 UserPlaces·지원 장소 역할·관련 설치 앱·Tool 메타데이터. 기본 24개/최대 48개; 연락처는 현재 전화/메시지 Tool과 권한이 없어 조회하지 않음 |
| 10. resolver | Hangul 초중종성·연음/띄어쓰기 비교, 경량 가중 거리, 조사/방향 문장, compatible N-best. 특정 오인식 치환·LLM auto-correct 없음 |
| 11. 발화 시작/종료 | context와 focus 준비 뒤 150ms 안정화, native onReadyForSpeech 뒤 진동. native endpoint 기본값 유지, 취소 watchdog. partial은 실행하지 않음. 첫 음절 손실률은 미측정 |
| 12. 음악 | 기본 TRANSIENT_MAY_DUCK; 선택적 TRANSIENT pause 요청. 캡처 뒤 focus만 해제하며 사용자 정지 뒤 transport resume을 보내지 않음. 실제 Clipstream/차량 우열은 미측정 |
| 13. baseline 정확도 | 미측정 — 사용자 요청으로 50개 자연 발화 생략 |
| 14. 개선 후 정확도 | 미측정. fixture·unit 통과를 정확도 개선률로 보고하지 않음 |
| 15. entity accuracy | 실제 발화 수치 미측정. 명확한 기존 집/회사 entity 유지, 경합·공개 목적지 후보·숫자 변경 금지/질문은 core와 Android에서 검사 |
| 16. intent accuracy | 실제 발화 수치 미측정. 부정·조건과 서로 다른 media action 보존 검사 포함 |
| 17. end-to-end success | 실제 S25 native 명령 성공률 미측정. 평가 모드는 명령을 실행하지 않으며 STT 오류·Tool 미지원·실제 실행 성공을 분리 |
| 18. latency | ready/audio/final/total/resolver 시간 수집 구현. 실제 전후 평균·분위수 미측정 |
| 19. RAM/CPU/battery | 평가용 own-app PSS/CPU, 충전·온도·전류·에너지 조회. native 서비스 비용과 충전 중 배터리 A/B는 판정하지 않음. 실제 비교 미측정 |
| 20. 남은 오류 유형 | natural/noise/차량·고유명사·첫 음절 손실·bias 실효·replay 지원이 미검증이라 잔여 오류 분포를 만들 수 없음 |
| 21. Native 유지 판정 | 요청대로 기본 사용 경로로 배포. S25 ko-KR 설치/지원 확인은 있으나 품질 합격 판정은 유보. 설정에서 기존 엔진 직접 선택 가능 |
| 22. 별도 ASR A/B | 새 모델 추가하지 않음. 실제 사용에서 native 품질이 부족할 때 같은 동의 데이터로 별도 한 가지 축의 비교 판단 |
| 23. 다음 ONE AXIS | 사용자가 원할 때 실제 발화의 baseline/native 동일 음성 비교부터 진행. 현재 버전 사용과 업데이트를 평가에 종속시키지 않음 |

자동 검사는 APK의 N-best Bundle/Intent, ambiguity 후 한 번의 음성 선택, partial/late callback 차단, 실제 AudioFocus callback, 기존 Tool·UI·업데이트 회귀를 검사한다. APK와 실제 검사 해시는 Release의 `update-verification.json`에 기록한다. 실기 설치는 별도 상태이며 가상폰 테스트와 혼동하지 않는다.

일반 호출의 원음 저장은 없다. 진단은 마지막 한 건을 메모리에 두며 영구 실행 진단은 opt-in만 암호화 저장한다. 선택 평가의 결과 기록·원음 저장은 별도 동의이고 raw audio 체크는 기본 해제다. GitHub에는 개인 장소·연락처·사용자 녹음·인식 원문·서명 키를 올리지 않는다.

감사 원문: [stt-audit.md](stt-audit.md). 공식 API: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent), [RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport), [AudioFocusRequest](https://developer.android.com/reference/android/media/AudioFocusRequest).
