package com.handcode.app.ui.screen.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.handcode.app.domain.model.CodeRecord
import com.handcode.app.domain.model.SortOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CODE_PREVIEW_MAX_LINES = 2
private const val DATE_PATTERN = "yyyy.MM.dd HH:mm"

/**
 * 학습 기록 화면.
 *
 * 상단: 뒤로가기 + 제목
 * 본문: 검색창 → 정렬 FilterChip 3개 → 카드 LazyColumn
 * 카드 길게 누르기 → DropdownMenu (이름 바꾸기 / 완료 표시 / 삭제)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordListScreen(
    onBack: () -> Unit,
    onRecordClick: (Long) -> Unit,
    viewModel: RecordListViewModel = hiltViewModel()
) {
    val records by viewModel.records.collectAsState()
    val query by viewModel.query.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()

    // 리네임 다이얼로그 상태.
    var renameTarget by remember { mutableStateOf<CodeRecord?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("학습 기록") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("뒤로") }
                }
            )
        }
    ) { padding ->
        RecordListContent(
            padding = padding,
            query = query,
            sortOrder = sortOrder,
            records = records,
            onQueryChanged = viewModel::onQueryChanged,
            onSortChanged = viewModel::onSortChanged,
            // 카드 탭: PipelineStore 에 세션을 올린 뒤 상위에서 전달받은 네비게이션 콜백 호출.
            onRecordClick = { id -> viewModel.openRecord(id) { onRecordClick(id) } },
            onRenameRequest = { renameTarget = it },
            onDelete = viewModel::delete,
            onToggleCompleted = viewModel::toggleCompleted
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            initial = target.title,
            onConfirm = { newTitle ->
                viewModel.rename(target.id, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
}

@Composable
private fun RecordListContent(
    padding: PaddingValues,
    query: String,
    sortOrder: SortOrder,
    records: List<CodeRecord>,
    onQueryChanged: (String) -> Unit,
    onSortChanged: (SortOrder) -> Unit,
    onRecordClick: (Long) -> Unit,
    onRenameRequest: (CodeRecord) -> Unit,
    onDelete: (Long) -> Unit,
    onToggleCompleted: (Long, Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        SearchField(query = query, onQueryChanged = onQueryChanged)
        Spacer(modifier = Modifier.height(8.dp))
        SortChipsRow(current = sortOrder, onSortChanged = onSortChanged)
        Spacer(modifier = Modifier.height(12.dp))
        if (records.isEmpty()) {
            EmptyState(hasQuery = query.isNotBlank())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = records, key = { it.id }) { record ->
                    RecordCard(
                        record = record,
                        onClick = { onRecordClick(record.id) },
                        onRename = { onRenameRequest(record) },
                        onDelete = { onDelete(record.id) },
                        onToggleCompleted = { onToggleCompleted(record.id, record.isCompleted) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChanged: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("제목 또는 코드 검색") },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = "검색")
        },
        singleLine = true
    )
}

@Composable
private fun SortChipsRow(current: SortOrder, onSortChanged: (SortOrder) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SortChip("최신순", SortOrder.DATE, current, onSortChanged)
        // 8주차 변경: "정확도순" 은 ML Kit v2 신뢰도 미반환으로 무의미 → "줄수 많은순" 으로 교체.
        SortChip("줄수 많은순", SortOrder.LINE_COUNT, current, onSortChanged)
        SortChip("제목순", SortOrder.TITLE, current, onSortChanged)
    }
}

@Composable
private fun SortChip(
    label: String,
    value: SortOrder,
    current: SortOrder,
    onSortChanged: (SortOrder) -> Unit
) {
    FilterChip(
        selected = current == value,
        onClick = { onSortChanged(value) },
        label = { Text(label) }
    )
}

@Composable
private fun EmptyState(hasQuery: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (hasQuery) "검색 결과가 없어요" else "아직 학습 기록이 없어요",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordCard(
    record: CodeRecord,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onToggleCompleted: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildTitle(record),
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
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = record.code
                    .lineSequence()
                    .take(CODE_PREVIEW_MAX_LINES)
                    .joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                ),
                maxLines = CODE_PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            LineCountBadge(code = record.code)
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("이름 바꾸기") },
                onClick = {
                    menuExpanded = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(if (record.isCompleted) "완료 해제" else "완료 표시")
                },
                onClick = {
                    menuExpanded = false
                    onToggleCompleted()
                }
            )
            DropdownMenuItem(
                text = { Text("삭제") },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

/**
 * 8주차 변경: 기존 AccuracyRow(진행 바 + %) 대신 단순 "N줄 인식" 배지.
 * ML Kit v2 가 신뢰도를 반환하지 않아 가짜 % 를 보이던 UX 를 제거하고,
 * 실측 가능한 "코드 줄 수" 로 카드마다 유의미한 보조 정보를 준다.
 */
@Composable
private fun LineCountBadge(code: String) {
    val lineCount = if (code.isEmpty()) 0 else code.count { it == '\n' } + 1
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "${lineCount}줄 인식",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 바꾸기") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("저장") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}

private fun buildTitle(record: CodeRecord): String {
    val base = record.title.ifBlank { "제목 없음" }
    return if (record.isCompleted) "완료 · $base" else base
}

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat(DATE_PATTERN, Locale.KOREA)
    return formatter.format(Date(timestamp))
}
