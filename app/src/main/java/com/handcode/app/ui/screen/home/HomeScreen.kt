package com.handcode.app.ui.screen.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.handcode.app.domain.model.CodeRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CODE_PREVIEW_MAX_LINES = 2
private const val DATE_PATTERN = "yyyy.MM.dd HH:mm"

// Gemma 라이선스 외부 페이지 — Settings 와 동일 URL 을 첫 실행 안내 다이얼로그에서도 사용.
private const val GEMMA_LICENSE_URL = "https://ai.google.dev/gemma/terms"

// Material Design 권장 터치 타겟 최소 48dp 를 보장하기 위한 상수.
// 라벨 텍스트와 아이콘을 하나의 클릭 영역으로 묶을 때 이 크기를 최소 기준으로 쓴다.
private const val QUICK_NAV_MIN_TOUCH_DP = 48

/**
 * 홈 화면. 중앙에 촬영 버튼, 아래에 최근 코드 3개 카드.
 *
 * 6주차 추가:
 * - 하단에 학습 기록 / 통계 / 설정 으로 이동하는 IconButton 3개.
 */
@Composable
fun HomeScreen(
    onCameraClick: () -> Unit,
    onRecordClick: () -> Unit,
    onRecordListClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val records by viewModel.recentRecords.collectAsState()
    val showGate by viewModel.showGate.collectAsState()
    val licenseAccepted by viewModel.licenseAccepted.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()

    // Settings 에서 사용자가 모델을 직접 받았거나 SAF 로 설치했을 수 있어 화면 재진입 시 동기화.
    LaunchedEffect(Unit) {
        viewModel.refreshModelStatus()
    }

    val context = LocalContext.current
    val openLicense: () -> Unit = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GEMMA_LICENSE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        ModelDownloadStatusBanner(
            progress = downloadProgress,
            error = downloadError,
            onCancel = viewModel::cancelDownload,
            onOpenSettings = onSettingsClick,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "HandCode",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "손글씨 Python 코드를\n촬영하고 실행하세요",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        FloatingActionButton(
            onClick = onCameraClick,
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "코드 촬영",
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "촬영하기",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        RecentSection(
            records = records,
            onCardClick = { id ->
                viewModel.openRecord(id, onRecordClick)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        QuickNavRow(
            onRecordListClick = onRecordListClick,
            onStatsClick = onStatsClick,
            onSettingsClick = onSettingsClick
        )

        // 모델 미설치 + 사용자가 [나중에] 안 누른 상태에서만 다이얼로그 노출.
        // Compose Dialog 는 자체 Window 라 Column.children 에 넣어도 layout 영향 없음.
        if (showGate) {
            ModelDownloadGateDialog(
                licenseAccepted = licenseAccepted,
                onAccept = viewModel::acceptLicenseAndDownload,
                onDismiss = viewModel::dismissGate,
                onOpenLicense = openLicense
            )
        }
    }
}

@Composable
private fun QuickNavRow(
    onRecordListClick: () -> Unit,
    onStatsClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickNavItem(
            label = "학습 기록",
            onClick = onRecordListClick,
            content = {
                Icon(Icons.Default.List, contentDescription = "학습 기록")
            }
        )
        QuickNavItem(
            label = "통계",
            onClick = onStatsClick,
            content = {
                Icon(Icons.Default.Assessment, contentDescription = "통계")
            }
        )
        QuickNavItem(
            label = "설정",
            onClick = onSettingsClick,
            content = {
                Icon(Icons.Default.Settings, contentDescription = "설정")
            }
        )
    }
}

@Composable
private fun QuickNavItem(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    // 아이콘과 라벨을 하나의 클릭 영역으로 묶는다.
    // 기존에는 IconButton(아이콘)만 클릭 가능하고 라벨은 비활성 영역이라 미스히트가 잦았다.
    // Column 전체에 clickable + Role.Button 을 부여하고 최소 48dp 터치 타겟을 확보한다.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .sizeIn(minWidth = QUICK_NAV_MIN_TOUCH_DP.dp, minHeight = QUICK_NAV_MIN_TOUCH_DP.dp)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        // 기존에 내부 IconButton 을 두면 클릭 영역이 이중으로 걸리는 문제가 있었다.
        // 바깥 Column 의 clickable 하나로 일원화하고, 아이콘은 48dp 크기의 Box 로 감싸
        // 최소 터치 타겟과 시각적 중앙 정렬만 유지한다.
        Box(
            modifier = Modifier.size(QUICK_NAV_MIN_TOUCH_DP.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecentSection(
    records: List<CodeRecord>,
    onCardClick: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "최근 코드",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (records.isEmpty()) {
            EmptyRecordsHint()
        } else {
            records.forEach { record ->
                RecentCard(record = record, onClick = { onCardClick(record.id) })
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun EmptyRecordsHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "첫 코드를 찍어보세요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecentCard(record: CodeRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.title.ifBlank { "제목 없음" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatDate(record.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = record.code.lineSequence().take(CODE_PREVIEW_MAX_LINES)
                    .joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = CODE_PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat(DATE_PATTERN, Locale.KOREA)
    return formatter.format(Date(timestamp))
}
