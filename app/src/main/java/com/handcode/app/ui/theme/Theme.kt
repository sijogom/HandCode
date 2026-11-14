package com.handcode.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.handcode.app.domain.model.AppSettings
import com.handcode.app.domain.model.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    secondary = Color(0xFF81C784),
    tertiary = Color(0xFFFFB74D),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0288D1),
    secondary = Color(0xFF388E3C),
    tertiary = Color(0xFFF57C00),
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
)

/**
 * 앱 루트 테마 컴포저블.
 *
 * 6주차 변경:
 * - settings: AppSettings 파라미터 추가. theme 필드로 SYSTEM/LIGHT/DARK 결정.
 * - LocalAppSettings 를 CompositionLocalProvider 로 하위 트리에 주입
 *   (EditorScreen, CameraScreen 이 폰트/그리드 설정을 읽을 수 있도록).
 */
@Composable
fun HandCodeTheme(
    settings: AppSettings = AppSettings.Default,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = resolveDarkTheme(settings.theme)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(LocalAppSettings provides settings) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}

/** ThemeMode 에 따라 실제 darkTheme 여부를 결정. SYSTEM 은 isSystemInDarkTheme 위임. */
@Composable
private fun resolveDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
