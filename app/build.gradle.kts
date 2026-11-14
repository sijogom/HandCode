plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.chaquo.python")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.handcode.app"
    compileSdk = 34
    // 설치된 build-tools 34 의 라이선스 파일이 Program Files 보호 경로라 자동 수락 불가.
    // 이미 라이선스 수락된 35.0.0 을 명시적으로 사용.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.handcode.app"
        // Chaquopy 16 의 실제 하한이 minSdk 24(Android 7.0 Nougat) 이라 여기에 맞춘다.
        // 구형 태블릿 중 Android 6.0 이하는 Python 런타임 호환이 안 되어 설치 불가.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "com.handcode.app.HandCodeAndroidTestRunner"

        // Room 스키마 JSON 생성 (MigrationTestHelper 가 createDatabase 시 필요).
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        vectorDrawables {
            useSupportLibrary = true
        }

        // Chaquopy / ML Kit / MediaPipe 네이티브 라이브러리 ABI 제어.
        //  - MediaPipe GenAI + Gemma-3n E2B (~3.4GB .litertlm) 는 arm64-v8a 만 현실적.
        //  - 2026 년 이후 유효한 Android 신규 기기 대부분 64-bit.
        //  - llama.cpp / JNI 경로는 2026-04-19 전면 폐기되어 externalNativeBuild 불필요.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Google Play 등록은 단일 APK 배포가 기본(플레이스토어가 자동으로 ABI 필터).
    // splits 를 끄고 universal APK 한 개만 나오도록 한다.
    // 추후 Play Console 에 AAB 업로드 전환 시, bundle 블록으로 교체하면 됨.

    buildTypes {
        release {
            // 현재는 debug APK 만 배포한다. 추후 Play 정식 릴리스 시 아래를 전부 재활성:
            //   isMinifyEnabled = true
            //   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            //   signingConfig = signingConfigs.getByName("release")
            // 지금 상태로 releaseBuild 를 돌리면 proguard 가 Hilt/Room/Chaquopy 심볼을 잘라 런타임 크래시 위험이 있다.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 7주차: HandCodeApplication 에서 BuildConfig.DEBUG 로 Timber DebugTree 조건부 설치.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Room 스키마 JSON 을 Robolectric 단위 테스트의 assets 경로에 포함시킨다.
    // MigrationTestHelper.createDatabase(name, version) 이 이 경로에서 JSON 을 읽는다.
    // 실제 파일 위치: app/src/test/assets/com.handcode.app.data.local.AppDatabase/{3,4}.json
    sourceSets {
        getByName("test") {
            assets.srcDirs("src/test/assets")
        }
    }
}

// Chaquopy Python 설정 (android 블록 바깥)
chaquopy {
    defaultConfig {
        version = "3.11"
    }
}

// JUnit test worker 가 한글 경로의 test class 를 ClassNotFoundException 없이
// 로드하도록 sun.jnu.encoding/file.encoding 을 forked VM 에 명시적으로 상속시킨다.
// gradle.properties 의 org.gradle.jvmargs 는 Gradle daemon 에만 적용되고 test worker
// 서브프로세스에는 자동 상속되지 않는 경우가 있다.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dsun.jnu.encoding=UTF-8"
    )
}

dependencies {
    // Android 기본
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Material Icons Extended (CameraAlt, PhotoLibrary, Assessment 등 확장 아이콘 세트)
    implementation("androidx.compose.material:material-icons-extended")

    // Material Components XML 테마 (Theme.Material3.DayNight.NoActionBar 부모 제공)
    implementation("com.google.android.material:material:1.12.0")

    // Navigation (2주차부터 사용)
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // CameraX
    val cameraVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraVersion")
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")

    // EXIF 회전 보정 (3주차 ImagePreparer에서 사용)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ML Kit OCR (온디바이스) — 폴백 없이 남기지 않지만, 일부 포스트프로세스가 여전히 참조.
    // (단, MediaPipe 단일 엔진 전환 후에는 DI 바인딩 없음. 추후 완전 제거 시 이 줄도 삭제.)
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // MediaPipe GenAI LLM Inference API — Gemma-3n E2B .litertlm 멀티모달 추론 진입점.
    // tasks-vision 은 framework.image (BitmapImageBuilder, MPImage) 제공용.
    // 2026-04-20: 0.10.29 → 0.10.33 업그레이드. close() native race (사용자 보고
    //   "결과 화면 진입 시 튕김") 가 새 버전에서 fix 됐을 가능성 검증 차원.
    //   Maven 에 0.10.32, 0.10.33 둘 다 존재 (확인 완료). 빌드 실패 시 0.10.32 로 fallback.
    implementation("com.google.mediapipe:tasks-genai:0.10.33")
    implementation("com.google.mediapipe:tasks-vision:0.10.33")

    // Hilt DI (KSP)
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room DB (KSP)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // DataStore Preferences (6주차 앱 설정 저장)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Timber — 7주차 도입. 태그 기반 로그 필터링 (Logcat 노이즈 방지).
    implementation("com.jakewharton.timber:timber:5.0.1")

    // 테스트
    testImplementation("junit:junit:4.13.2")
    // Phase 1 Task 3: JVM 단위 테스트에서 Room 을 구동하기 위한 Robolectric 세트.
    // Task 14 리뷰에서 Robolectric 유지 vs androidTest 이관을 재검토한다.
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:$roomVersion")
    // InstantTaskExecutorRule — LiveData/Flow 테스트에서 Architecture Components executor 동기화.
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // Phase 1 Task 8: ModelInstallRepositoryImpl 단위 테스트에서 WorkManager 를 mock 하기 위해 추가.
    testImplementation("org.mockito:mockito-core:5.12.0")
    // Phase 1 Task 9: ModelSetupViewModel 단위 테스트 (StandardTestDispatcher, runTest, setMain 등).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.08.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // WorkManager / OkHttp — 2026-04-19 MediaPipe GenAI 전환으로 ModelDownloadWorker 와
    // Range 다운로드 클라이언트 전부 삭제됨. `.litertlm` 은 SAF OpenDocument 로 사용자가 직접 선택.

    // Task 11: HiltAndroidRule + HiltWorkerFactory 주입을 위한 Hilt 테스트 의존성.
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.51.1")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.51.1")
}
