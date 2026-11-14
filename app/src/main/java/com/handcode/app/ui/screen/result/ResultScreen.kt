package com.handcode.app.ui.screen.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.handcode.app.R
import com.handcode.app.domain.model.CodeResult
import com.handcode.app.domain.model.CodeSession
import com.handcode.app.ui.screen.pipeline.FailureReason
import com.handcode.app.ui.screen.pipeline.PipelineState
import com.handcode.app.ui.screen.pipeline.PipelineViewModel
import com.handcode.app.ui.theme.LocalAppSettings
import com.handcode.app.ui.theme.toCodeSp

private val SUCCESS_COLOR = Color(0xFF2E7D32)
private val FAILURE_COLOR = Color(0xFFC62828)

// 7주차: 성공 실행 중에도 stderr 에 출력이 있으면 경고 톤(주황)으로 별도 표시.
private val WARNING_COLOR = Color(0xFFEF6C00)

/**
 * 실행 결과 화면.
 *
 * PipelineStore 가 Singleton 이므로 Editor 쪽에서 만든 세션/상태를 그대로 재사용한다.
 * - Completed -> 초록 카드 + stdout, 실행 시간(있을 경우) 표시
 * - Failed(RUNTIME_ERROR | TIMEOUT) -> 빨강 카드 + 에러 메시지
 * - 그 외 (예: 유저가 직접 진입한 경우) -> "표시할 결과가 없어요" 안내
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    onRetry: () -> Unit,
    onRetake: () -> Unit,
    onHome: () -> Unit,
    pipelineViewModel: PipelineViewModel = hiltViewModel()
) {
    val state by pipelineViewModel.state.collectAsState()
    val session by pipelineViewModel.session.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("실행 결과") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            ResultCard(state = state, session = session, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(12.dp))
            ActionRow(
                onRetry = {
                    // "다시 편집" — 편집 화면으로 돌아가되 세션은 유지.
                    // Completed / Failed(RUNTIME_ERROR|TIMEOUT) 상태를 Editing 으로 되돌려,
                    // Editor 의 LaunchedEffect 가 Result 로 다시 튕기는 것을 막는다.
                    pipelineViewModel.backToEditing()
                    onRetry()
                },
                onRetake = {
                    // "새로 촬영" — 세션 폐기 후 카메라 흐름으로.
                    pipelineViewModel.reset()
                    onRetake()
                },
                onHome = {
                    pipelineViewModel.reset()
                    onHome()
                }
            )
        }
    }
}

/** 상태/세션에 따라 성공/실패 카드 분기. */
@Composable
private fun ResultCard(
    state: PipelineState,
    session: CodeSession?,
    modifier: Modifier = Modifier
) {
    val failureReason = (state as? PipelineState.Failed)?.reason
    val executionResult = session?.execution

    when {
        // 명시적 실패 상태 — 배너 컬러는 빨강.
        failureReason != null -> FailureCard(
            reason = failureReason,
            executionResult = executionResult,
            modifier = modifier
        )

        // 성공 실행 결과가 있는 경우
        executionResult is CodeResult.Success -> SuccessCard(
            result = executionResult,
            modifier = modifier
        )

        // 그 외 (아직 아무것도 없음)
        else -> EmptyResult(modifier = modifier)
    }
}

@Composable
private fun SuccessCard(
    result: CodeResult.Success,
    modifier: Modifier = Modifier
) {
    // 설정 화면에서 고른 폰트 크기를 stdout/stderr 영역에도 동일하게 반영.
    val codeFontSize = LocalAppSettings.current.codeFontSize.toCodeSp()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        // 정상 출력 블록 (stdout)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SUCCESS_COLOR.copy(alpha = 0.08f))
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "실행 완료",
                    color = SUCCESS_COLOR,
                    fontWeight = FontWeight.Bold
                )
                if (result.executionTimeMs > 0L) {
                    Text(
                        text = "  ·  실행 시간 ${result.executionTimeMs} ms",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = result.output.ifBlank { "(출력 없음)" },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = codeFontSize
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 7주차: stderr 내용이 있으면 주황 경고 블록을 별도로 표시.
        // 성공(success=True) 상태에서도 경고나 DeprecationWarning 등이 나올 수 있음.
        if (result.stderr.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(WARNING_COLOR.copy(alpha = 0.10f))
                    .padding(16.dp)
            ) {
                Text(
                    text = "경고 출력 (stderr)",
                    color = WARNING_COLOR,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = result.stderr,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = codeFontSize
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun FailureCard(
    reason: FailureReason,
    executionResult: CodeResult?,
    modifier: Modifier = Modifier
) {
    val title = when (reason) {
        FailureReason.RUNTIME_ERROR -> stringResource(R.string.result_failure_runtime)
        FailureReason.TIMEOUT -> stringResource(R.string.result_failure_timeout)
        FailureReason.SYNTAX_ERROR -> stringResource(R.string.result_failure_syntax)
        FailureReason.OCR_FAILED -> stringResource(R.string.result_failure_ocr)
        FailureReason.UNKNOWN -> stringResource(R.string.result_failure_unknown)
        // ENGINE_UNAVAILABLE 은 Editor 에서 배너로 처리되어 여기 도달하지 않지만,
        // when 의 완전성 보장을 위해 명시적으로 커버.
        FailureReason.ENGINE_UNAVAILABLE -> stringResource(R.string.result_failure_engine)
    }
    val message = buildFailureMessage(reason, executionResult)
    val codeFontSize = LocalAppSettings.current.codeFontSize.toCodeSp()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(FAILURE_COLOR.copy(alpha = 0.08f))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = title,
            color = FAILURE_COLOR,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = codeFontSize
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun buildFailureMessage(
    reason: FailureReason,
    executionResult: CodeResult?
): String {
    if (reason == FailureReason.TIMEOUT) {
        return "코드 실행이 5초를 넘겨 중단했어요. 무한 루프가 없는지 확인해 주세요."
    }
    return when (executionResult) {
        is CodeResult.RuntimeError -> executionResult.message
        is CodeResult.SyntaxError -> "라인 ${executionResult.line}: ${executionResult.message}"
        is CodeResult.Success -> executionResult.output
        null -> "자세한 오류 메시지를 불러오지 못했어요."
    }
}

@Composable
private fun EmptyResult(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "표시할 결과가 없어요",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ActionRow(
    onRetry: () -> Unit,
    onRetake: () -> Unit,
    onHome: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
            Text("다시 편집")
        }
        OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) {
            Text("새로 촬영")
        }
        Button(onClick = onHome, modifier = Modifier.weight(1f)) {
            Text("홈으로")
        }
    }
}
