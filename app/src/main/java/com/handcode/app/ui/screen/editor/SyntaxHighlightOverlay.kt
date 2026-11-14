package com.handcode.app.ui.screen.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * 편집기 BasicTextField 뒤에 깔려 문법 오류 라인 배경을 빨간 음영으로 칠한다.
 *
 * 입력 좌표계는 `EditorScreen` 에서 계산해 전달:
 *  - [errorLines]: 1-indexed 라인 번호 (Python `SyntaxErrorInfo.line` 규약).
 *  - [lineHeightPx]: `codeFontSize × LINE_HEIGHT_RATIO` 를 px 로 환산한 값.
 *  - [topPaddingPx]: `EDITOR_INNER_PADDING_DP` 와 동일 px.
 *
 * TextField/LineNumberColumn 과 같은 줄 높이를 쓰므로 스크롤/줄바꿈과 함께 자연스럽게 움직인다.
 */
@Composable
fun SyntaxHighlightOverlay(
    errorLines: Set<Int>,
    lineHeightPx: Float,
    topPaddingPx: Float,
    modifier: Modifier = Modifier
) {
    if (errorLines.isEmpty()) return
    Canvas(modifier = modifier.fillMaxSize()) {
        errorLines.forEach { line ->
            val y = topPaddingPx + (line - 1).coerceAtLeast(0) * lineHeightPx
            drawRect(
                color = Color.Red.copy(alpha = 0.15f),
                topLeft = Offset(0f, y),
                size = Size(size.width, lineHeightPx)
            )
        }
    }
}
