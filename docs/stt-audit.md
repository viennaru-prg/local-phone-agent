# 음성 인식 수정 전 감사 — 2026-10-08

코드와 연결된 S25(SM-S938N, API 37, owner u0)를 먼저 읽고 조사했다. 기기의 설치판은 0.5.0/code 5였고 수정 전 실제 `AgentApplication.createSpeechInput()` 결과는 `LocalSpeechInput`이었다. 제품 STT 코드를 수정하기 전에 같은 서명의 진단 instrumentation으로 지원 API만 조회했다. 마이크·녹음·설정 변경·권한 자동 허용은 하지 않았다.

| 항목 | 수정 전 코드와 관측 |
|---|---|
| 기본 엔진 | 포함된 sherpa-onnx 1.12.20 Korean Zipformer, CPU 2 threads |
| Android 생성 API | 선택 기능에서만 createOnDeviceSpeechRecognizer. createSpeechRecognizer 호출 없음 |
| 실제 native 설정 | Android System Intelligence의 AiAiSpeechRecognitionService (com.google.android.as). OS의 일반 기본 서비스는 GoogleTTSRecognitionService이지만 앱은 이 일반 경로를 생성하지 않음 |
| native 사용 가능 | S25의 isOnDeviceRecognitionAvailable=true, createOnDeviceSpeechRecognizer 성공 |
| 한국어 모델 | checkRecognitionSupport: installed=[ko-KR], pending=[], online=[]; Korean ready=true |
| 비행기 모드 | 감사 당시 airplane_mode=0. 지원 조회는 실제 비행기 모드 인식 성공의 증거가 아님 |
| locale / language model | native ko-KR, FREE_FORM, language detection 없음; 포함 모델 한국어 전용 |
| N-best | native MAX_RESULTS=1, RESULTS_RECOGNITION의 firstOrNull만 사용 |
| confidence | CONFIDENCE_SCORES 미사용 |
| partial | native와 포함 모델 모두 수집하나 단일 문자열; partial로 명령 실행하지 않음 |
| bias | EXTRA_BIASING_STRINGS 미사용 |
| endpoint | native 25초 취소 watchdog, 플랫폼 종료 감지; 포함 모델 4.5초 시작/900ms 후행 침묵/18초 최대 길이 |
| audio capture | 포함 모델은 16kHz PCM16 mono AudioRecord(VOICE_RECOGNITION); native는 시스템 내부 마이크 사용 |
| audio focus | Back Tap coordinator는 TRANSIENT_MAY_DUCK, 종료 전 focus 해제. MainActivity 수동 마이크에는 focus 처리 없음 |
| 음악 | focus duck 요청만 함. Clipstream transport pause/play로 capture를 복원하지 않음. 실제 음악·차량 비교는 아직 하지 않음 |
| haptic | Back Tap coordinator의 onListening callback 뒤 준비 진동. native는 onReadyForSpeech, 포함 모델은 AudioRecord 시작 뒤 callback. 첫 음절 손실률은 미측정 |
| 저장·전송 | raw audio 저장·HTTP 경로 없음. 마지막 호출 trace는 DEBUG 빌드에서만 암호화 저장 |

감사 자료는 개발 PC의 `work/stt-audit-device.json`, `work/stt-native-support.json`에 보관한다. 연락처·UserPlaces·녹음·transcript를 공개 소스나 GitHub에 넣지 않는다. 위 서비스는 **설정과 실제 native API 지원 query**로 확인한 것이며 실제 발화 처리와 비행기 모드 검사는 이후 별도로 평가해야 한다.

공식 API: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent), [RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport). 생성/가용성은 API 31, 언어 지원 조회와 bias strings는 API 33부터 사용한다. confidence는 optional이고 -1/미제공을 unknown으로 보존해야 한다. 종료 침묵 extra는 제조사별로 무시될 수 있어 기본값을 추측해 덮어쓰지 않는다.
