package com.handcode.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.handcode.app.ui.screen.camera.CameraScreen
import com.handcode.app.ui.screen.editor.EditorScreen
import com.handcode.app.ui.screen.home.HomeScreen
import com.handcode.app.ui.screen.records.RecordListScreen
import com.handcode.app.ui.screen.result.ResultScreen
import com.handcode.app.ui.screen.settings.SettingsScreen
import com.handcode.app.ui.screen.stats.StatsScreen

/**
 * 앱 전체 Navigation 그래프.
 *
 * 5주차: Home → Camera → Editor → Result 흐름
 * 6주차: RecordList / Stats / Settings 라우트 추가 (홈에서 진입)
 * 2026-04-19 MediaPipe GenAI 전환: ModelSetup 라우트 제거, 진입은 항상 Home.
 *  모델(`.litertlm`) 선택은 Settings 의 "모델 선택" SAF 버튼에서 수행.
 */
@Composable
fun HandCodeNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onCameraClick = {
                    navController.navigate(Screen.Camera.route)
                },
                onRecordClick = {
                    navController.navigate(Screen.Editor.route)
                },
                onRecordListClick = {
                    navController.navigate(Screen.RecordList.route)
                },
                onStatsClick = {
                    navController.navigate(Screen.Stats.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Camera.route) {
            CameraScreen(
                onBack = { navController.popBackStack() },
                onNavigateToEditor = {
                    navController.navigate(Screen.Editor.route) {
                        popUpTo(Screen.Camera.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Editor.route) {
            EditorScreen(
                onNavigateToResult = {
                    navController.navigate(Screen.Result.route) {
                        launchSingleTop = true
                    }
                },
                onBack = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Result.route) {
            ResultScreen(
                onRetry = {
                    navController.navigate(Screen.Editor.route) {
                        popUpTo(Screen.Editor.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onRetake = {
                    navController.navigate(Screen.Camera.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.RecordList.route) {
            RecordListScreen(
                onBack = { navController.popBackStack() },
                onRecordClick = { _ ->
                    // 카드 탭 시 PipelineStore 세션 복원은 RecordListViewModel.openRecord 에서 처리.
                    // 여기서는 세션 준비 완료 후의 네비게이션만 담당.
                    navController.navigate(Screen.Editor.route)
                }
            )
        }

        composable(Screen.Stats.route) {
            StatsScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
