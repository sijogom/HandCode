package com.handcode.app.domain.model

/**
 * 앱 전역 설정 모델. DataStore 에 저장되며 테마/폰트/카메라 그리드 표시를 제어한다.
 *
 * enum 을 사용해 DataStore 에는 name() 문자열로만 저장 → 마이그레이션 부담 최소화.
 */

enum class ThemeMode {
    /** 시스템 다크 모드 설정을 따른다. */
    SYSTEM,

    /** 라이트 고정. */
    LIGHT,

    /** 다크 고정. */
    DARK
}

enum class FontSize {
    /** 작게 (12sp 기준) */
    SMALL,

    /** 보통 (14sp 기준) */
    MEDIUM,

    /** 크게 (18sp 기준) */
    LARGE
}

data class AppSettings(
    val theme: ThemeMode,
    val codeFontSize: FontSize,
    val cameraGridVisible: Boolean
) {
    companion object {
        /** 최초 실행 시 기본값. */
        val Default = AppSettings(
            theme = ThemeMode.SYSTEM,
            codeFontSize = FontSize.MEDIUM,
            cameraGridVisible = true
        )
    }
}
