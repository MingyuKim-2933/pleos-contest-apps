# 여긴어디 — 위치 기반 오디오 여행 가이드 (팀 나그네들)

운전하면서 지나치는 명소 이야기를 음성으로 들려주고, **"들러줘"** 한마디로 내비게이션 경유지에 추가하는 Pleos Connect 앱입니다.
화면을 보지 않아도 쓸 수 있도록 음성을 중심으로 설계했습니다.

## 동작 흐름

```
차량 위치(FusedLocation) ──┬─ 위치 업데이트 → 앱 자체 반경 판정 ─┐
                           └─ 근접 알림(ProximityAlert ENTER) ────┴→ NarrationPlanner (중복·쿨다운 필터)
                                                                        │
     "800미터 오른편에 경복궁이 있어요. …"  ← TextToSpeech  ←──────────┘
                                                                        │
     "들러줘" / "괜찮아" / "다시"           → SpeechToText → VoiceIntentParser
                                                                        │
     경로 안내 중이면 경유지 추가, 아니면 목적지 설정 → NaviHelper ─────┘
```

## 사용한 Pleos Connect SDK

| SDK | 버전 | 용도 |
|---|---|---|
| FusedLocation | 1.1.3 | 차량 위치·진행 방향 수신, 장소별 근접 알림(`addProximityAlert`) |
| TextToSpeech | 3.3.1 | 명소 안내 음성 |
| SpeechToText | 3.3.1 | 안내 후 짧은 음성 응답 인식 |
| NaviHelper | 3.0.5 | `addWaypoint()` 경유지 추가 / `requestRoute()` 목적지 설정, 경로 상태 이벤트 |

## 사용한 공공 데이터

| API | 용도 |
|---|---|
| 한국관광공사 TourAPI 4.0 (국문 관광정보 서비스, `KorService2`) | `locationBasedList2`로 차량 주변 관광지(12)·문화시설(14)·쇼핑(38) 조회(대표 이미지가 있는 곳만), `detailCommon2`의 개요로 안내 이야기 생성 |

- 인증키가 없거나 호출에 실패하면 내장 명소(`assets/pois.json`)만으로 동작합니다.
- 2km 이상 이동했거나 30분이 지났을 때만 다시 조회해서 호출량을 줄입니다. 실패하면 1분 뒤 재시도합니다.
- 가까운 곳부터 개요를 받고(한 번에 최대 20곳), 개요를 받은 장소만 안내 대상에 넣습니다. 개요는 HTML 태그와 한자 괄호를 지우고 150자 안팎으로 줄여 TTS로 읽습니다.
- 내장 명소와 같은 장소(80m 이내, 또는 600m 이내이면서 이름이 비슷한 곳)는 직접 다듬은 내장 이야기를 우선합니다.
- 마지막 조회 결과를 `filesDir/tour_cache.json`에 저장해 통신이 끊겨도 직전 지역은 안내합니다.
- 공공누리 출처 표시 조건에 따라 화면 아래에 "출처: 한국관광공사 TourAPI"를 표시합니다.

Vehicle SDK의 제어(CONTROL_*) 권한은 **사용하지 않습니다**. 심사 부담이 적은 조회 권한만 씁니다.

## 주요 기능
- **넉넉한 안내 거리**: 현재 속도로 45초 동안 달리는 거리만큼 미리 안내합니다(최소 800m, 최대 3km). 시속 60km면 약 800m, 시속 100km면 약 1.25km 앞에서 시작해서, 안내와 "들러줘" 응답 뒤에도 경유지를 반영하고 차선을 바꿀 여유가 15초 이상 남습니다. 이미 지나친 뒤쪽 장소는 안내하지 않습니다.
- **유명한 곳 우선**: 직접 고른 대표 명소(35곳)를 먼저 안내합니다. TourAPI 결과는 사진이 등록된 곳만 받고, 분류체계로 역사 유적·산·강/바다·랜드마크·테마파크·백화점·전통시장·음식 거리만 남깁니다. 근린공원·도서관·산업시설과 개별 소규모 음식점은 제외합니다.
- **방향 안내**: 진행 방향(heading)과 장소의 방위각을 비교해 "오른편, 왼편, 앞쪽"으로 안내
- **중복 방지**: 같은 장소는 30분 쿨다운. 근접 알림과 위치 판정 경로가 겹쳐도 한 번만 안내
- **대기열**: 안내 중에 다른 장소에 진입하면 가장 최근 장소 하나만 대기시킨 뒤 이어서 안내
- **음성 응답 해석**: '들러줘·가자·응'은 들르기, '다시·다시 듣기'는 다시 듣기, '괜찮아·다음에·건너뛰기'는 넘어가기로 처리. 부정 표현을 먼저 검사해 "안 들러도 돼" 같은 말을 방문으로 오해하지 않음
- **한국어 조사 처리**: 받침에 맞춰 이/가, 을/를, 으로/로 선택
- **포그라운드 서비스**: 내비 화면이 앞에 있어도 가이드 유지 (`mediaPlayback` 타입)
- **데모 주행**: 서울역 → 숭례문 → 경복궁 → 창덕궁 → DDP → 롯데월드타워를 가상으로 주행해 에뮬레이터에서도 흐름 시연 가능

## 프로젝트 구조
```
app/src/main/java/com/hdcontest/roadstory/
├── core/                 # 순수 Kotlin 로직 (단위 테스트 대상)
│   ├── GeoUtils.kt         거리·방위각·좌우 판정
│   ├── NarrationPlanner.kt 안내 대상 결정 (진입/쿨다운)
│   ├── NarrationScript.kt  안내 문장 생성, 조사 처리
│   ├── VoiceIntent.kt      음성 응답 해석
│   └── TourApi.kt          TourAPI 응답 파싱, 개요 요약, 내장 명소와 병합, 재조회 정책
├── data/                 # TourApiClient(HTTP) / TourPoiProvider(조회·캐시)
├── location/             # PleosLocationSource(FusedLocation) / DemoLocationSource
├── voice/VoiceGuide.kt   # TTS + STT 래퍼 (HYBRID/ON_DEVICE 자동 선택)
├── navi/NaviBridge.kt    # NaviHelper 래퍼
├── GuideEngine.kt        # 전체 흐름 상태 머신 (StateFlow)
├── GuideService.kt       # 포그라운드 서비스
└── MainActivity.kt       # 큰 버튼 3개 + 주변 명소 목록
app/src/main/assets/pois.json   # 직접 고른 대표 명소·맛집 거리 (35곳)
```

## 빌드 및 실행
1. Android Studio에서 `road-story` 폴더를 엽니다. JDK는 17 또는 21을 쓰고, Android Studio 기본 JBR이면 됩니다.
2. Pleos Playground 문서에 따라 **Pleos Connect Emulator(AVD)** 를 설치하고 실행합니다.
3. (선택) 서버 음성 엔진을 쓰려면 `local.properties`에 My Project 자격 증명을 넣습니다. 비워 두면 온디바이스 엔진으로 동작합니다.
   ```properties
   pleos.clientId=발급받은_CLIENT_ID
   pleos.clientSecret=발급받은_CLIENT_SECRET
   ```
   TourAPI로 실시간 주변 장소를 받으려면 [공공데이터포털](https://www.data.go.kr/data/15101578/openapi.do)에서 "한국관광공사_국문 관광정보 서비스_GW" 활용 신청을 한 뒤, `.env.example`을 `.env`로 복사해 인증키를 넣습니다. Encoding 키와 Decoding 키 어느 쪽이든 됩니다. `.env`는 git에 올라가지 않습니다. 키가 없으면 내장 명소만 안내합니다.
   ```bash
   cp .env.example .env
   # .env
   TOUR_API_KEY=발급받은_인증키
   ```
4. `app`을 실행하고 **데모 주행**을 켠 뒤 **가이드 시작**을 누릅니다.

빌드된 release APK는 저장소 루트의 [`apk/나그네들_여긴어디_release.apk`](../apk/나그네들_여긴어디_release.apk)에 있습니다.

명령줄에서 빌드하고 테스트하려면 아래 명령을 씁니다.
```bash
./gradlew assembleDebug testDebugUnitTest
```

## 확인된 것과 확인이 필요한 것
- ✅ `assembleDebug` 빌드, 단위 테스트 49개 통과
- ✅ TourAPI 실제 키로 에뮬레이터 데모 주행 확인 (판교 주변 관광지·맛집 조회, 개요 안내, 출처 표시)
- ⚠️ 공공데이터포털 개발 계정은 하루 호출 수 제한이 있습니다. 상용 단계에서는 운영 계정 전환이나 서버 캐시가 필요합니다.
- ✅ Pleos Connect 에뮬레이터에서 데모 주행으로 TTS 안내 → 음성 "들러줘" 인식(STT) → NaviHelper 목적지 설정 → 내비게이션 길 안내까지 확인
- ⚠️ 실차에서는 아직 실행해 보지 않았습니다.
- ⚠️ `pois.json`의 좌표와 설명은 시연용으로 직접 작성했습니다. 공개 전에 한국관광공사 TourAPI 같은 공식 데이터로 교체하거나 검수하는 것을 권장합니다.
- ⚠️ NaviHelper에 경유지를 요청할 때 내비 POI ID 없이 좌표만 보냅니다(`poiId = ""`). 내비 앱이 POI ID를 요구하면 검색 연동이 필요할 수 있습니다.
- ⚠️ 문서에는 FusedLocation 1.1.2로 안내되어 있지만 Maven 저장소에는 1.1.3만 있어서 1.1.3을 씁니다.
- `distractionOptimized=true`를 선언했습니다. 주행 중 화면 정책은 Pleos 앱 심사 가이드라인에 맞춰 재확인이 필요합니다.
