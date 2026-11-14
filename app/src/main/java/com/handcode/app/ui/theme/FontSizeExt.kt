package com.handcode.app.ui.theme

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.handcode.app.domain.model.FontSize

// 코드/출력 영역 폰트 크기 상수. Editor / Result 화면에서 동일 매핑으로 사용.
private const val CODE_FONT_SMALL_SP = 12
private const val CODE_FONT_MEDIUM_SP = 14
private const val CODE_FONT_LARGE_SP = 18

/** 설정값(FontSize) → Compose sp 변환. 화면별 중복 정의를 없애기 위한 공용 확장. */
fun FontSize.toCodeSp(): TextUnit = when (this) {
    FontSize.SMALL -> CODE_FONT_SMALL_SP.sp
    FontSize.MEDIUM -> CODE_FONT_MEDIUM_SP.sp
    FontSize.LARGE -> CODE_FONT_LARGE_SP.sp
}
