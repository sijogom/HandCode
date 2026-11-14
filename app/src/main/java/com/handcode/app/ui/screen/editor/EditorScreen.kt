package com.handcode.app.ui.screen.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.handcode.app.data.ocr.postprocess.PythonTokenRepair
import com.handcode.app.domain.model.CodeSession
import com.handcode.app.domain.model.OcrEngineType
import com.handcode.app.ui.screen.pipeline.ErrorBanner
import com.handcode.app.ui.screen.pipeline.FailureReason
import com.handcode.app.ui.screen.pipeline.PipelineOverlay
import com.handcode.app.ui.screen.pipeline.PipelineState
import com.handcode.app.ui.screen.pipeline.PipelineViewModel
import com.handcode.app.ui.theme.LocalAppSettings
import com.handcode.app.ui.theme.toCodeSp

private const val LINE_NUMBER_WIDTH_DP = 32
private const val EDITOR_MIN_HEIGHT_DP = 320

// BasicTextField 내부 padding. LineNumberColumn 의 vertical padding 과 동일해야
// 첫 줄부터 정렬된다. SyntaxHighlightOverlay 도 이 값을 topPadding 으로 사용.
private const val EDITOR_INNER_PADDING_DP = 8

// Compose Text 기본 lineHeight 는 fontSize 의 약 1.15배. 코드 편집에서는 여유를 줘서 1.4.
// TextField 와 LineNumberColumn 에 같은 값을 써야 줄 수가 늘어나도 정렬이 유지된다.
private const val LINE_HEIGHT_RATIO = 1.4f

/**
 * 편집 화면.
 *
 * v2 변경:
 *  - F5 `SyntaxHighlightOverlay`: 문법 오류 라인 배경을 빨간 음영으로 표시
 *  - F6 `QuickFixPanel`: `PythonTokenRepair` 제안 + `ValidationResult.errors` 통합 패널
 *  - F7 `OriginalImageViewer`: AppBar 이미지 아이콘으로 원본 사진 오버레이
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    onNavigateToResult: () -> Unit,
    onBack: () -> Unit,
    pipelineViewModel: PipelineViewModel = hiltViewModel()
) {
    val session by pipelineViewModel.session.collectAsState()
    val pipelineState by pipelineViewModel.state.collectAsState()

    var showOriginal by remember { mutableStateOf(false) }

    // 결과 화면으로 넘어가야 하는 상태 감지.
    LaunchedEffect(pipelineState) {
        if (pipelineState.shouldNavigateToResult()) {
            onNavigateToResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { EditorAppBarTitle(session = session) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("뒤로") }
                },
                actions = {
                    IconButton(
                        onClick = { showOriginal = true },
                        enabled = !session?.originalImagePath.isNullOrBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "원본 이미지 보기"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val current = session
            if (current == null) {
                EmptyEditor(onBack = onBack)
            } else {
                EditorBody(
                    session = current,
                    pipelineState = pipelineState,
                    onCodeChanged = pipelineViewModel::onCodeChanged,
                    onExecute = pipelineViewModel::onExecuteRequested,
                    onDismissSyntaxError = pipelineViewModel::dismissSyntaxError
                )
            }
            // 로딩 오버레이 (Recognizing / Validating / Executing 에서만 표시).
            PipelineOverlay(state = pipelineState)

            // F7: 원본 이미지 풀스크린 오버레이 — 가장 위 레이어에 둔다.
            OriginalImageViewer(
                imagePath = session?.originalImagePath,
                visible = showOriginal,
                onDismiss = { showOriginal = false }
            )
        }
    }
}

/**
 * Completed, Failed(RUNTIME_ERROR), Failed(TIMEOUT) 는 결과 화면에서 처리한다.
 * Failed(SYNTAX_ERROR) 와 Failed(ENGINE_UNAVAILABLE) 는 에디터 화면에서 배너로 처리하므로
 * 여기서는 이동하지 않는다 — 엔진 장애는 결과 화면으로 이동해도 사용자가 할 수 있는 조치가 없다.
 */
private fun PipelineState.shouldNavigateToResult(): Boolean = when (this) {
    is PipelineState.Completed -> true
    is PipelineState.Failed -> when (reason) {
        FailureReason.RUNTIME_ERROR, FailureReason.TIMEOUT -> true
        else -> false
    }
    else -> false
}

@Composable
private fun EditorAppBarTitle(session: CodeSession?) {
    // 2026-04-19 MediaPipe GenAI 전환: 단일 엔진 (Gemma-3n) 로 통일.
    //  - 이전에 VLM / ML Kit 폴백 분기로 배지 색을 달리했지만 이제 단일 엔진이므로
    //    라벨은 "Gemma-3n" 고정, 색상은 primary 로 통일.
    //  - 엔진 타입이 아직 세팅되지 않은 상태(세션 첫 진입 직후 등)는 라벨 공란.
    //  - lineCount 는 기존과 동일하게 "N줄" 표시 (신뢰도 수치는 기만 지표 방지 위해 노출 X).
    val ocr = session?.ocrResult
    val engineType = ocr?.engineUsed
    val engineLabel = when (engineType) {
        OcrEngineType.VLM -> "Gemma-3n"
        OcrEngineType.ML_KIT -> "Gemma-3n"
        null -> ""
    }
    val engineColor = when (engineType) {
        null -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.primary
    }
    val lineCount = ocr?.lineCount ?: 0

    if (session == null) {
        Text(text = "편집", style = MaterialTheme.typography.titleMedium)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "편집 · ${lineCount}줄",
            style = MaterialTheme.typography.titleMedium
        )
        if (engineLabel.isNotEmpty()) {
            Text(
                text = " · $engineLabel",
                style = MaterialTheme.typography.titleMedium,
                color = engineColor
            )
        }
    }
}

@Composable
private fun EmptyEditor(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "편집할 코드가 없어요",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "카메라나 갤러리에서 손글씨 코드를 먼저 인식해 주세요",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack) { Text("돌아가기") }
    }
}

@Composable
private fun EditorBody(
    session: CodeSession,
    pipelineState: PipelineState,
    onCodeChanged: (String) -> Unit,
    onExecute: () -> Unit,
    onDismissSyntaxError: () -> Unit
) {
    // 사용자 설정 폰트 크기 반영. 라인 번호와 TextField 가 같은 크기여야 정렬이 맞는다.
    val codeFontSize = LocalAppSettings.current.codeFontSize.toCodeSp()

    // 7주차: 한글 IME 커서 튕김 버그 수정.
    // remember(session.id) 로 키를 걸어 새 세션이 들어오면 리셋되도록 한다.
    var codeField by remember(session.id) {
        mutableStateOf(
            TextFieldValue(
                text = session.editedCode,
                selection = TextRange(session.editedCode.length)
            )
        )
    }

    // 라인번호/코드 스크롤을 한 ScrollState 로 묶어서 두 컴포넌트가 같이 움직이도록 한다.
    val scrollState = rememberScrollState()

    // F6: OCR 결과에 대해 토큰 수리 제안 목록. 편집할 때마다 재계산되며 remember 로 같은 입력엔 캐싱.
    val suggestions = remember(codeField.text) {
        PythonTokenRepair.analyze(codeField.text)
    }
    // F4 + F5: 문법 검증 결과를 그대로 읽어 라인 번호 Set 으로 변환.
    val syntaxErrors = session.validation?.errors.orEmpty()
    val errorLines = syntaxErrors.map { it.line }.toSet()

    // F5: Canvas 좌표 계산 — TextField 의 상단 padding + 라인당 높이를 px 로 치환.
    val density = LocalDensity.current
    val lineHeightPx = with(density) { (codeFontSize * LINE_HEIGHT_RATIO).toPx() }
    val topPaddingPx = with(density) { EDITOR_INNER_PADDING_DP.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 문법 오류 / 엔진 장애 / 분류 불가(UNKNOWN) 시 상단 배너.
        val editorFailure = pipelineState as? PipelineState.Failed
        val editorBannerReason = editorFailure?.reason?.takeIf {
            it == FailureReason.SYNTAX_ERROR ||
                it == FailureReason.ENGINE_UNAVAILABLE ||
                it == FailureReason.UNKNOWN
        }
        if (editorBannerReason != null) {
            ErrorBanner(
                reason = editorBannerReason,
                onAction = onDismissSyntaxError,
                modifier = Modifier.padding(bottom = 12.dp),
                errorDetails = editorFailure?.errorDetails
            )
        }
        // 공유 ScrollState 로 감싼 Column — 안쪽 Row 의 라인번호와 TextField 가 함께 스크롤된다.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            // F5: Box 로 감싸 BasicTextField 뒤에 SyntaxHighlightOverlay Canvas 를 깐다.
            Box(modifier = Modifier.fillMaxWidth()) {
                SyntaxHighlightOverlay(
                    errorLines = errorLines,
                    lineHeightPx = lineHeightPx,
                    topPaddingPx = topPaddingPx,
                    modifier = Modifier.matchParentSize()
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    LineNumberColumn(code = codeField.text, fontSize = codeFontSize)
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = codeField,
                        onValueChange = {
                            codeField = it
                            onCodeChanged(it.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = EDITOR_MIN_HEIGHT_DP.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(EDITOR_INNER_PADDING_DP.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = codeFontSize,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = codeFontSize * LINE_HEIGHT_RATIO
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
        // F6: QuickFix 패널 — 제안 0 건이면 렌더되지 않는다 (패널 내부 체크).
        QuickFixPanel(
            suggestions = suggestions,
            syntaxErrors = syntaxErrors,
            onApplySuggestion = { sug ->
                val lines = codeField.text.split('\n').toMutableList()
                if (sug.line in lines.indices) {
                    lines[sug.line] = sug.replacement
                    val newText = lines.joinToString("\n")
                    codeField = codeField.copy(
                        text = newText,
                        selection = TextRange(newText.length)
                    )
                    onCodeChanged(newText)
                }
            },
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onExecute,
            modifier = Modifier.fillMaxWidth(),
            enabled = !pipelineState.isBusy()
        ) {
            Text("실행하기")
        }
    }
}

private fun PipelineState.isBusy(): Boolean = when (this) {
    is PipelineState.Recognizing,
    is PipelineState.Validating,
    is PipelineState.Executing -> true
    else -> false
}

/**
 * 좌측 라인 번호 영역.
 * 자체 verticalScroll 을 갖지 않고, 상위 Column 의 공유 ScrollState 로 함께 스크롤된다.
 */
@Composable
private fun LineNumberColumn(code: String, fontSize: TextUnit) {
    val lines = (code.count { it == '\n' } + 1).coerceAtLeast(1)
    // 단일 멀티라인 Text 로 통합. Why: 각 줄을 별도 Text 로 쌓으면 Compose 가 컴포넌트별
    // wrap height 를 적용해 BasicTextField 의 멀티라인 lineHeight 와 미세 차이가 누적되어
    // 줄이 늘수록 라인 번호와 코드 줄이 어긋나는 사용자 보고 버그가 생긴다.
    val numberText = (1..lines).joinToString("\n")
    Column(
        modifier = Modifier
            .width(LINE_NUMBER_WIDTH_DP.dp)
            .padding(vertical = EDITOR_INNER_PADDING_DP.dp)
    ) {
        Text(
            text = numberText,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                lineHeight = fontSize * LINE_HEIGHT_RATIO,
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
