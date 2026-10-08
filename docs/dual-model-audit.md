# 0.7 듀얼 내장 모델: 기존 구현 감사

감사 대상: 0.6.0, Git commit `4bd1ea73f674b27d610448c24d5a6f9851ebaf6c`.

기존 APK에는 에이전트 LLM 가중치가 없었다. `FunctionGemmaPlanner`는 실제 LiteRT-LM API를 사용하지만 `noBackupFilesDir/functiongemma.litertlm`을 수동으로 가져와야 했다. 음성 모델 가중치는 에이전트 LLM과 별개다. 기본값은 FunctionGemma 비활성화였다.

`AgentEngine.prepare`는 먼저 `BasicCommandPlanner`를 호출하고, 규칙이 처리하지 못한 명령에 한해서만 수동 모델 파일이 있을 때 FunctionGemma를 시도했다. 따라서 이전의 지도·음악 성공은 LLM 추론의 증거가 아니며 모델 평가로 사용할 수 없다. 복잡한 화면 작업도 가중치가 없으면 실제 모델 추론을 수행할 수 없었다.

0.7은 `LocalAgentModel`의 LiteRT-LM / llama.cpp 구현을 `LocalAgentModels` 한곳에서 선택한다. 프로덕션의 음성·텍스트 명령은 선택 모델의 실제 추론을 먼저 수행하고, 동일한 `PhoneTools` decoder와 `PlanGrounding`, `PolicyGate`, Android / Accessibility runtime을 거친다. 모델이 실패하면 `RULE_BASED`와 모델 오류를 남기고 기존 자동 경로를 이어간다. 모델 응답을 무시하거나 규칙 판단을 `MODEL_INFERENCE`로 표시하지 않는다. 회귀 테스트의 규칙 모드는 프로세스 내부 테스트 설정이며 외부 인텐트로 켤 수 없다.

두 가중치는 `agent-models.json`의 출처 revision / 용량 / SHA-256과 일치해야 한다. Gradle `preBuild`는 누락·손상을 실패로 처리한다. 모델 파일은 Git에서 제외하며 최종 APK의 압축하지 않은 asset에 포함된다. 앱은 다운로드하거나 임의 파일을 선택하지 않는다. 처음 사용할 때 현재 설치 프로필의 비백업 전용 저장소로 필요한 파일만 스트리밍 추출하고 크기·해시·여유 공간을 확인한 뒤 동기화 / atomic rename한다. 중단된 `.part`와 이전 revision은 정리하며 현재 두 revision의 캐시는 오프라인 전환에 재사용한다.

전환은 명령이 진행 중일 때 거부하며, 기존 native runtime을 해제한 뒤 새 파일 추출·로드·실제 Wi-Fi 설정 함수 probe를 실행한다. probe는 Android 도구를 실행하지 않는다. 성공해야 선택을 저장하며 실패하면 이전 모델을 다시 로드한다. 작업 종료 후 30초 동안 사용이 없으면 native 메모리를 해제한다. CPU만 사용하고 GPU/NPU 사용으로 표시하지 않는다.

공식 Mobile Actions fine-tune은 `open_app` 등 미학습 함수에 자연어 거절을 반환했다. FunctionGemma adapter는 동일한 canonical catalog의 설정 함수와 지도 함수를 학습된 native 이름으로 표현하고, 실제 생성된 이름·인자를 공통 함수로 변환한다. 이 형식 변환은 사용자 명령을 규칙으로 분류하거나 모델 답을 고정하지 않는다. 원래 native 출력도 진단 화면과 평가 기록에서 보존한다. 두 adapter의 의미 기능·최종 decoder·정책·실행기는 동일하다.

FunctionGemma의 공식 CPU INT8 파일은 계정의 모델 약관 동의가 필요하다. Gemma 약관·금지 사용 정책·NOTICE를 APK에 포함하고 처음 사용할 때 사용 제한에 동의하도록 한다. Qwen 공식 GGUF 저장소에는 Q8_0만 제공되어, 원본 모델 연결과 SHA-256을 확인한 bartowski Q4_K_M을 고정했다. Qwen Apache-2.0, 양자화 출처 카드 및 llama.cpp MIT 라이선스를 포함한다.

이 문서는 설계 / 감사 기록이다. 실제 추론·정확도·메모리·APK·S25 검증 결과는 실행 후 생성되는 검증 보고서에서만 확인한다. 가상 Android 결과는 실제 S25나 보안 폴더의 PASS가 아니다.
