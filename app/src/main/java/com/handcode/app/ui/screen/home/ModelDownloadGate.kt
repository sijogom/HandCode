package com.handcode.app.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 첫 실행 시 Gemma-3n 모델 다운로드 안내 다이얼로그.
 *
 * 표시 조건 (호출 측 책임): 모델 미설치 + 사용자가 같은 세션에서 [나중에] 안 누름.
 * 라이선스 동의 체크박스가 활성화되어야 [동의하고 다운로드] 버튼이 enable.
 *
 * 디자인 결정:
 *  - `AlertDialog` 사용 — Compose Material3 표준, 시스템 백 버튼 자동 처리
 *  - 체크박스 상태는 화면 내부 [remember] 로만 보관, 동의 시점에서야 [SettingsRepository] 에 영구 저장
 *  - 약관 링크 외부 브라우저 (`Intent.ACTION_VIEW`) — 호출 측에서 [onOpenLicense] 핸들러 주입
 */
@Composable
fun ModelDownloadGateDialog(
    licenseAccepted: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onOpenLicense: () -> Unit,
) {
    var checked by remember { mutableStateOf(licenseAccepted) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gemma-3n 모델 다운로드 필요") },
        text = {
            Column {
                Text(
                    text = "손글씨 코드 인식을 위해 Gemma-3n 모델 (약 3.4GB) 을 받아야 합니다. " +
                        "Wi-Fi 환경을 권장하며, 다운로드 후에는 오프라인에서 동작합니다.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(onClick = onOpenLicense) { Text("Gemma 이용 약관 열기") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text(
                        text = "약관을 읽었으며 동의합니다",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                enabled = checked
            ) {
                Text(if (checked) "동의하고 다운로드" else "약관 동의 필요")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("나중에")
            }
        }
    )
}

/**
 * 홈 화면 상단의 다운로드 진행률 / 에러 배너.
 *
 * 상태 분기:
 *  - [error] 가 있으면 빨간 배너 + [설정으로 가기] 버튼 (사용자가 Settings 에서 재시도/URL 변경 가능)
 *  - [progress] 가 있으면 파란 배너 + 진행률 + [취소] 버튼
 *  - 둘 다 null 이면 아무것도 그리지 않음 (Composable 호출은 가벼움 — 빈 노드)
 *
 * Why 배너로 분리:
 *  - 다이얼로그를 닫고 다운로드 시작한 후에도 진행률을 사용자가 볼 수 있어야 함
 *  - 매번 다이얼로그를 띄우면 사용자가 다른 작업 (Settings/촬영) 못 함
 */
@Composable
fun ModelDownloadStatusBanner(
    progress: Float?,
    error: String?,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        error != null -> ErrorBannerContent(
            error = error,
            onOpenSettings = onOpenSettings,
            modifier = modifier
        )
        progress != null -> ProgressBannerContent(
            progress = progress,
            onCancel = onCancel,
            modifier = modifier
        )
        else -> Unit
    }
}

@Composable
private fun ErrorBannerContent(
    error: String,
    onOpenSettings: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFC62828).copy(alpha = 0.10f))
            .padding(12.dp)
    ) {
        Text(
            text = "모델 다운로드 실패",
            color = Color(0xFFC62828),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpenSettings) { Text("설정에서 다시 시도") }
        }
    }
}

@Composable
private fun ProgressBannerContent(
    progress: Float,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .padding(12.dp)
    ) {
        Text(
            text = formatProgressText(progress),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (progress > 0f) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.padding(horizontal = 6.dp))
            TextButton(onClick = onCancel) { Text("취소") }
        }
    }
}

private fun formatProgressText(progress: Float): String = when {
    progress <= 0f -> "다운로드 시작 중 (서버 응답 대기)…"
    else -> "Gemma-3n 다운로드 중 %.1f%%".format(progress * 100f)
}
