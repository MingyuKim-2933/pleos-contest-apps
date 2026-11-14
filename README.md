# 여긴어디 — 운전하며 듣는 위치 기반 오디오 여행 가이드 (팀 나그네들)

> 운전 중 지나치는 명소와 맛집의 이야기를 Pleos 음성으로 들려주고, **"들러줘"** 한마디로 내비게이션 길 안내까지 이어 주는 Pleos Connect 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전 제출작입니다. 소스 코드는 [`road-story/`](road-story/README.md) 폴더에 있습니다.

## APK
- 위치: [`apk/나그네들_여긴어디_release.apk`](apk/나그네들_여긴어디_release.apk) (release 서명 빌드)
- 패키지: `com.hdcontest.roadstory` · 버전 1.0.0 (versionCode 1)
- 심사 시 실시간 관광 정보 안내를 확인할 수 있도록 한국관광공사 TourAPI 개발용 인증키를 포함해 빌드했습니다. 소스 코드에는 키를 넣지 않았습니다(아래 "필요한 설정" 참고).

## 실행 방법
1. Pleos Connect 에뮬레이터를 실행합니다.
2. APK를 설치합니다. Pleos Connect 에뮬레이터는 사용자 10에서 앱이 실행되므로 `--user 10`을 붙입니다.
   ```bash
   adb install -r -g --user 10 apk/나그네들_여긴어디_release.apk
   ```
3. 내비게이션 앱을 한 번 연 뒤 '여긴어디'를 실행합니다. 데모 주행 경로가 내비게이션의 차량 위치에서 시작합니다.
4. 오른쪽 아래 **데모 주행 (시연용)** 을 켜고 **가이드 시작**을 누릅니다. 판교 → 남한산성을 시속 약 100km로 가상 주행하며 주변 명소를 음성으로 안내합니다.
5. 안내가 끝나고 "듣고 있어요…"가 표시되면 **"들러줘"** 라고 말하거나 **들르기** 버튼을 누릅니다. 내비게이션이 그 장소로 길 안내를 시작합니다.
   - 들르기: "들러줘 / 가자 / 응 / 좋아" · 다시 듣기: "다시 / 다시 듣기 / 뭐라고" · 넘어가기: "괜찮아 / 다음에 / 건너뛰기"

## 테스트 환경
| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator v2.1 (AVD `Pleos_Connect_v2_1`, 빌드 `release_connect_v2.1.0`) |
| 시스템 이미지 | Android 14 (API 34), `pleos_car_emulator` arm64-v8a |
| 화면 규격 | 2560 × 1440, 160 dpi (가로) |
| Android Emulator | 37.2.12.0 |
| 개발 PC | macOS (Apple Silicon), JDK 21 (Android Studio JBR) |
| 빌드 | AGP 8.8.2, Gradle 8.10.2, Kotlin 2.0.0, compileSdk 35, minSdk 28 |

## 필요한 설정 (직접 빌드할 때)
- **TourAPI 인증키(선택):** `road-story/.env.example`을 `road-story/.env`로 복사하고 `TOUR_API_KEY`에 [공공데이터포털](https://www.data.go.kr/data/15101578/openapi.do) "한국관광공사_국문 관광정보 서비스_GW" 인증키를 넣습니다. 키가 없으면 내장 대표 명소 35곳만 안내합니다.
- **release 서명(선택):** `.env`의 `RELEASE_*` 항목에 keystore 정보를 넣으면 release APK가 서명됩니다.
- **Pleos 음성:** 별도 자격 증명 없이 온디바이스 TTS/STT로 동작합니다.

```bash
cd road-story
./gradlew testDebugUnitTest assembleRelease   # 단위 테스트 49개 + release APK
```

자세한 구조와 기능 설명은 [`road-story/README.md`](road-story/README.md)를 참고해 주세요.

## 사용한 Pleos Connect SDK · 공공 데이터
- FusedLocation 1.1.3 · TextToSpeech 3.3.1 · SpeechToText 3.3.1 · NaviHelper 3.0.5
- 한국관광공사 TourAPI 4.0 (출처: 한국관광공사)

---

### 참고: 같은 저장소의 다른 프로젝트
[`eco-coach/`](eco-coach/README.md) (에코 드라이빙 코치)는 같은 저장소에 있는 별도 실험 프로젝트이며, 이번 공모전 제출작에는 포함되지 않습니다.
