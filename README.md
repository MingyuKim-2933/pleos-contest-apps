# Pleos Connect 공모전 앱 2종

| 폴더 | 앱 | 한 줄 소개 | 주요 SDK |
|---|---|---|---|
| [`road-story/`](road-story/README.md) | 여긴 어디 | 지나치는 명소 이야기를 음성으로 들려주고 "들러줘"로 경유지 추가 | FusedLocation, TTS, STT, NaviHelper |
| [`eco-coach/`](eco-coach/README.md) | 에코 드라이빙 코치 | 급가속·급제동·과속을 음성으로 코칭하고 주차 후 점수 리포트 제공 | Vehicle, NaviHelper, TTS (+ AAOS 차속) |

두 앱의 공통 설계 원칙은 다음과 같습니다.
- **조회 권한만 사용**합니다. 차량 제어(CONTROL_*) 권한을 쓰지 않아 앱 심사 부담이 적습니다.
- 운전 중에는 **음성 중심**으로 동작하고, 화면에는 큰 버튼과 최소한의 정보만 둡니다.
- **데모 모드**가 있어 에뮬레이터에서도 전체 시나리오를 시연할 수 있습니다.
- 핵심 로직(`core/`)은 Android 의존성이 없는 순수 Kotlin으로 작성했고, 단위 테스트로 검증했습니다.

각 폴더는 독립된 Android Studio 프로젝트입니다. 빌드 기준은 AGP 8.8.2, Gradle 8.10.2, Kotlin 2.0.0, compileSdk 35, minSdk 28, JDK 17 이상입니다.
