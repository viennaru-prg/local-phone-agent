# 지도 Intent 테스트 대역

실제 네이버지도 앱이 아닙니다. Android Intent의 패키지 지정, URL scheme, 목적지 인자 전달, 전면 전환을 검증하는 최소 수신 화면입니다. 지도·길찾기·음성 안내는 제공하지 않습니다.

프로토콜의 명시적 패키지 지정까지 검사하기 위해 applicationId가 `com.nhn.android.nmap`입니다. `android:testOnly=true`로 설정되어 있습니다. **실제 휴대폰이나 사용자의 기존 에뮬레이터에는 설치하지 않습니다.** 이 작업에서 만든 `LocalPhoneAgent_API35` AVD에서만 `adb install -t`로 설치합니다. 실제 네이버지도와 함께 설치할 수 없습니다.

이 fixture를 통과해도 실제 네이버지도 E2E를 통과한 것으로 표시하지 않습니다. 일반 사용자용 앱 APK에 포함되지 않습니다.
