package com.handcode.app.ui.navigation

/**
 * 앱 내 화면 정의. Compose Navigation 의 route 로 사용.
 *
 * 6주차에 RecordList / Stats / Settings 라우트 추가.
 * 2026-04-19 MediaPipe GenAI 전환으로 ModelSetup 라우트 제거.
 * 모델 선택은 Settings 화면의 SAF 버튼에서 직접 수행.
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object Editor : Screen("editor")
    data object Result : Screen("result")
    data object RecordList : Screen("record_list")
    data object Stats : Screen("stats")
    data object Settings : Screen("settings")
}
