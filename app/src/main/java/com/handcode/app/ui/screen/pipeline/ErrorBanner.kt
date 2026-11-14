package com.handcode.app.ui.screen.pipeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 실패 원인별로 다른 색/문구/액션 라벨을 보여주는 상단 배너.
 *
 * "어디로 돌아갈 수 있는지" 까지 알려주는 것이 핵심.
 * 사용자가 "처음부터 다시" 와 "이 자리에서 고치기" 를 구분할 수 있어야 한다.
 *
 * 8주차 8차 변경:
 *  - [errorDetails] 파라미터 추가. 채워지면 본 메시지 아래에 단일 폭 글꼴로 cause 한 줄을 노출.
 *    ENGINE_UNAVAILABLE / UNKNOWN 에서 사용자가 진짜 원인을 화면에서 즉시 확인할 수 있게 한다.
 *    (이전엔 Timber.e 로만 남아 logcat 봐야 알 수 있었던 정보를 화면으로 끌어올린다.)
 */
@Composable
fun ErrorBanner(
    reason: FailureReason,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    errorDetails: String? = null
) {
    val spec = reason.toBannerSpec()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(spec.bannerColor)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = spec.message,
                color = Color.White,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            )
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = spec.bannerColor
                )
            ) {
                Text(spec.actionLabel)
            }
        }
        if (!errorDetails.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = errorDetails,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

/**
 * FailureReason -> (색상, 안내 문구, 액션 버튼 라벨) 매핑.
 * when 으로 일괄 정의하여 새 실패 원인 추가 시 컴파일러가 누락을 잡아준다.
 */
private fun FailureReason.toBannerSpec(): BannerSpec = when (this) {
    FailureReason.SYNTAX_ERROR -> BannerSpec(
        bannerColor = Color(0xFFD32F2F), // 빨강
        message = "문법 오류가 있어요. 에디터에서 코드를 고쳐 주세요.",
        actionLabel = "고치기"
    )
    FailureReason.RUNTIME_ERROR -> BannerSpec(
        bannerColor = Color(0xFFF57C00), // 주황
        message = "실행 중 오류가 발생했어요. 코드를 확인하고 다시 실행해 주세요.",
        actionLabel = "다시 실행"
    )
    FailureReason.OCR_FAILED -> BannerSpec(
        // 기존 #FBC02D(노랑) 은 흰 글씨와 WCAG AA 대비 미달 — 어두운 노랑(#F57F17)으로 교체.
        bannerColor = Color(0xFFF57F17),
        message = "글씨를 알아보기 힘들어요. 조금 더 선명한 사진으로 다시 찍어 볼까요?",
        actionLabel = "다시 촬영"
    )
    FailureReason.TIMEOUT -> BannerSpec(
        bannerColor = Color(0xFFF57C00), // 주황
        message = "실행이 너무 오래 걸려서 중단했어요. 무한 루프가 없는지 확인해 주세요.",
        actionLabel = "중단하기"
    )
    FailureReason.UNKNOWN -> BannerSpec(
        // 분류 불가 상태 — 회색/어두운 톤으로 RUNTIME/ENGINE 과 시각적으로 구분.
        bannerColor = Color(0xFF616161),
        message = "알 수 없는 오류가 발생했어요. 다시 시도해 주세요.",
        actionLabel = "확인"
    )
    FailureReason.ENGINE_UNAVAILABLE -> BannerSpec(
        // 엔진 장애는 치명적 상태이므로 문법 오류보다 짙은 빨강으로 구분.
        bannerColor = Color(0xFFC62828),
        message = "코드 실행 엔진을 불러오지 못했어요. 앱을 다시 시작해 주세요.",
        actionLabel = "확인"
    )
}

/** 배너 한 줄의 시각 사양. */
private data class BannerSpec(
    val bannerColor: Color,
    val message: String,
    val actionLabel: String
)
