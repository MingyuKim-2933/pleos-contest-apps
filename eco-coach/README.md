# 에코 드라이빙 코치 (EcoCoach)

주행 습관(급가속, 급제동, 과속, 정속 주행)을 실시간으로 분석해 **운전 중에는 짧은 음성으로** 코칭하고,
**주차하면** 점수 리포트와 개선 팁을 보여주는 Pleos Connect 앱입니다.

## 동작 흐름

```
차속·기어 (AAOS CarPropertyManager) ─┐
브레이크·주행모드·전비/연비·주행거리 (Pleos Vehicle SDK) ─┼→ TripAccumulator ─→ EcoScore ─→ 리포트 저장·표시
도로 제한속도 (Pleos NaviHelper) ─────┘        │
                                         EventDetector (급가속/급제동/과속)
                                               │
                                         CoachPolicy (간격·횟수 제한) → TextToSpeech
```

- **주행 시작**: 차속이 3km/h를 넘으면
- **주행 종료**: P단을 5초 유지하면 (기어 정보가 없으면 3분 정차). 화면의 "주행 종료" 버튼으로도 끝낼 수 있습니다.

## 사용한 API

| 출처 | 항목 | 권한 |
|---|---|---|
| Pleos Vehicle SDK 2.3.2 · Brake | 브레이크 페달 상태 | `pleos.car.permission.CAR_DRIVE` |
| Pleos Vehicle SDK · DrivingMode | ECO/NORMAL/SPORT | `CAR_DRIVE` |
| Pleos Vehicle SDK · Odometer | 누적 주행거리 | `CAR_DRIVE` |
| Pleos Vehicle SDK · EvBattery / Fuel | 순간·평균 전비(연비), 잔량, 파워트레인 판별 | `CAR_ENERGY` |
| Pleos NaviHelper 3.0.5 | `onCurrentRoadSpeedLimit` 제한속도 | `NAVI_ROUTE` |
| Pleos TextToSpeech 3.3.1 | 음성 코칭 | `TTS_SERVICE` |
| AAOS `CarPropertyManager` | `PERF_VEHICLE_SPEED`, `GEAR_SELECTION` | `android.car.permission.CAR_SPEED`, `CAR_POWERTRAIN` |

> Pleos Vehicle SDK에는 차속 API가 없고, 기어 API는 deprecated(“use the Google GEAR_SELECTION property”) 상태입니다.
> SDK 정책도 "AAOS 표준 API가 있으면 표준을 우선 사용"이라서 차속과 기어는 AAOS 표준 프로퍼티로 읽습니다.
> **차량 제어(CONTROL_*) 권한은 전혀 쓰지 않습니다.**

## 점수 계산 (0~100)
| 항목 | 기준 | 가중치 (EV / 내연기관) |
|---|---|---|
| 부드러운 가속 | 급가속(≥ 2.8 m/s²) 횟수, 허용치 = 2회 + 10km당 3회 | 30% / 25% |
| 부드러운 감속 | 급감속(≤ −3.4 m/s²) 횟수, 허용치 동일 | 30% / 25% |
| 제한속도 준수 | 제한속도 +10km/h 초과 시간 비율 | 20% / 20% |
| 일정한 속도 | 가감속이 0.8 m/s² 미만인 시간 비율 | 20% / 15% |
| 공회전 줄이기 | 정차 시간 비율 (내연기관만) | – / 15% |
| ECO 모드 보너스 | ECO 모드 주행 비율 × 5점 | |

등급은 90점 이상 에코 마스터, 75점 이상 에코 드라이버, 60점 이상 보통, 그 아래는 개선 필요입니다. 점수가 가장 낮은 항목 2개에 맞춘 팁을 보여줍니다.

## 운전 중 주의 분산 줄이기
- 주행 중 화면에는 **실시간 점수, 속도, 제한속도 표지만** 나오고, 상세 리포트는 정차 중에만 보입니다.
- 음성 코칭 사이에는 최소 10초 간격을 두고, 같은 종류의 지적은 60초 간격에 주행당 2회까지만 합니다.
- 10분 동안 이벤트가 없으면 칭찬 한마디를 합니다.

## 프로젝트 구조
```
app/src/main/java/com/hdcontest/ecocoach/
├── core/                  # 순수 Kotlin 로직 (단위 테스트 대상)
│   ├── EventDetector.kt     급가속·급감속·과속 감지
│   ├── TripAccumulator.kt   주행 통계 집계 (거리 적분, 모드 시간 등)
│   ├── EcoScore.kt          점수·등급·팁
│   └── CoachPolicy.kt       음성 코칭 문장·빈도 제어
├── vehicle/               # PleosSignalSource(실차) / DemoSignalSource(시연)
├── voice/VoiceCoach.kt    # TTS 래퍼
├── data/TripRepository.kt # 주행 기록 저장 (내부 저장소 JSON, 최근 50개)
├── TripEngine.kt          # 주행 시작/종료 상태 머신 (StateFlow)
├── ui/                    # ScoreRingView(원형 점수), HistoryBarView(최근 점수 막대)
└── MainActivity.kt
```

## 빌드 및 실행
1. Android Studio에서 `eco-coach` 폴더를 엽니다. JDK는 17 또는 21을 씁니다.
2. **Pleos Connect Emulator(AVD)** 에서 실행합니다. 첫 실행 때 차속 권한을 허용해 주세요.
3. (선택) `local.properties`에 `pleos.clientId`, `pleos.clientSecret`을 넣으면 서버 TTS를 씁니다. 비워 두면 온디바이스 엔진으로 동작합니다.
4. **데모 주행 시작**을 누르면 약 2분 15초 동안 가상 주행이 진행됩니다. 주행 흐름은 다음과 같습니다.
   부드러운 출발 → 급가속 (음성) → 제한속도 50 구간 과속 (음성) → 급제동 (음성) → 신호 대기 → ECO 정속 주행 → 주차 → 리포트와 음성 요약
   데모 결과는 1.4km, 75점, 에코 드라이버 등급이에요.

명령줄에서 빌드하고 테스트하려면 아래 명령을 씁니다.
```bash
./gradlew assembleDebug testDebugUnitTest
```

에뮬레이터의 Virtual Console에서 차속, 기어, 브레이크 값을 바꾸면 실차 경로(`PleosSignalSource`)도 테스트할 수 있습니다.

## 확인된 것과 확인이 필요한 것
- ✅ `assembleDebug` 빌드, 단위 테스트 14개 통과, Android Lint 오류 0개
- ✅ 데모 시나리오를 핵심 로직에 그대로 넣어 시뮬레이션했고, 급가속, 과속, 급제동 이벤트와 음성 3회가 의도대로 나오는 것을 확인했습니다.
- ⚠️ **Pleos Connect 에뮬레이터나 실차에서는 아직 실행해 보지 않았습니다.** 특히 AAOS 차속 권한(`CAR_SPEED`)을 서드파티 앱에 부여하는 방식은 Pleos 환경에서 꼭 확인해 주세요.
- ⚠️ 문서에는 `VehicleException.errorCode`가 공개 API로 나와 있지만, 배포된 Vehicle 2.3.2 바이너리에서는 난독화되어 쓸 수 없습니다. 그래서 오류 코드로 분기하지 않고 실패 시 한 번 재시도하는 방식으로 처리했습니다.
- ⚠️ 급가속, 급감속 기준값은 일반적인 운행기록 분석 기준을 참고한 값입니다. 실차 데이터로 보정하는 것을 권장합니다.
