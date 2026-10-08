# Local Phone Agent 0.7.2 — 실제 내장 모델 검증

**0.7.2-automation / versionCode 11**, `1,808,198,573` bytes, APK SHA-256 `3e0fea67040df0d15a11f8cc54fd17ad7a1921149df54754406d32bd64f2b3fe`. 직접 설치는 `LocalPhoneAgent-dual-model.apk` 하나다. 두 실제 가중치를 APK에서 전부 읽어 SHA-256을 확인했고 소유한 Android 15/API35 x86_64 AVD에서 Wi-Fi/모바일 데이터 없이 실제 native 추론·전환·실행을 검사했다. 첫 실행 모델 다운로드나 수동 파일 선택은 필요 없다.

## 모델과 엔진

- **functiongemma**: `agent_models/mobile_actions_q8_ekv1024.litertlm`, 288,964,608 bytes, SHA-256 `33e295cbd996b419bb1de8f3f85c5b6b01ee058a2c89bdb2173cf3e6ff4ce9d0`.
- **qwen3**: `agent_models/Qwen_Qwen3-1.7B-Q4_K_M.gguf`, 1,282,439,584 bytes, SHA-256 `72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb`.

FunctionGemma 출처: [공식 mobile-actions](https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions), revision `f752a74080682b379823794defdbbdf8c2663609`, CPU INT8 ekv1024, LiteRT-LM 0.10.2/XNNPACK, CPU 4 threads, context 1024. 공식 이용 약관 승인으로 확보했다. Gemma 약관/금지 사용 정책/NOTICE를 포함한다.

Qwen 출처: [bartowski Qwen3-1.7B GGUF](https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF), revision `dcb19155b962dbb6389f4691a982043a8e651022`, Q4_K_M. 원본 Qwen3-1.7B/Apache-2.0, 양자화 출처 카드, llama.cpp/MIT를 포함한다. 실행 JNI revision `bd4eeaa047006cb1fe71999fbd11134b5836e167`, CPU 4 threads, context 4096, GPU offload 0. APK arm64-v8a/x86_64 native ELF·ZIP의 16 KiB 정렬을 검증했다. 실제 arm64/S25 추론은 미검증이다.

두 파일은 압축하지 않은 assets에 들어 있고 누락/크기/SHA/라이선스 불일치는 빌드를 실패시킨다. 토큰/서명 키를 APK나 Git에 넣지 않는다. 큰 가중치는 일반 Git에 넣지 않고 Release의 전체 소스 ZIP에는 실제 가중치·STT·고정 native 소스를 넣는다.

## 실제 모델 선택·품질

선택 모델 하나만 native 메모리에 유지한다. 실제 probe 성공 후 선택을 저장하고, 실패한 전환은 이전 Qwen을 실제 로드/추론해 복구한다. 작업 중 전환은 보류하며 완료 후 30초 idle에 unload한다. Android process 종료/재실행 뒤 실제 Qwen RadioButton 선택 유지도 확인했다. 기기 재부팅/Knox 재실행은 미검증이다.

양 모델은 같은 의미 Tool Catalog·PlanGrounding·Policy·Android/UI 실행기를 사용한다. 실제 native 출력과 fallback을 분리 기록한다. FunctionGemma는 English Wi-Fi health tool을 실제 생성하지만 한국어 전체 계획은 **0/11**다. Qwen은 **9/11**다. 고정 11개 텍스트 소표본이며 자연 발화 정확도/사용자 앱 작업 성공률로 일반화하지 않는다. 규칙 fallback은 AI 정답으로 세지 않는다.

| 명령 | FunctionGemma 전체 계획 | Qwen 전체 계획 |
|---|---|---|
| 네이버지도 켜줘 | FAIL | PASS |
| 회사로 가자 | FAIL | PASS |
| 집으로 네비 찍어줘 | FAIL | PASS |
| 음악 재생해 | FAIL | PASS |
| 현재 노래 저장해 | FAIL | PASS |
| 집으로 가면서 음악 틀어줘 | FAIL | FAIL |
| 유튜브에서 노래 검색해 | FAIL | PASS |
| 최근 알림 읽어줘 | FAIL | PASS |
| 집으로 가자 | FAIL | PASS |
| 집에 가자 | FAIL | PASS |
| 지브로 가자 | FAIL | FAIL |

Qwen JSON grammar는 형식만 제한한다. 잘못된 앱/장소/누락된 복합 목표는 grounding이 거부하고 원래 목표의 규칙/UI 경로를 이어간다. 임의 앱의 다단계 GUI 성공률은 미측정이다.

## 시간·메모리·저장공간

| 선택 | 총 전환 ms | 추출 ms | native load ms | 실제 probe ms |
|---|---:|---:|---:|---:|
| FUNCTIONGEMMA | 623 | 3 | 162 | 418 |
| QWEN3 | 55779 | 15717 | 9220 | 30449 |
| FUNCTIONGEMMA | 7194 | 2 | 1299 | 5304 |

| 모델 | 첫 명령 추론 ms | 이후 10건 중앙값 ms | 전체 범위 ms | 관측 peak process PSS MiB |
|---|---:|---:|---:|---:|
| functiongemma | 691 | 594.0 | 522–2679 | 568.7 |
| qwen3 | 5075 | 4209.5 | 3670–7945 | 1856.1 |

PSS는 앱/검사 VM을 포함한 process 샘플이며 모델만의 RAM 값이 아니다. CPU backend만 검사했다. AVD로 실제 휴대폰 배터리/발열/GPU/NPU 사용률을 추정하지 않는다. 저장공간 원측정: `{"android_storage_stats": {"app_bytes": 1818177536.0, "data_bytes": 1887858688.0, "cache_bytes": 294912.0}, "base_apk_bytes": 1808198573.0, "private_no_backup_bytes": 1887221572.0, "private_model_weights_bytes": 1571404192.0, "private_cache_bytes": 250064.0, "native_libraries_bytes": 0.0}`. 두 모델 추출 사본은 private noBackup에 남아도 한 번에 하나만 메모리에 올린다. 취소/손상 사본의 APK 재추출·SHA 확인을 실제 검사했다. 고정 Tool input prefix만 검증해 재사용하며 사용자 명령/화면/생성 출력은 캐시에 넣지 않는다. 명령은 매번 새 생성이다.

native 취소 뒤 join **265 ms**와 다음 실제 추론이 통과했다. idle 후 PSS **100.9 MiB**, 2초 process CPU **0 ms**다. 초기 모델 준비와 인식 후 완료 시간은 [음성·안내 검토](navigation-voice-review.md)에서 구분한다. 모든 작업이 10초 이내인 상태는 아니다.

## 최종 검증과 배포

core **133/133**, 동일 최종 app/test APK의 Android **95/96**, 실패 1·skip 0. 실제 모델 9/9, 회귀 78/78, 음성/UI/기한/캐시 8/9다. 유일한 실패는 실제 SUCCESS였으나 11,719ms로 기존 10초 assertion을 넘긴 성능 목표다. 기준을 낮추지 않았고 PASS로 세지 않는다. NAVER는 testOnly 별도 앱/OS 접근성 fixture이며 실제 NAVER 검사가 아니다. 합성 한국어 PCM → 실제 포함 STT decoder → Qwen 알람 계획 → 실제 AVD Clock foreground도 통과했다. 자동 호출은 보고된 native confidence 0.0/7 partial callback → 실제 Qwen → 회사 좌표 → 안내 완료/AI 종료를 확인했다. 실제 S25 마이크·RegiStar 센서를 재현한 시험으로 표시하지 않는다.

[GitHub v0.7.2](https://github.com/viennaru-prg/local-phone-agent/releases/tag/v0.7.2)의 최종 APK를 쓰거나 앱 설정에서 업데이트 확인을 누른다. 이전 0.6 이하 updater는 크기 제한 해제 준비판(8) → 최종판(11), 두 모델 포함 0.7.0/0.7.1은 최종판(11)을 바로 받는다. 크기/SHA/패키지/버전/기존 서명을 확인하고 최종 설치는 Android 화면에서 승인한다. 기존 공개 버전의 파일은 바꾸지 않는다.

Release의 `dual-model-validation.json`, `navigation-voice-review.json`, `office-*.json`, 7개 Android report, `update-verification.json`, `SHA256SUMS.txt`에 실제 결과/해시를 기록한다. GitHub CI는 core를 항상 실행하고 승인된 HF_TOKEN이 있을 때만 전체 APK/lint를 실행한다. 이번 전체 APK/lint는 로컬 검사다. S25/arm64/Knox/실 NAVER/RegiStar/Play Protect 승인은 **NOT_VERIFIED**다. 자연 발화 50개/차량 평가를 사용 조건으로 요구하지 않는다.
