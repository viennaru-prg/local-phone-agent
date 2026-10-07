# 목표를 유지하는 실행 구조

## 준비와 빠른 실행 경로

`AgentEngine.prepare`는 원래 발화를 기준으로 Android 도구를 먼저 찾습니다. `BasicCommandPlanner`는 알려진 문법의 빠른 경로이고, 선택적인 FunctionGemma가 함수 계획을 제안할 수 있습니다. 알려진 도구로 계획할 수 없으면 `GoalRequests`가 발화를 `Action.AppTask`로 유지합니다. 앱별 함수를 등록하지 않은 앱도 런처 이름과 관측 UI로 작업할 수 있습니다. 단일 목적지 Intent가 지원하지 않는 경유 명령도 원래 목표를 AppTask로 넘기며, 복잡한 경유 계획의 실제 수행은 로컬 모델과 대상 앱에 달려 있습니다.

`PolicyGate`는 잘못된 시간·실제 이름 충돌·명령 범위를 검사합니다. 장소 부재, 검색 API 미설정, 공식 함수 부재는 정책 거절이 아닙니다. `Ready`는 선택적인 destination/deviceCommands와 원래 navigationGoal/pendingDevices/appTasks를 함께 보존합니다. 장소를 찾지 못했다고 음악만 실행하지 않으며, 실행 중 부분 수행이 발생하면 결과·완료 증거를 구분합니다. `ActionExecutor`는 독립적인 구조화 경로 전용이고 미해결 목표를 완료로 표시하지 않습니다. 실제 Activity는 비동기 `AgentEngine.execute`를 사용합니다.

앱 열기는 설치 공간 내 확정한 launcher Intent로 끝납니다. `지도 켜줘`에는 목적지 resolver·접근성·모델이 필요하지 않습니다. 알람·타이머·설정의 Intent 전달은 완료 증거와 구별하고 열린 앱에서 최종 상태를 확인하도록 표시합니다. 사용할 수 없는 공식 경로는 `GenericUiGoal`로 넘깁니다. 여러 실제 앱 후보의 이름이 충돌하면 선택을 받습니다.

## 지도 목적지 탐색과 검증

UserPlaces의 정확한 장소가 있으면 그 좌표를 네이버 navigation Deep Link로 전달합니다. 로컬 장소·API 키가 없으면 네이버지도를 열어 회사/집 바로가기, 저장 장소, 저장 장소 검색, provider search Deep Link/UI 순으로 시도합니다. 개인 별칭을 공개 REST 검색에 보내 고용주를 추정하지 않습니다. 화면의 공개 검색 결과 ‘회사’는 개인의 회사와 같다고 가정하지 않습니다.

`NaverUiGoal`의 버튼 의미 규칙은 화면 작업의 빠른 경로입니다. 일치하지 않는 화면에서는 선택적인 로컬 UI planner에 다음 동작을 요청합니다. 개인 목적지가 확정되기 전에는 이 보조 경로도 저장 목록·검색·메뉴 탐색으로 제한하여 목적지 정체성을 모델이 추측하지 않게 합니다. 실제 여러 후보만 질문하고 선택한 주소를 재관측해 유지합니다. 모든 자동 경로가 실패했으면 실제 장소명·주소를 요청합니다.

목적지 선택 → 도착/길찾기 → 자동차 경로 → 안내 시작을 시도합니다. start click 수락이나 경로 미리보기는 성공이 아닙니다. 목적지 관측과 start 동작 후 안내 종료 컨트롤·남은 시간/거리 등 주행 상태를 함께 확인해야 `NAVIGATION_ACTIVE_AFTER_START`입니다. 실제 네이버 버전·언어의 접근성 레이블은 실기에서 확인해야 합니다.

성공한 뒤에만 ProviderPlaceCache에 이름·주소 참조를 저장합니다. 참조는 Keystore 기반 암호화 설정이며 유효 기간은 30일입니다. 선택한 목적지 행에 명시된 좌표가 있을 때만 UI_VERIFIED UserPlace를 저장하며 수동 장소를 덮어쓰지 않습니다. 지도의 카메라 중심·모델 생성 좌표는 캐시하지 않습니다. 저장 실패·cache off는 이미 수행한 목표의 성공을 뒤집지 않습니다.

## 실제 화면 실행

`AgentAccessibilityService`는 Android가 바인딩하는 서비스이며, 사용자 동의와 OS 접근성 허용이 모두 있어야 합니다. 서비스는 `isAccessibilityTool=false`로 선언하고 용도를 설명합니다. 자체 허용이나 외부 명령 receiver는 없습니다. `AccessibilityRuntime`의 mutex로 명령 세션을 직렬화하고 세션 ID·취소 job·45초 제한을 둡니다.

서비스는 해당 설치 공간의 대상 앱 UID를 확인합니다. 현재 활성 대상 창, 키보드/본인 접근성 overlay 아래의 대상 창, 본인 음성 호출 pill 아래의 대상 창만 읽습니다. 다른 앱이나 시스템 권한 창 아래의 대상을 조작하지 않습니다. 평상시 이벤트에서는 text를 수집하지 않고 요청 중에만 최대 180개 요소와 제한된 깊이의 일시 관측을 만듭니다. 비밀번호와 민감 노드는 제외합니다.

`UiSession`은 현재 관측의 token만 사용합니다. 텍스트 입력은 목표의 문구로 제한합니다. 클릭/입력/IME submit/스크롤/앱 내부 Back을 지원합니다. live click은 역할·ID·레이블·하위 내용 fingerprint를 재검사하므로 경로가 재사용된 다른 항목을 누르지 않습니다. ACTION_CLICK이 지원되지 않으면 확인한 요소의 실제 bounds 중심에서만 gesture를 시도합니다. 임의 좌표나 화면 밖 터치 계획을 받지 않습니다.

`GenericUiGoal`은 앱별 selector 없이 관측 이름의 직접 선택·검색·음악 컨트롤을 시도합니다. 추가 단계는 로컬 FunctionGemma가 관측 노드만 사용하는 ui_click/ui_set_text/ui_submit/ui_scroll/ui_back을 제안합니다. 자동 tool execution은 꺼 두고 `UiGrounding`과 서비스가 각 제안을 다시 검사합니다. UI 문구의 명령 주입을 권한으로 취급하지 않습니다. 모델의 완료 선언만으로 성공하지 않으며 새로운 실제 완료 표시와 수행 이력을 확인합니다. 현재 가중치가 없으므로 실제 모델의 계획 품질과 CPU 성능은 미검증입니다.

UI 횟수·같은 동작 반복·앱 이탈·잠금을 제한합니다. 작은 OS 접근성 overlay로 진행·실제 모호한 후보·취소를 표시합니다. 사용자가 취소하면 뒤늦은 음성 결과, 클릭, navigation, 캐시를 실행하지 않습니다. 인증·송금·결제·권한 승인은 자동화 범위 밖입니다.

## 음성 호출과 앱 전환

런처 `VoiceInvocationActivity`는 자동 음성 진입이고, 수동 `MainActivity` 홈에는 중앙 마이크·듣기 애니메이션만 있습니다. `VoiceSessionCoordinator`는 기존 세션 lease와 최종 인식 한 번 소비를 유지합니다. `externalExecution`은 명령이 대상 앱으로 화면을 넘기는 동안 onStop 취소를 막고, 작업 완료/실패 때 정리합니다. 듣는 중 사용자가 나가면 마이크를 종료하며 후속 결과는 무시합니다. RegiStar 자체 설정·뒤 두 번 탭 감지는 앱이 강제로 설정하지 않습니다.

기본 한국어 STT는 Android createOnDeviceSpeechRecognizer입니다. ko-KR/FREE_FORM, 최대 5개 후보, partial과 bounded dynamic bias를 요청합니다. 서비스가 반환한 모든 후보와 선택적 confidence를 공통 SpeechRecognitionResult로 보존합니다. 일반 recognizer나 cloud fallback은 만들지 않으며, 기존 bundled sherpa-onnx Zipformer는 사용자가 직접 선택하는 경로로 유지합니다. native 마이크는 시스템 서비스가 처리하고 custom AudioRecord는 기존 모델 또는 명시적 평가 버튼에서만 사용합니다.

SpeechVocabulary는 암호화 UserPlaces, 지원하는 장소 역할, 같은 공간의 관련 앱과 현재 Tool 메타데이터에서 작은 bias list를 만듭니다. 연락처 조회나 권한 추가는 없습니다. ContextualTranscriptResolver는 N-best·Hangul 분해/음성 거리·문장 구조로 결정적인 재평가를 합니다. 특정 오인식 치환과 LLM 보정은 없습니다. 실제 다른 목적지/동작이 경합하거나 confidence가 낮을 때만 질문합니다. 원래 부정·조건 표현, 숫자, 정확한 기존 entity를 함부로 교체하지 않습니다. 결과와 acoustic confidence는 기존 AgentEngine의 정책 확인 전까지 전달합니다.

context 준비 → AudioFocus → 150ms 안정화 → native 시작 → onReadyForSpeech → LISTENING → haptic 순서입니다. 캡처 종료 전에 focus를 반환하며 transport resume은 보내지 않습니다. 기본 duck과 선택적 TRANSIENT pause 전략이 있고 실제 음악/차량 우열은 미측정입니다. partial·취소 후 callback·마이크 준비 전 결과는 작업을 실행하지 않습니다.

음성 진단은 마지막 한 건을 메모리에 둡니다. DEBUG 또는 명시적 opt-in 실행 진단만 암호화 저장합니다. 비공개 평가 Activity는 별도 동의·버튼으로만 녹음하고 내부 noBackupFilesDir에 결과를 저장하며 raw PCM 저장은 기본 해제입니다. 이전 모델과 native bias 0/12/24 및 같은 native 결과의 contextual rescore를 비교하도록 구성했습니다. AUDIO_SOURCE extra의 실제 기기 지원은 검증 대상이며 지원 API만으로 동일 음성 replay 성공을 주장하지 않습니다. 이번 배포의 자연 발화 평가는 사용자 요청으로 생략했습니다.

## 설치 공간, 권한과 배포

현재 Context의 Room·Keystore·설정·앱 조회·미디어 컨트롤을 사용합니다. 다른 프로필 Context, forwarder, 보안폴더 잠금 우회가 없습니다. 보안폴더에서는 대상 앱도 같은 공간에 있어야 합니다. 글로벌 접근성 허용은 앱별 사전설정과 다른 OS 기능 허용 단계입니다. Knox에서 지원하지 않으면 그 제한을 정확히 보고하며 외부 영역으로 바꾸지 않습니다.

`automation`은 debuggable/testOnly가 아니지만 개인 시험판이며 기존 개발 인증서로 서명합니다. 접근성 및 알림 listener를 명시적으로 포함합니다. `install`은 과거 권한 축소판으로 두 기능이 없습니다. 사용자에게 이번 요청의 설치판으로 권하지 않습니다. 실제 Play Protect 설치 승인과 Google Play 접근성 정책 적합성은 확인하지 않았습니다. 기능을 숨기거나 보호를 끄지 않습니다.

화면 원문은 일시 메모리로 처리하고 전송/저장하지 않습니다. 검증된 목적지 캐시는 선택 사항입니다. 마지막 실행 한 건의 암호화 저장은 DEBUG 또는 명시적 opt-in만 허용하며 automation의 기본값은 메모리 진단입니다. 대상 앱의 통신, 선택적 공개 장소 REST API와 사용자 Deep Link는 외부 AI 호출과 구별합니다.

## 검증

core는 목표 보존·빠른 경로 실패·좌표·음성 계획·관측 token/fingerprint 검사를 수행합니다. Android instrumentation은 실제 OS AccessibilityService가 별도의 testOnly 앱의 Views를 누르고 검색·선택·주행 상태를 관측하게 합니다. fixture의 NAVER 패키지는 URI 계약 시험용이며 실제 네이버 앱으로 설명하지 않습니다. shell 접근성 허용은 확인된 전용 AVD의 테스트 코드에만 존재합니다.

최종 APK 해시가 설치판 검사 기록과 일치하는지 패키징에서 확인합니다. 기존 개발판 전체 검사와 nondebug automation 검사 수는 중복 합산하지 않습니다. S25 일반 영역의 과거 USB 설치·사용자 접근성 활성화 및 실제 네이버 집/회사·자주 가는 곳 탭 구조는 확인했습니다. 실제 네이버 안내 시작·Knox·RegiStar·음악 앱의 실제 소리·FunctionGemma inference는 별도 미검증입니다. 0.6.0은 update-verification.json과 automation-test/stt-shipping.json에 기록합니다. 이전 0.5.1은 history/0.5.1에 보존합니다.

## 앱 업데이트

설정 버튼으로 명시적으로 시작한 업데이트는 별도의 ViewModel이 관리합니다. GitHub 최신 정식 Release와 update.json의 versionCode를 확인하고, 새 버전만 제한된 HTTPS 주소에서 다운로드합니다. 기존 서명·패키지·버전·파일 크기·SHA-256 검사를 통과한 APK만 내부 cache/updates에서 FileProvider로 해당 설치 공간의 시스템 설치 프로그램에 전달합니다. 접근성으로 설치 권한이나 최종 승인을 누르지 않습니다. Android의 앱 설치 허용은 사용자에게 요청하며 설치 확인은 OS 화면에 남깁니다. 자세한 계약은 docs/github-updates.md에 있습니다.

## 네이버 개인 장소 영역

NaverPersonalUi는 집/회사 전용 등록, 자주 가는 곳, 저장 장소와 MY/즐겨찾기 진입을 별도로 식별합니다. 화면의 읽을 수 있는 텍스트에서 실제 클릭 가능한 부모를 찾으며 사용자별 장소 설정이나 고정 좌표가 필요하지 않습니다. 미등록 항목과 카테고리 탭은 목적지 행과 구분합니다. 집·회사는 전용 역할을 우선하고 다른 목적지는 자주 가는 곳 등 개인 영역을 시도합니다. 캐시의 이름·주소가 현재 검색과 맞지 않으면 캐시 힌트를 버리고 개인 영역 탐색으로 복귀합니다. 동작·시간 제한과 실제 안내 확인·취소·선택적 캐시 조건은 유지합니다.
