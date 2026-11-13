# 케어마일 (CARE MILE)

## 1. 앱 이름 및 한 줄 소개

**케어마일 (CARE MILE)**: 평소 운행 이력과 소모품 관리 기록을 바탕으로 다음 정비를 계획하고 예약까지 이어 주는 Android Automotive 앱

프로젝트명은 **운행 이력과 소모품 관리를 연결하는 맞춤 정비·예약 서비스**입니다.

차가 주차(P)할 때마다 이번 운행의 거리, 시간, 정차 시간, 고속 비율, 급가감속, 브레이크 조작, 외기온을 운행 1회로 저장합니다. 최근 30일 운행이 10회 이상 쌓이면 취급설명서의 가혹 조건에 해당하는지 판정하고, 해당하면 가혹 조건 정비 주기를 적용합니다. 기한 안에서 정비소 작업, 내 시간대, 예산에 맞는 묶음 방문을 추천하고, 평소 차를 쓰지 않는 시간을 먼저 제안합니다.

| 항목 | 내용 |
| --- | --- |
| 앱 버전 | 0.1.0 (versionCode 1) |
| 패키지 | `io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler` |
| 시연 영상 | [demo-video/케어마일-시연영상.mp4](demo-video/케어마일-시연영상.mp4) (약 78초, 자막 `.srt` 포함) |

### 폴더 구성

| 위치 | 내용 |
| --- | --- |
| `apk/` | 바로 설치할 수 있는 release APK |
| `project/` | 앱 소스 전체 (Android Studio 프로젝트) |
| `demo-video/` | 시연 영상과 자막 |

## 2. 실행 방법

### 2.1 APK로 실행 (권장)

1. Pleos Connect 에뮬레이터를 켭니다. 준비 방법은 [3. 테스트 환경 및 필요한 설정](#3-테스트-환경-및-필요한-설정)에 있습니다.
2. 저장소 최상위 폴더에서 APK를 설치하고 실행합니다. `-g`를 붙이면 차속 권한이 설치할 때 함께 허용됩니다.

```bash
adb install -r -g apk/설계3.5팀_CareMile_release.apk
adb shell am start -n io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler/.MainActivity
```

에뮬레이터 앱 목록에서 **케어마일**을 눌러 실행해도 됩니다.

같은 패키지가 다른 서명(디버그 빌드 등)으로 이미 설치돼 있으면 설치가 거부됩니다. 이때는 먼저 지운 뒤 다시 설치합니다.

```bash
adb uninstall io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler
```

샘플 데이터를 처음 상태로 되돌리려면 앱의 **07 샘플·연결 → 샘플 초기화**를 누르거나 아래 명령을 실행합니다. Pleos 에뮬레이터는 앱을 사용자 10으로 실행하므로 `--user current`가 필요합니다.

```bash
adb shell pm clear --user current io.github.myshihi.pleosproject26.m01vehiclemaintenancescheduler
```

### 2.2 소스에서 빌드해 실행

1. Android Studio에서 **`project` 폴더**를 엽니다(Open).
2. Gradle JDK는 Android Studio에 들어 있는 JBR(JDK 17 이상)을 씁니다.
3. 실행 기기로 Pleos Connect 에뮬레이터를 고르고 `app`을 실행(Run)합니다.

명령줄로 빌드하려면 다음과 같이 합니다.

```bash
cd project
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

서명 키는 저장소에 넣지 않았습니다. 그래서 소스에서 `assembleRelease`로 빌드하면 서명되지 않은 APK가 나옵니다. 소스 빌드는 debug로 실행하고, release 버전은 `apk/` 폴더의 APK로 확인해 주세요.

### 2.3 사용 순서 (샘플 모드)

처음 실행하면 가상 차량 A의 샘플 데이터로 시작합니다.

1. **01 관리 현황**: '내 차 맞춤 정비 기준' 카드에서 운행 이력으로 적용된 가혹 조건과 바뀐 정비 주기를 봅니다. 항목마다 거리와 기간 중 먼저 오는 조건을 보여 줍니다.
2. **02 운행 이력**: 판정 근거(측정값과 앱 기준)를 확인합니다. 운행 패턴을 '고른 운행', '도심 정체', '장거리 고속'으로 바꾸면 적용되는 조건과 주기가 달라집니다. **제외**로 판정을 뺄 수도 있습니다.
3. **03 정비 계획**: 기한 안의 묶음 방문 추천과 '평소 차를 쓰지 않는 시간' 표시를 확인합니다.
4. **04 방문·예약**: 샘플 예약을 요청하고 접수·확정 과정을 확인합니다. 방문일이 되면 **방문 결과 입력**에서 실제로 받은 작업만 기록합니다.
5. **05 정비 기록**, **06 내 조건**: 점검·교체 기록과 방문 시간대·예산·선호 정비소를 바꿔 봅니다.
6. **07 샘플·연결**: 타이어 저압이나 브레이크 오일 부족을 켜면 묶음 예약과 따로 '즉시 확인' 카드가 나옵니다.

## 3. 테스트 환경 및 필요한 설정

### 3.1 빌드 환경

| 항목 | 내용 |
| --- | --- |
| 언어 | Kotlin |
| 빌드 도구 | Android Gradle Plugin 9.4.1, Gradle 9.6.0 |
| SDK | compileSdk 37, minSdk 30, targetSdk 37 |
| 차량 SDK | Pleos Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle:2.3.2`) |
| 실행 시 권한 | 차속 `android.car.permission.CAR_SPEED` (운행 기록) |

### 3.2 에뮬레이터 준비

1. Android Studio → SDK Manager → SDK Update Sites에 Pleos Connect 시스템 이미지 주소를 추가합니다.
   - Windows / Linux: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - macOS (Apple Silicon): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. SDK Platforms에서 **Show Package Details**를 켜고, API 34의 **Pleos Connect 2.1.0** 이미지를 설치합니다.
3. Device Manager → Create Device → Automotive → **Pleos Connect 2.1.0**으로 기기를 만들고 실행합니다.
4. 소스에서 빌드하려면 Android SDK Platform 37도 설치해야 합니다. Vehicle SDK는 빌드할 때 Pleos 공개 Maven 저장소에서 자동으로 받으므로 인터넷 연결이 필요합니다.

### 3.3 실제 차량 모드로 운행 기록하기 (선택)

1. 기어 P에서 앱의 **07 샘플·연결 → 실제 차량 입력**으로 바꾸고, 차속 권한을 허용합니다. **01 관리 현황**에서 차종을 확인합니다.
2. 에뮬레이터의 Virtual Console(<http://127.0.0.1:8088/>)에서 기어를 **D**로 바꾸고 가속과 브레이크를 조작한 뒤 기어를 **P**로 돌립니다. 주행 중에는 앱이 확인 전용 화면으로 바뀌고 입력이 잠깁니다.
3. P가 되면 '운행 종료' 안내가 나오고, **02 운행 이력**에 이번 운행이 추가됩니다. 운행이 10회 이상 쌓이면 맞춤 판정을 시작합니다.

### 3.4 정비 기준과 시연용 값

- 정비 주기는 현대자동차 GN7HEV 2023 취급설명서의 [통상 조건](https://ownersmanual.hyundai.com/full_webhelp/GN7HEV/2023/ko_KR/id6b219dd157c.html)과 [가혹 조건](https://ownersmanual.hyundai.com/full_webhelp/GN7HEV/2023/ko_KR/id7b77f1c5ffe.html) 표에서 옮겼습니다(2026-11-11 확인). 다른 차종이나 차종을 확인하기 전인 차량에는 일정을 계산하지 않습니다.
- 운행 이력으로 가혹 조건을 판정하는 기준값은 취급설명서에 수치가 없어 앱이 정한 초안입니다. 정비 전문가 검토 전이며, 화면에 기준값을 그대로 보여 줍니다.
- 샘플 정비소의 이름, 가격, 빈자리는 시연용 가상 값입니다. 실제 예약 서비스와는 연결하지 않았습니다.

### 3.5 확인한 범위

- release APK를 Pleos Connect 2.1.0 에뮬레이터에 설치해 실행했습니다. 샘플 모드에서 관리 현황, 정비 계획, 샘플 예약 확정, 방문 결과 입력을 확인했습니다.
- 실제 차량 모드에서 기어, 차속, 외기온, 주행거리, 브레이크 페달 신호를 받았고, Virtual Console로 D → P 주행을 해 운행 1회가 기록되는 것을 확인했습니다. 브레이크 오일과 타이어 신호는 이 에뮬레이터가 지원하지 않습니다.
- 실제 차량, 실제 예약 서비스 연결, 움직이는 차량에서의 주행 중 화면, 모든 화면의 수동 점검은 확인하지 않았습니다.

## 4. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

| 항목 | 내용 |
| --- | --- |
| 에뮬레이터 | Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34, x86_64) |
| 화면 규격 | 2560 × 1440 px, 160 dpi, 가로 화면 |

## 5. APK 파일 위치

**[apk/설계3.5팀_CareMile_release.apk](apk/설계3.5팀_CareMile_release.apk)**

- 디버그 모드를 끈 release 빌드입니다.
- APK Signature Scheme v2로 서명했고, `apksigner verify`로 서명을 확인했습니다.
- 샘플 데이터가 앱 안에 들어 있어 로그인이나 인터넷 연결 없이 실행됩니다.
