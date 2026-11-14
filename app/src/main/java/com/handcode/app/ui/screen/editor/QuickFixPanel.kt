package com.handcode.app.ui.screen.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.handcode.app.data.ocr.postprocess.Suggestion
import com.handcode.app.data.ocr.postprocess.SuggestionCategory
import com.handcode.app.domain.model.SyntaxErrorInfo

/**
 * QuickFix 패널 — `PythonTokenRepair` 제안 + `ValidationResult.errors` 를 한 목록으로 통합.
 *
 * UI 규칙:
 *  - 항목 0 건이면 렌더하지 않는다 (패널 자체가 사라짐 → 스크롤 여유 확보).
 *  - 헤더 탭으로 접고 펼친다. 새 항목이 들어오면 자동으로 펼쳐진다 (remember 키 = items.size).
 *  - 수정 가능한 제안은 탭 시 `onApplySuggestion` 호출. 문법 오류(ERROR)는 정보 표시만.
 *  - 심각도 색: ERROR 빨강 / WARNING 주황 / HINT 파랑.
 */
data class QuickFixItem(
    val line: Int,
    val title: String,
    val description: String,
    val onApply: (() -> Unit)?,
    val severity: Severity
) {
    enum class Severity { ERROR, WARNING, HINT }
}

@Composable
fun QuickFixPanel(
    suggestions: List<Suggestion>,
    syntaxErrors: List<SyntaxErrorInfo>,
    onApplySuggestion: (Suggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = buildItems(suggestions, syntaxErrors, onApplySuggestion)
    if (items.isEmpty()) return
    var expanded by remember(items.size) { mutableStateOf(true) }

    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "수정 제안 ${items.size}건",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null
                )
            }
            if (expanded) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                ) {
                    items(items) { item ->
                        QuickFixRow(item)
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFixRow(item: QuickFixItem) {
    val tint = when (item.severity) {
        QuickFixItem.Severity.ERROR -> Color(0xFFD32F2F)
        QuickFixItem.Severity.WARNING -> Color(0xFFF57C00)
        QuickFixItem.Severity.HINT -> Color(0xFF1976D2)
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { base -> if (item.onApply != null) base.clickable { item.onApply.invoke() } else base }
        .padding(horizontal = 12.dp, vertical = 8.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${item.line + 1}줄",
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(40.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium
            )
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (item.onApply != null) {
            Text(
                text = "적용",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

private fun buildItems(
    suggestions: List<Suggestion>,
    syntaxErrors: List<SyntaxErrorInfo>,
    onApplySuggestion: (Suggestion) -> Unit
): List<QuickFixItem> {
    val sug = suggestions.map { s ->
        QuickFixItem(
            line = s.line,
            title = s.message,
            description = s.replacement,
            onApply = { onApplySuggestion(s) },
            severity = when (s.category) {
                SuggestionCategory.MISSING_COLON,
                SuggestionCategory.UNBALANCED_PAREN,
                SuggestionCategory.UNBALANCED_QUOTE -> QuickFixItem.Severity.WARNING
                SuggestionCategory.KEYWORD_TYPO -> QuickFixItem.Severity.HINT
            }
        )
    }
    val errs = syntaxErrors.map { e ->
        QuickFixItem(
            line = (e.line - 1).coerceAtLeast(0),
            title = e.message,
            description = "",
            onApply = null,
            severity = QuickFixItem.Severity.ERROR
        )
    }
    return (sug + errs).sortedBy { it.line }
}
