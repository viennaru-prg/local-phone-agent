# Local Phone Agent 0.7.0 — 듀얼 내장 모델 검증 결과

최종 배포 APK `0.7.0-automation`, versionCode **9**. 검증 대상은 task 소유 Android 15/API35 x86_64 AVD `LocalPhoneAgent_API35`이며, 두 모델은 실제 네이티브 엔진에서 실행했다. 모델 검사 중 Wi-Fi와 모바일 데이터를 껐다. S25 Ultra 설치·성능·Knox는 **NOT_VERIFIED**다.

## 1. 정확한 모델 출처와 버전

- [litert-community/functiongemma-270m-ft-mobile-actions](https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions) — revision `f752a74080682b379823794defdbbdf8c2663609`, CPU dynamic INT8, ekv1024
- [bartowski/Qwen_Qwen3-1.7B-GGUF](https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF) — revision `dcb19155b962dbb6389f4691a982043a8e651022`, Q4_K_M (bartowski imatrix, upstream llama.cpp b5200)

Qwen 원본은 [Qwen/Qwen3-1.7B](https://huggingface.co/Qwen/Qwen3-1.7B)이다. [공식 GGUF](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF)의 Q8_0 대신 출처 카드·revision·LFS SHA-256이 확인된 bartowski Q4_K_M을 선택했다. 양자화 원본 도구는 llama.cpp b5200이며 앱의 실행 런타임 revision과 구분한다.

FunctionGemma는 계정의 Mobile Actions 모델 약관 승인을 확인한 뒤 공식 서버에서 받았다. 기본 Google FunctionGemma 저장소의 별도 권한을 우회하지 않았다. [Gemma 약관](https://ai.google.dev/gemma/terms), [금지 사용 정책](https://ai.google.dev/gemma/prohibited_use_policy), NOTICE를 APK에 넣고 첫 사용에 동의를 받는다. Qwen Apache-2.0와 llama.cpp MIT 및 양자화 출처 카드도 포함한다.

기존 0.6 APK 감사: 한국어 STT 가중치는 있었으나 Agent LLM 가중치는 없었다. 수동 FunctionGemma 파일 선택 전에는 규칙/화면 경로로 처리했고 단순 명령은 규칙 경로를 먼저 사용했다. 이번 판은 선택된 모델의 실제 추론을 먼저 시도한다. 감사 상세는 [dual-model-audit.md](dual-model-audit.md)에 있다.

## 2. 실제 내장 파일·크기·SHA-256

**FunctionGemma 270M**

- `mobile_actions_q8_ekv1024.litertlm`
- 288,964,608 bytes (275.58 MiB)
- SHA-256 `33e295cbd996b419bb1de8f3f85c5b6b01ee058a2c89bdb2173cf3e6ff4ce9d0`

**Qwen3 1.7B**

- `Qwen_Qwen3-1.7B-Q4_K_M.gguf`
- 1,282,439,584 bytes (1223.03 MiB)
- SHA-256 `72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb`

Gradle preBuild는 모델 누락·크기·SHA-256 불일치 및 라이선스 누락을 실패로 처리한다. 두 파일은 APK의 **압축하지 않은 assets**에 실제 포함되어 있고 Android 테스트에서 두 파일 전체를 읽어 SHA-256을 다시 확인했다. 링크나 다운로드 stub을 모델 파일로 계산하지 않았다.

## 3. 실행 엔진과 공통 경로

FunctionGemma: **LiteRT-LM Android 0.10.2, CPU/XNNPACK, 4 threads, 1024 context tokens**. Qwen: **llama.cpp `bd4eeaa047006cb1fe71999fbd11134b5836e167`, JNI/CPU, 4 threads, 4096 context tokens**, GPU offload 0. APK에는 arm64-v8a와 x86_64 JNI 라이브러리를 포함한다. 실제 실행 ABI는 x86_64이고 arm64의 실단말 실행은 미검증이다.

`LocalAgentModel`의 load/infer/cancel/unload/info → 공통 Tool Plan → PlanGrounding → Policy Gate → 기존 Android/Accessibility 실행기. 두 모델의 canonical phone Tool 9개와 UI Tool 7개는 동일하다. FunctionGemma의 지도/설정 함수는 학습된 native 함수 이름으로 표현하고 실제 생성된 인자를 공통 형식으로 변환한다. Qwen은 동일 catalog에서 만든 GBNF로 JSON 함수 형식만 제한한다. 어느 adapter도 정답 JSON이나 누락된 행동을 만들어 채우지 않는다.

출력 한도: 공통 함수 계획은 최대 6 calls. Qwen phone 생성은 최대 384 tokens, UI 160, probe 96. LiteRT-LM 0.10.2에는 개별 최대 출력 token 설정 API가 없어 FunctionGemma는 총 1024-token context 제한을 사용한다. 같은 의미 catalog·동일한 공통 힌트를 사용하지만 tokenizer/context/output 예산은 정확히 동일하지 않다.

## 4. APK 전체 크기

**1,808,165,801 bytes (1.808 GB / 1.684 GiB)**.

SHA-256: `ca0dc8cc42f823dc447bc10284801974317268d68a3c535e370c64423206f8a3`. 사용자 설치 파일은 `LocalPhoneAgent-dual-model.apk` 하나다. 테스트 instrumentation APK는 배포하지 않는다. 동일한 기존 서명·일반 설치 가능 non-debug/non-testOnly automation 패키지를 사용한다.

기존 0.6 이하 updater의 512 MiB 제한 때문에 같은 Release에 작은 **준비판 versionCode 8**과 `update.json`을 별도로 둔다. 이전 앱은 준비판 설치 후 다시 업데이트 버튼을 눌러 최종판 9와 `update-dual.json`을 받는다. 준비판에는 Agent LLM이 없으며 직접 설치용 최종 앱으로 안내하지 않는다. 두 단계 모두 Android 설치 승인이 필요하다.

## 5. 설치 후 실제 저장공간

두 모델을 한 번씩 사용한 뒤 앱 native 세션을 닫고 AVD에서 측정했다. Android StorageStats(캐시를 포함하는 dataBytes 정의이므로 항목을 무조건 합하지 않음): `{"app_bytes": 1818062848.0, "data_bytes": 1844785152.0, "cache_bytes": 364544.0}`.

- `base_apk_bytes`: 1,808,165,801 bytes
- `private_no_backup_bytes`: 1,844,091,984 bytes
- `private_model_weights_bytes`: 1,571,404,192 bytes
- `private_cache_bytes`: 317,120 bytes
- `native_libraries_bytes`: 0 bytes

APK와 추출 사본은 서로 다른 실제 파일이다. 처음 선택한 모델만 전용 비백업 디렉터리로 추출하고 두 모델을 모두 사용한 뒤에는 둘의 검증된 디스크 사본이 남는다. RAM에는 하나만 로드한다. 1 MiB streaming·SHA 검사·fsync·atomic rename·128 MiB 여유 공간 확인을 사용한다. 중단된 추출의 .part 삭제 및 손상된 사본을 APK에서 재추출하는 실제 테스트가 PASS했다. 새 revision에서는 이전 사본과 이전 XNNPACK cache를 정리한다. 업데이트 다운로드 전에는 파일 및 Android 설치 staging을 위해 약 2×APK+128 MiB의 추가 여유 공간을 확인한다. 기존 개인 설정을 지우지 않는다.

## 6. FunctionGemma 실제 추론

**PASS — 실제 로드 및 오프라인 English Wi-Fi tool probe**. 실제 출력 `open_wifi_settings()` → canonical `open_settings(page=wifi)`를 생성했다. 네이티브 생성 결과와 시간은 원본 JSON 기록에 있다. **한국어 11개 전체 Tool Plan은 0/11**로, 한국어 Agent 품질의 PASS를 주장하지 않는다. Mobile Actions fine-tune의 학습 범위는 제한되어 있으며 범용 GUI 능력도 미검증이다. 모델이 거절/잘못된 인자를 내면 공통 decoder가 거부하고 기존 처리 결과는 `RULE_BASED`로 표시한다. 기본 모델은 요청대로 FunctionGemma다.

## 7. Qwen 실제 추론

**PASS — 실제 Qwen 로드·오프라인 한국어 계획·Android 실행**. 한국어 Tool Plan 10/11. `네이버지도 켜줘`의 실제 모델 계획 → 공통 정책 → Android launcher → 관측 foreground package까지 확인했다. 지도 대상은 NAVER 패키지를 가진 별도 테스트 앱이므로 실 NAVER 주행 성공으로 계산하지 않는다.

추가 실제 경로: 합성 한국어 PCM → 포함 sherpa-onnx Android decoder → 원문 `오전 7시 알람 맞춰 줘` → 선택된 Qwen 실제 생성 → `set_alarm(7,0)` → Policy → 실제 AVD Clock 앱. `MODEL_INFERENCE:qwen3` 및 실제 Android Intent/foreground 증거를 기록했고 PASS했다. 이 검사는 실제 마이크 자연 발화 및 S25 기본 native STT 검사가 아니다. STT 엔진은 모델 선택 때문에 바뀌지 않는다.

## 8. 전환·취소·재시작

**PASS — FunctionGemma → Qwen → FunctionGemma**. 기존 native 세션을 해제한 뒤 새 모델을 로드하고 실제 probe 성공 후 설정을 저장했다. 동시에 두 adapter를 메모리에 유지하지 않았고 검사 중 OOM이 없었다. 초기화 실패 주입은 실제 이전 Qwen unload 이후에 실시했고 Qwen을 다시 실제 로드·추론해 복구했다. 고정 응답으로 복구 성공을 만들지 않았다. 진행 중 invocation에서는 전환을 거부했다.

| 선택 | 총 전환 ms | 추출 ms | native load ms | 실제 probe ms |
|---|---:|---:|---:|---:|
| FUNCTIONGEMMA | 432 | 4 | 123 | 286 |
| QWEN3 | 23,797 | 8,024 | 3,841 | 11,572 |
| FUNCTIONGEMMA | 2,542 | 1 | 456 | 1,981 |

실제 native 추론 취소 후 join **108ms**, 다음 실제 명령 생성 PASS. 마지막 Qwen 선택은 encrypted setting/새 manager뿐 아니라 **별도 Android process 종료·재실행 후 UI의 선택된 RadioButton**으로 확인했다. 휴대폰 재부팅 및 Knox 재시작은 미검증이다.

## 9. 한국어 명령 정확도

양쪽에 동일한 11개 명령 텍스트·동일한 빈 로컬 장소 상태·동일한 권한·동일한 의미 catalog·같은 시스템 힌트를 제공했다. 이는 소규모 고정 명령 비교이며 자연 발화 분포의 정확도 추정이 아니다. 원문과 native output·fallback 결과를 별도 기록했다.

| 명령 | FunctionGemma 전체 의미 | Qwen 전체 의미 | Qwen 실제 생성 calls |
|---|---|---|---|
| 네이버지도 켜줘 | FAIL | PASS | open_app(app_name=네이버지도) |
| 회사로 가자 | FAIL | PASS | navigate(destination=회사) |
| 집으로 네비 찍어줘 | FAIL | PASS | navigate(destination=집) |
| 음악 재생해 | FAIL | PASS | media_resume() |
| 현재 노래 저장해 | FAIL | PASS | perform_app_task(app_name=, goal=현재 노래 저장해) |
| 집으로 가면서 음악 틀어줘 | FAIL | PASS | navigate(destination=집); media_resume() |
| 유튜브에서 노래 검색해 | FAIL | PASS | perform_app_task(app_name=유튜브, goal=유튜브에서 노래 검색해) |
| 최근 알림 읽어줘 | FAIL | PASS | perform_app_task(app_name=, goal=최근 알림 읽어줘) |
| 집으로 가자 | FAIL | PASS | navigate(destination=집) |
| 집에 가자 | FAIL | PASS | navigate(destination=집) |
| 지브로 가자 | FAIL | FAIL | navigate(destination=지브로) |

`지브로 가자`는 Qwen이 목적지 `지브로`를 그대로 생성해 집으로 복구하지 못했다. 특정 문자열 치환을 추가하지 않았다. 같은 환경의 전체 계획 기준 Qwen 90.9% / FunctionGemma 0.0%다. **실제 사용자 앱 작업 성공률의 향상은 이 수치로 계산할 수 없다.** 앱 로그인·현재 UI·설정·권한·실행 완료 관측을 포함한 현장 성공률은 미측정이다.

## 10. Tool Plan 정확도

| 지표 | FunctionGemma | Qwen |
|---|---:|---:|
| Intent 정확도(전체 요청 동작 종류) | 0/11 | 11/11 |
| Entity 정확도(앱·장소 명시 8건) | 0/8 | 7/8 |
| 전체 Tool Plan 의미 정확도 | 0/11 | 10/11 |
| 잘못된 종류 또는 잘못된 인자의 호출을 낸 명령 | 1/11 | 0/11 |
| 스키마 위반 호출을 낸 명령 | 1/11 | 0/11 |
| 복합 이동+음악 전체 계획 | FAIL | PASS |

Intent는 요청한 모든 함수 종류, Entity는 앱/장소가 명시된 8건의 해당 인자, 전체 Plan은 모든 요청과 원문 목표 보존까지 평가한다. 잘못된 호출의 분모는 11개 명령이며 무호출은 오호출로 계산하지 않는다. 무호출·거절·누락은 전체 Plan 실패로 계산한다. PlanGrounding은 앱 열기를 내비게이션으로 바꾸거나 장소/시간을 만들어내는 계획을 거부한다. 명확한 복합 명령의 일부가 빠진 계획도 거부하며 규칙 fallback을 AI 정답으로 세지 않는다.

개발 중 Qwen이 음악을 빠뜨린 실제 출력이 있었고 공통 coverage 검사와 동일한 다중 행동 지침을 추가했다. 최종 11건은 수정 후 **최종 APK의 실제 새 추론 결과**다. GBNF는 형식만 제한하며 의미 정확도를 강제하지 않는다.

## 11. 기존 GUI Agent 회귀

**core 118개 + 최종 APK Android 회귀 69개 + 실제 모델/복구/음성 연결 9개 PASS**, 실패·skip 0. GUI 25, 저장 2, Android Tool 7, updater 7, 음성 UI 8, 음성 구조 3, voice invocation 17 = 69. 기존 빠른 경로·NAVER 집/회사/자주 가는 곳 탐색·원래 목표 UI fallback·관측된 요소 기반 조작·취소·호출 중복 제어를 보존했다.

회귀 집계는 동일한 app/test APK의 각 case 최신 결과다. 첫 실행은 68/69였으며 설치 화면 검사에서 외부 작은 업데이트 fixture의 label이 실제 앱 이름과 달랐다. fixture label만 맞춘 뒤 updater 7개를 다시 실행해 모두 PASS했다. APK는 변경하지 않았으며 첫 실패 기록과 재검사 기록을 각각 `dual-regression-initial.json`, `dual-update-retry.json`에 보존했다.

회귀 69개는 기존 경로를 분리 검증하기 위해 명시적으로 **RULE_BASED** 모드에서 실행했다. 실제 모델은 별도 9개에서 진짜 native engine을 사용했다. 전체 11개 계획을 실 NAVER/음악/YouTube/알림 앱에서 모두 실행했다고 주장하지 않는다. 둘 다 공통 GUI catalog를 받지만 실제 모델이 임의 앱의 다단계 UI를 해결하는 품질은 미검증이다. Tool 선택 오류, 모델 오류, 화면 관찰 오류, 실행 오류를 분리 기록한다.

## 12. RAM·CPU·속도·발열·배터리

동일 AVD: CPU 4 threads, RAM 4096 MiB, x86_64/WHPX. 실제 FunctionGemma 최초 설치 추출 **627ms**, native load **843ms**. Qwen 최초 추출/native load는 위 첫 Qwen 전환 행이다. 최초 좁은 Wi-Fi probe와 전체 phone catalog의 첫 명령 prefill은 다른 측정이다.

| 측정 | FunctionGemma | Qwen |
|---|---:|---:|
| 첫 전체 catalog 명령 추론(ms) | 526.0 | 42,940.0 |
| 이후 10건 추론 중앙값(ms) | 477.5 | 3,644.0 |
| 이후 추론 최소(ms) | 449.0 | 3,171.0 |
| 이후 추론 최대(ms) | 625.0 | 6,455.0 |
| 명령 중 process CPU 시간 중앙값(ms) | 1,831.0 | 12,753.0 |
| 300ms 간격 관측 peak process PSS(MiB) | 548.4 | 1,854.1 |

PSS는 앱/VM/검사 프레임워크를 포함한 process 값이며 모델만의 RAM 값이 아니다. 300ms 샘플 간격의 관측 peak다. CPU ms도 process 전체 시간이며 utilization %로 보고하지 않는다. 추론 backend는 CPU이며 GPU/NPU utilization 측정은 하지 않았다. Qwen은 변하지 않은 prompt prefix의 KV만 재사용하고 이전 생성 응답을 재사용하지 않으므로 매 명령을 새로 생성한다. 변경된 사용자 문장/화면과 과거 출력 KV는 버린다.

작업 후 **30초 idle**에 unload. 그 뒤 process PSS **115.9 MiB**, 2초 동안 process CPU **1ms**. 상시 추론·상시 마이크는 없다. 음성 호출은 STT와 선택 모델 warm 준비를 병행한다. AVD 결과로 뒷면 탭 S25 체감 지연·배터리·발열을 추정하지 않는다. **배터리/발열 NOT_APPLICABLE_AVD, 실제 단말 NOT_VERIFIED**.

## 13. 실제 S25 Ultra

**NOT_VERIFIED**. 이번 최종판을 설치/실행할 연결된 S25가 없어 실단말 설치·arm64 추론·재부팅·Secure Folder·Back Tap·실 NAVER/음악·Play Protect 허용 여부를 검증하지 않았다. 사용자가 요청한 대로 자연 발화 50건 평가를 다시 요구하지 않았다. 이전 버전에서 관측한 기능을 이 APK의 PASS로 옮기지 않는다.

## 14. 최종 APK 위치와 업데이트

직접 설치용: `outputs/LocalPhoneAgent-dual-model.apk`.

[GitHub v0.7.0 Release](https://github.com/viennaru-prg/local-phone-agent/releases/tag/v0.7.0)에서 `LocalPhoneAgent-dual-model.apk`를 사용한다. 앱의 업데이트 버튼은 공개 GitHub Release의 크기·SHA-256·패키지·버전·기존 서명을 검증한 뒤 Android 설치 화면을 연다. 모델 최초 다운로드는 필요하지 않다. 소스 Git에는 대형 가중치가 없으며 고정 manifest/준비 스크립트를 포함한다. Release의 모델 포함 source ZIP에는 두 실제 가중치·STT·고정 native 소스가 포함되고 개인 서명 키/토큰/SDK cache는 포함되지 않는다. SDK/Gradle dependencies는 별도로 필요하다.

검증 데이터: Release의 `dual-model-validation.json`, `update-verification.json`, `SHA256SUMS.txt`; 로컬 `automation-test/dual-model.json`, `automation-test/dual-regression.json`, `model-selection-restart-verification.json`. GitHub CI는 core만 항상 검사하며 승인된 HF_TOKEN secret이 있어야 전체 APK/lint를 실행한다. 이번 배포 APK의 전체 빌드와 lint는 로컬에서 검증했다.

## 15. 남은 BLOCKER와 미검증 사항

- 모델 확보/재배포 권한/최종 APK 크기에 대한 기술적 blocker는 해결했다. 두 모델은 실제 APK에 포함된다.
- FunctionGemma 한국어 계획 0/11: 학습 범위/한국어 성능 한계다. 실패한 모델 결과는 성공처럼 표시하지 않는다.
- Qwen의 `지브로` 의미 복구 실패와 임의 앱 다단계 GUI 성공률은 남아 있다. 실제 ambiguity/자동 해결 실패는 사용자에게 표시한다.
- S25/Knox/Back Tap/Play Protect/실제 앱 작업 성공률/배터리·발열 및 휴대폰 재부팅은 NOT_VERIFIED.
- 실제 음성 평가를 사용 조건으로 만들지 않는다. 실제 마이크 입력은 사용자가 원할 때 확인할 수 있고 이번 수치는 합성 PCM 1건 및 텍스트 계획 비교에 한정된다.

필수 조건: A 내장 모델 PASS; B FunctionGemma 실제 함수 추론 PASS(한국어 정확도 한계 별도); C Qwen 실제 추론 PASS; D 전환 PASS; E 기존 runtime 회귀 PASS(실제 외부 앱 전체 E2E 미검증); F 네트워크 차단 추론 PASS; G 실제 앱 process 재시작 선택 유지 PASS; H S25 NOT_VERIFIED.
