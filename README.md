# HandCode

손으로 쓴 Python 코드를 촬영해 텍스트로 변환하고 편집·문법 검사·실행하는 Android 애플리케이션입니다. Gemma-3n 모델을 기기에 가져온 뒤 MediaPipe GenAI로 추론하며, Python 검증·실행에는 Chaquopy를 사용합니다.

## 주요 기능과 구성

- CameraX 촬영, 이미지 회전·크기 조정
- 코드 인식, 들여쓰기 복원, 토큰 보정과 사용자 편집
- Python `ast` 문법 검증과 앱 내 실행
- Room 기반 기록 저장, 설정과 학습 통계
- Kotlin·Jetpack Compose·Hilt 기반 앱 구조

소스는 `app/src/main/`, 단위 테스트는 `app/src/test/`에 있습니다. 모델 파일·개인 코드 기록·실제 촬영 이미지는 포함하지 않습니다.

## 빌드

JDK 17, Android SDK API 34, Build Tools 35.0.0이 필요합니다. 앱은 Android 7.0(API 24) 이상과 `arm64-v8a`를 대상으로 하며, 모델 실행 가능 여부는 기기 자원에 따라 달라집니다.

```powershell
git clone https://github.com/sijogom/HandCode.git
cd HandCode
Copy-Item local.properties.example local.properties
# local.properties의 sdk.dir를 자신의 Android SDK 경로로 수정합니다.
.\gradlew.bat testDebugUnitTest assembleDebug
```

결과 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 릴리스 빌드는 자신의 서명 설정을 사용합니다.

## 모델 준비와 사용

앱의 모델 설정에서 호환되는 Gemma-3n E2B int4 `.litertlm` 파일을 직접 선택합니다. 기본 다운로드 안내 주소는 소스의 `GemmaModelManager.kt`에서 관리하며, 설정에서 주소를 변경할 수 있습니다. 모델 배포자의 접근 조건·라이선스를 확인하세요. 모델 다운로드와 가져오기가 끝나기 전에는 OCR을 사용할 수 없습니다.

촬영 → 인식 → 편집·문법 검사 → 실행 → 결과 확인 순서로 사용합니다. 촬영 시 코드 전체가 선명하게 보이도록 맞추고, 실행 전 편집 화면에서 인식 결과를 확인합니다.
