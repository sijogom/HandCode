package com.handcode.app.ui.theme

import androidx.compose.runtime.compositionLocalOf
import com.handcode.app.domain.model.AppSettings

/**
 * 현재 앱 설정을 컴포지션 트리 아래로 전달하기 위한 CompositionLocal.
 *
 * EditorScreen / CameraScreen 등에서 LocalAppSettings.current 로 읽어
 * 코드 폰트 크기, 카메라 그리드 표시 여부 등을 참조한다.
 * 실제 값은 MainActivity 의 HandCodeTheme 내부에서 Provide 된다.
 */
val LocalAppSettings = compositionLocalOf { AppSettings.Default }
