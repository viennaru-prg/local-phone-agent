# Local Phone Agent 0.7.2 — 뒷면 음성·안내 완료 검토

검증 APK: **0.7.2-automation / versionCode 11**, SHA-256 `3e0fea67040df0d15a11f8cc54fd17ad7a1921149df54754406d32bd64f2b3fe`. Android 15/API35 x86_64 전용 AVD, CPU 4 threads. 실제 S25 마이크·RegiStar 센서·NAVER 앱을 재검증한 결과가 아니다.

## 확인된 원인과 변경

사용자 진단에서 Android 온디바이스 STT는 `회사로 안내해 줘`를 정확히 반환했다. 측정 confidence는 `0.0`, 중간 결과는 `회사 → 회사로 → 회사로 안 → 회사로 안내 → 회사로 안내해 줘`로 수렴했다. 이전 resolver는 이 최종 문장을 낮은 점수로 거절해 `STT_AMBIGUITY_CLARIFICATION_ONCE`로 넘어갔다. `NOT_PLANNED`와 추론 시간 미측정은 Qwen 이전에 거절됐다는 증거다. 띄어쓰기만으로 중앙 음성과 자동 호출의 차이를 설명하지 않는다.

0점은 낮은 음향 점수 그대로 보존한다. Android 문서의 점수 없음(-1)으로 바꾸거나 반복 partial을 독립적인 투표로 세지 않는다. native on-device 마이크의 최종 결과이며, 생성/치환하지 않은 단순 길안내이고, N-best의 목적지·동작 충돌이 없으며, 앞선 partial들이 최종 문장으로 일관되게 완성됐을 때만 `NATIVE_ZERO_WITH_STABLE_LITERAL`·`PARTIAL_FINAL_AGREEMENT` 근거로 정상 AI·Policy·실행 검증을 이어간다. 로컬 장소가 없어도 이 판별은 가능하다. 부정/취소·민감한 명령·충돌한 목적지·불안정한 partial·최종 결과 없는 partial은 이 경로를 통과하지 않는다.

중앙 음성과 자동 호출 모두 마이크 준비 callback 뒤에 모델 준비를 시작한다. 이는 콜드 시작의 순서를 맞춘 예방적 개선이며, 이미 모델이 준비돼 있던 사용자 기록의 직접 원인으로 주장하지 않는다. 별칭을 가진 실제 등록 장소와 가상 slot identity를 이중 후보로 만들지 않는다. 안내가 성공한 기존 등록 장소를 별도 slot 캐시에 중복 저장하지 않으며 기존 수동 장소를 변경하지 않는다.

안내 시작은 클릭 가능 플래그뿐 아니라 실제 ACTION_CLICK capability와 관측한 버튼을 사용한다. 클릭이 수락됐어도 같은 활성 버튼이 남으면 새 화면에서 한 번만 실제 gesture를 시도한다. 모든 탭은 관측한 live bounds·label·package·fingerprint를 다시 검사한다. 시간 경과나 누르기 반환값만으로 성공 처리하지 않는다.

요청 목적지가 일치한 경로를 관측한 뒤 주행 화면에서 목적지가 숨겨져도 안내 상태를 확인한다. 속도·남은 거리·도착 시간과 주행 제어/회전 안내가 함께 있어야 하고, 안내 시작/경로 preview와 충돌한 목적지는 성공으로 인정하지 않는다. 실제 안내 상태를 확인하면 UI session과 AI 실행 표시를 종료한다.

## 실제 실행과 시간

동일한 명령을 중앙 음성 callback·실제 텍스트 버튼·자동 호출 callback으로 처리했다. 자동 호출에는 보고된 0점 최종 결과와 같은 7개 partial/시간을 넣었다. 세 경로 모두 실제 Qwen `navigate(destination=회사)` 생성 → 같은 등록 좌표 → 실제 OS 접근성 조작 → fixture의 guidance 관측 → SUCCESS가 통과했다. 센터/텍스트는 클릭 수락 후 반응하지 않는 버튼에 실제 gesture를 사용했고, 자동 호출은 인식 가능한 시작 버튼 없이 자동 시작하고 목적지/종료 버튼을 숨기는 fixture다. 단순 timer 만료를 성공으로 바꾸지 않는다.

| 진입점 | 완료까지 ms | 실제 Qwen 추론 ms | 10초 목표 |
|---|---:|---:|---|
| center | 16282 | 12064 | 목표 초과 |
| text | 6172 | 3349 | 목표 이내 |
| automatic | 9659 | 6388 | 목표 이내 |

음성은 최종 인식(T5) → 작업 완료(T8), 텍스트는 제출 → 완료다. 음성 캡처/STT의 실제 시간은 callback 재현으로 측정하지 않았다. 각 한 번의 AVD 실행이며 S25 지연 분포나 모든 명령이 10초 이내인 상태를 보장하지 않는다. 복잡한 앱 화면의 다단계 모델 추론과 최초 모델 준비는 별도 지연이 있다.

동일한 최종 app APK의 앞선 실행은 center 15969ms (Qwen 12146ms), text 6270ms (Qwen 3323ms), automatic 22857ms (Qwen 19438ms)였다. 이후 변경은 음성 시험을 바꾸지 않고 다른 회귀의 무반응 클릭 횟수 기대값을 최초 클릭+단 한 번 retry로 맞춘 것이다. 앞선 유효한 지연도 함께 보존하며 가장 빠른 값만 선택하지 않는다.

앞선 자동 실행은 Qwen 추론 19,438ms(생성 18,855ms, 입력 계산 581ms), 이후 화면/완료 약 3.4초였다. 매번 같은 14-token 함수 JSON을 실제 생성했으며 단순 UI 완료 오류와 모델 계산 지연을 구분한다. 모델 load 0ms/warm·prefix 재사용 377 tokens 상태에서도 지연 차이가 있었다. S25에서의 CPU 스케줄링·배터리·발열 원인으로 단정하지 않는다.

## 검증 범위

core **133/133**, 동일 최종 app/instrumentation APK의 Android JUnit **95/96**, 실패 **1**, skip 0. 회귀 78/78·양 모델 9/9·나머지 음성/UI/기한/캐시 8/9다. 유일한 실패는 `launcherPlanningSurvivesTemporaryCoverAndFinishesCountdownNavigation`의 기존 10초 assertion이다. 모든 기능 assertion과 실제 SUCCESS를 확인한 뒤 **11,719ms**로 실패했다. 기준/assertion을 낮추거나 JUnit PASS로 재분류하지 않으며 성능 결과는 **FAIL_TARGET_10_SECONDS_ASSERTION_UNCHANGED**다. 모두 소유한 AVD만 사용했고 테스트 중 Wi-Fi/모바일 데이터를 껐다. 두 모델 실제 추론·선택 전환·복구·process 재실행 선택 유지·음성 연결·취소/기한·일반 화면 완료·countdown·무반응 클릭·목적지 숨김 자동 안내·잘못된 목적지 거부를 확인했다.

개발 후보의 순차 실행에서 안내 성공 후 UUID 장소를 slot으로 중복 cache해 다음 텍스트 명령이 AMBIGUOUS가 되는 결함을 발견해 고쳤다. 최종 검사에서는 세 경로 실행 후 기존 장소 한 개가 그대로 유지되는지도 확인했다. 후보의 실패/측정값을 최종 검사 수나 시간에 합산하지 않는다.

실제 S25/arm64/Knox/RegiStar/NAVER UI/Play Protect 승인은 미검증이다. 사용자가 평가를 생략하기로 했으므로 자연 발화 50개 평가를 요구하지 않았다. 릴리스 JSON과 `office-*.json`은 가상폰 fixture/실제 모델의 근거이며 개인 진단 전체를 공개하지 않는다. Android confidence 정의: [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer#CONFIDENCE_SCORES).
