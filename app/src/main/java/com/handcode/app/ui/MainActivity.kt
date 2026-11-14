package com.handcode.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.handcode.app.data.preferences.SettingsRepository
import com.handcode.app.domain.model.AppSettings
import com.handcode.app.ui.navigation.HandCodeNavGraph
import com.handcode.app.ui.navigation.Screen
import com.handcode.app.ui.theme.HandCodeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 앱 단일 Activity.
 *
 * 6주차 변경:
 *  - SettingsRepository 를 Hilt 로 주입받아 앱 테마/폰트/그리드 설정을 전역 적용.
 *  - 초기값은 [AppSettings.Default] (시스템 테마) 를 사용하고, Flow 구독이 도달하면 실제 설정으로 전환.
 *    메인 스레드 runBlocking 을 제거해 ANR 위험 없음. 첫 프레임이 Default 로 그려지지만
 *    DataStore 값이 즉시 도착하므로 체감 깜빡임은 거의 없다.
 *
 * 2026-04-19 MediaPipe GenAI 전환:
 *  - 과거 ModelSetup 라우트 진입 분기 제거. MediaPipe 는 Play Store 100MB 제한으로 APK 번들이
 *    불가능하고, 모델은 [com.handcode.app.data.ocr.mediapipe.LiteRtLmModelInstaller] 의 SAF 복사
 *    경로로 설정 화면에서 사용자가 직접 선택한다. 앱 진입은 항상 Home 으로.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settings by remember {
                settingsRepository.settings
            }.collectAsState(initial = AppSettings.Default)

            HandCodeTheme(settings = settings) {
                val navController = rememberNavController()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HandCodeNavGraph(
                        navController = navController,
                        startDestination = Screen.Home.route,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}
