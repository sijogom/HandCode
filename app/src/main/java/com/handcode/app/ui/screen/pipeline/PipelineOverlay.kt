package com.handcode.app.ui.screen.pipeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val OVERLAY_BG_ALPHA = 0.55f

/**
 * 파이프라인 진행 중 단계(Recognizing / Validating / Executing) 에 한해
 * 반투명 배경 + 스피너 + 한글 안내를 띄우는 오버레이.
 *
 * 짧은 상태 전환에서 깜빡임을 줄이기 위해 [AnimatedVisibility] 로 페이드 처리.
 */
@Composable
fun PipelineOverlay(state: PipelineState) {
    val message = state.loadingMessage()
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        // message 가 null 이면 렌더되지 않지만, 컴파일러 null 체크용 로컬 변수.
        val safeMessage = message ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = OVERLAY_BG_ALPHA)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = safeMessage, color = Color.White)
            }
        }
    }
}

/**
 * 로딩 단계만 한글 문구로 매핑. 그 외에는 null → 오버레이를 숨긴다.
 */
private fun PipelineState.loadingMessage(): String? = when (this) {
    is PipelineState.Recognizing -> "손글씨를 읽고 있어요"
    is PipelineState.Validating -> "문법을 확인하고 있어요"
    is PipelineState.Executing -> "코드를 실행하고 있어요"
    else -> null
}
