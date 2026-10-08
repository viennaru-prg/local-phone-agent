# GitHub Release와 앱 업데이트

설정 화면의 **업데이트 확인**을 누르면 GitHub의 최신 공개 정식 Release를 읽습니다. 현재 설치 버전보다 versionCode가 크면 Release 변경 내용을 표시하며 APK를 자동으로 받습니다. SHA-256·파일 크기·패키지·버전·현재 앱과 동일한 서명을 확인한 뒤 Android 설치 프로그램을 엽니다. 첫 사용에는 Android의 해당 앱 설치 허용이 필요하며, 최종 업데이트는 시스템 화면에서 사용자가 승인합니다.

자동 백그라운드 다운로드나 음성 호출 때마다 네트워크 확인을 하지 않습니다. 사용자가 누른 업데이트 요청만 처리합니다. 다운로드 중 Activity 회전은 ViewModel이 유지하고, 앱을 닫아 작업이 취소되면 부분 파일을 지웁니다. 완료 파일은 앱 내부 cache/updates에 두고 FileProvider가 APK만 설치 프로그램에 임시 읽기 권한으로 전달합니다. 네이버 장소·모델·Keystore 자료를 공유하지 않습니다.

업데이트 대상 저장소는 BuildConfig.UPDATE_REPOSITORY입니다. 기본값은 `viennaru-prg/local-phone-agent`이며, 재빌드 시 `-PupdateRepository=owner/repo`로 바꿀 수 있습니다. 인증 토큰을 APK에 넣지 않습니다. 직접 다운로드는 공개 Release 기준입니다.

## 다음 버전 배포

1. 기존 설치판의 개인 서명 키를 보관합니다. 키를 Git에 올리지 않습니다. 자동으로 생성한 다른 debug key로 만든 APK는 기존 앱의 업데이트가 될 수 없습니다.
2. versionCode를 단조 증가시키고 버전을 지정합니다. 예: `:app:assembleAutomation -PreleaseVersionCode=11 -PreleaseVersionName=0.7.2`. Gradle을 실행할 때 기존 키가 있는 ANDROID_USER_HOME 또는 별도 signing 설정을 유지해야 합니다.
3. 패키지 `dev.localphone.agent`, 버전 및 기존 서명을 확인하고 검사를 실행합니다. 실제 배포할 APK의 검사 해시를 기록합니다.
4. 두 모델이 포함된 파일 이름을 `LocalPhoneAgent-dual-model.apk`로 복사한 후 다음 명령으로 `update-dual.json`을 만듭니다.

```powershell
python scripts/make-update-manifest.py --apk LocalPhoneAgent-dual-model.apk --version-code 11 --version 0.7.2 --output update-dual.json
```

5. 해당 검증 commit의 `v0.7.2` GitHub Release를 draft로 만들고 APK와 update-dual.json을 모두 첨부합니다. 변경 내용은 Release 본문에 적습니다.
6. 모든 첨부의 업로드와 SHA-256을 확인한 뒤 draft를 공개 정식 Release로 전환하고 latest로 지정합니다. prerelease·draft는 앱이 사용하지 않습니다. 이미 배포한 버전의 파일을 바꾸는 대신 새 versionCode를 발급합니다.

GitHub CI는 항상 core 검사를 수행합니다. 승인받은 FunctionGemma 계정의 `HF_TOKEN` secret이 설정된 경우에만 두 모델 준비·전체 APK 빌드·lint를 추가 수행합니다. secret이 없으면 이 항목들은 미실행으로 안내합니다. 배포 키가 없는 CI의 임시 debug APK를 Release에 올리지 않습니다. 큰 모델과 음성 AAR는 Git에 넣지 않고 `scripts/prepare-assets.py`, `scripts/prepare-agent-models.py`가 고정 revision·크기·SHA-256으로 준비합니다. 최종 설치 APK에는 음성 모델과 두 Agent 모델이 포함됩니다. 네이티브 소스는 `git submodule update --init --recursive`로 고정 llama.cpp revision을 준비합니다.

## 0.6 이하에서 0.7로 업데이트

0.6까지의 앱은 512 MiB보다 큰 APK를 거부합니다. 0.7 Release에는 다음 두 쌍을 함께 제공합니다.

| 용도 | APK | Manifest | versionCode |
|---|---|---|---|
| 직접 설치·최종 앱 | `LocalPhoneAgent-dual-model.apk` | `update-dual.json` | 11 |
| 기존 앱의 업데이트 크기 제한 해제 | `LocalPhoneAgent-automation.apk` | `update.json` | 8 |

기존 앱의 업데이트 확인은 작은 준비판(8)을 설치합니다. 준비판에서 다시 업데이트 확인을 누르면 두 모델이 포함된 최종판(11)을 받습니다. 이미 두 모델이 포함된 0.7.0(9)·0.7.1(10) 사용자는 최종판(11)을 바로 받습니다. 각 단계는 Android 설치 화면에서 승인이 필요합니다. 준비판은 모델을 포함한 최종 앱이 아닙니다. 새로 설치할 때는 `LocalPhoneAgent-dual-model.apk` 하나만 사용합니다.

준비판은 배포된 0.6 commit `4bd1ea73f674b27d610448c24d5a6f9851ebaf6c`의 기존 실행 구조에 새 파일명·2 GiB 상한·설치 완료 캐시 정리·업데이트 안내를 적용한 것입니다. 모델을 인터넷에서 추가로 내려받는 방식으로 바꾸지 않습니다. 최종 APK와 같은 개인 서명 키를 사용합니다.

재현할 때는 별도 0.6 checkout에서 이 저장소의 `scripts/update-bridge-0.7.patch`를 `git apply`하고, 기존 음성 assets와 개인 서명 키를 준비한 뒤 `:app:assembleAutomation :app:lintAutomation -PreleaseVersionCode=8 -PreleaseVersionName=0.7.2`을 실행합니다. 준비판의 versionName도 해당 Release 태그와 일치해야 이전 updater의 버전 검사를 통과합니다. patch에는 가중치나 인증 정보가 없습니다. 준비판 빌드에 새 Agent 모델을 넣거나 최종판의 versionCode를 낮추지 않습니다.

업데이트 다운로드 전에는 APK 다운로드와 Android 설치 스테이징을 위한 공간을 확인합니다. 최종 모델은 압축하지 않은 APK assets에서 앱 전용 `noBackupFilesDir`로 필요한 모델만 추출하고 SHA-256을 검사합니다. 두 모델을 한 번씩 사용하면 둘의 디스크 사본이 남지만 네이티브 추론 메모리에는 선택된 모델 하나만 유지합니다. 오래된 모델 revision·중단된 추출 파일·이전 업데이트 APK는 앱 소유 디렉터리 안에서 정리합니다.

## 검증과 실제 기기

업데이트 검사는 전용 Android 15 AVD에서 다른 버전의 실제 APK, 다른 서명의 실제 APK, 실제 PackageManager/FileProvider 및 시스템 설치 화면을 사용합니다. Release HTTP 응답은 테스트 fixture이며 실제 GitHub 연결 검사는 배포 후 별도 기록합니다. 시스템 확인 화면까지 열고 취소하므로 instrumentation 도중 앱을 바꿔 설치하지 않습니다.

개인 장소 데이터는 같은 패키지·서명으로 정상 업데이트할 때 유지됩니다. 보안폴더에서는 그 공간의 Agent와 설치 프로그램이 지원해야 합니다. 실제 S25/Knox 및 Play Protect가 새 APK를 허용하는지는 별도 미검증입니다. 앱은 보호나 최종 설치 승인을 자동으로 누르지 않습니다.

공식 API: [GitHub Release](https://docs.github.com/en/rest/releases/releases), [asset digest](https://docs.github.com/en/rest/releases/assets), [Android 설치 허용](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()), [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider).
