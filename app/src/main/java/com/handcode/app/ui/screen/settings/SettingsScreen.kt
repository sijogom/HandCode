package com.handcode.app.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.handcode.app.domain.model.AppSettings
import com.handcode.app.domain.model.FontSize
import com.handcode.app.domain.model.ThemeMode

/**
 * 앱 설정 화면.
 *
 * 섹션 1: 테마 (시스템 / 라이트 / 다크) — Radio
 * 섹션 2: 코드 폰트 크기 (작게 / 보통 / 크게) — Radio
 * 섹션 3: 촬영 시 그리드 표시 — Switch
 * 섹션 4: Gemma-3n 모델 관리 — URL 자동 다운로드 + SAF 폴백 하이브리드.
 *   4-1. 라이선스 동의 (`ai.google.dev/gemma/terms`)
 *   4-2. 모델 상태 UI — 4 모드 (설치됨 / URL 입력 필요 / 다운로드 가능 / 다운로드 중)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val modelInstalled by viewModel.modelInstalled.collectAsState()
    val modelInstalling by viewModel.modelInstalling.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val gemmaUrl by viewModel.gemmaModelUrl.collectAsState()
    val gemmaSha by viewModel.gemmaModelSha256.collectAsState()
    val licenseAccepted by viewModel.gemmaLicenseAccepted.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 화면 진입 시 한 번 파일 상태 동기화 + 이벤트 collect 시작.
    LaunchedEffect(Unit) {
        viewModel.refreshModelStatus()
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.ModelInstalled -> snackbarHostState.showSnackbar(
                    message = MODEL_INSTALL_OK_MESSAGE,
                    duration = SnackbarDuration.Short
                )
                is SettingsEvent.ModelInstallFailed -> snackbarHostState.showSnackbar(
                    message = "$MODEL_INSTALL_FAIL_PREFIX ${event.reason}",
                    duration = SnackbarDuration.Short
                )
            }
        }
    }

    // 최초 진입 시 떠 있는 "현재 값" 은 알림 대상이 아니므로 previousSettings 로 비교한다.
    // null → 진입 직후 한 번만 설정을 기록하고 스낵바를 띄우지 않는다.
    // 이후 settings 가 바뀔 때마다 "저장되었어요" 를 한 번씩 짧게 보여준다.
    var previousSettings by remember { mutableStateOf<AppSettings?>(null) }
    LaunchedEffect(settings) {
        val prev = previousSettings
        if (prev != null && prev != settings) {
            snackbarHostState.showSnackbar(
                message = SAVE_CONFIRM_MESSAGE,
                duration = SnackbarDuration.Short
            )
        }
        previousSettings = settings
    }

    // SAF launcher — `.litertlm` 은 MIME 이 표준화되지 않아 "*/*" 로 연다.
    // 사용자가 정확한 파일을 고르는지는 파일명/크기로 확인.
    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onModelSelected(it) }
    }

    val context = LocalContext.current
    val openLicense: () -> Unit = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GEMMA_LICENSE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("뒤로") }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        SettingsContent(
            padding = padding,
            settings = settings,
            modelInstalled = modelInstalled,
            modelInstalling = modelInstalling,
            downloadProgress = downloadProgress,
            gemmaUrl = gemmaUrl,
            gemmaSha = gemmaSha,
            licenseAccepted = licenseAccepted,
            onThemeSelected = viewModel::onThemeSelected,
            onFontSizeSelected = viewModel::onFontSizeSelected,
            onGridToggled = viewModel::onGridToggled,
            onUrlChanged = viewModel::onGemmaUrlChanged,
            onShaChanged = viewModel::onGemmaShaChanged,
            onLicenseToggled = viewModel::onLicenseToggled,
            onOpenLicense = openLicense,
            onStartDownload = viewModel::startDownload,
            onCancelDownload = viewModel::cancelDownload,
            onRedownload = viewModel::redownloadModel,
            onPickFromFile = { modelPicker.launch(arrayOf("*/*")) }
        )
    }
}

// 저장 확인 스낵바 메시지. 짧게 표시되어 "반영됐다"는 확신만 주는 게 목적.
private const val SAVE_CONFIRM_MESSAGE = "저장되었어요"
private const val MODEL_INSTALL_OK_MESSAGE = "Gemma-3n 모델이 준비되었어요"
private const val MODEL_INSTALL_FAIL_PREFIX = "모델 준비 실패 —"
private const val GEMMA_LICENSE_URL = "https://ai.google.dev/gemma/terms"

@Composable
private fun SettingsContent(
    padding: PaddingValues,
    settings: AppSettings,
    modelInstalled: Boolean,
    modelInstalling: Boolean,
    downloadProgress: Float?,
    gemmaUrl: String,
    gemmaSha: String,
    licenseAccepted: Boolean,
    onThemeSelected: (ThemeMode) -> Unit,
    onFontSizeSelected: (FontSize) -> Unit,
    onGridToggled: (Boolean) -> Unit,
    onUrlChanged: (String) -> Unit,
    onShaChanged: (String) -> Unit,
    onLicenseToggled: (Boolean) -> Unit,
    onOpenLicense: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRedownload: () -> Unit,
    onPickFromFile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SectionTitle("테마")
        ThemeSection(current = settings.theme, onSelected = onThemeSelected)
        Spacer(modifier = Modifier.height(24.dp))

        SectionTitle("코드 폰트 크기")
        FontSizeSection(current = settings.codeFontSize, onSelected = onFontSizeSelected)
        Spacer(modifier = Modifier.height(24.dp))

        SectionTitle("촬영 시 그리드 표시")
        GridToggleRow(checked = settings.cameraGridVisible, onChanged = onGridToggled)
        Spacer(modifier = Modifier.height(24.dp))

        SectionTitle("Gemma-3n 모델 라이선스")
        GemmaLicenseSection(
            accepted = licenseAccepted,
            onToggle = onLicenseToggled,
            onOpen = onOpenLicense
        )
        Spacer(modifier = Modifier.height(24.dp))

        SectionTitle("Gemma-3n 모델 다운로드")
        GemmaModelSection(
            mode = resolveMode(
                installed = modelInstalled,
                copying = modelInstalling,
                progress = downloadProgress,
                url = gemmaUrl,
            ),
            gemmaUrl = gemmaUrl,
            gemmaSha = gemmaSha,
            progress = downloadProgress,
            licenseAccepted = licenseAccepted,
            onUrlChanged = onUrlChanged,
            onShaChanged = onShaChanged,
            onStartDownload = onStartDownload,
            onCancelDownload = onCancelDownload,
            onRedownload = onRedownload,
            onPickFromFile = onPickFromFile,
        )
    }
}

/** 모델 섹션이 어떤 상태로 렌더링될지 결정. */
private fun resolveMode(
    installed: Boolean,
    copying: Boolean,
    progress: Float?,
    url: String,
): ModelSectionMode = when {
    progress != null -> ModelSectionMode.DOWNLOADING
    copying -> ModelSectionMode.DOWNLOADING
    installed -> ModelSectionMode.INSTALLED
    url.isBlank() -> ModelSectionMode.URL_REQUIRED
    else -> ModelSectionMode.READY_TO_DOWNLOAD
}

/**
 * 모델 섹션의 4가지 상태.
 * - INSTALLED: 파일 존재. 재다운로드 / SAF 파일 교체 가능.
 * - URL_REQUIRED: URL 미설정. URL 입력 + SAF 폴백.
 * - READY_TO_DOWNLOAD: URL 설정됨 + 미설치. "다운로드 시작" 버튼.
 * - DOWNLOADING: 진행률 표시 + 취소 버튼.
 */
private enum class ModelSectionMode { INSTALLED, URL_REQUIRED, READY_TO_DOWNLOAD, DOWNLOADING }

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ThemeSection(current: ThemeMode, onSelected: (ThemeMode) -> Unit) {
    Column {
        RadioRow(
            label = "시스템 설정 사용",
            selected = current == ThemeMode.SYSTEM,
            onClick = { onSelected(ThemeMode.SYSTEM) }
        )
        RadioRow(
            label = "라이트",
            selected = current == ThemeMode.LIGHT,
            onClick = { onSelected(ThemeMode.LIGHT) }
        )
        RadioRow(
            label = "다크",
            selected = current == ThemeMode.DARK,
            onClick = { onSelected(ThemeMode.DARK) }
        )
    }
}

@Composable
private fun FontSizeSection(current: FontSize, onSelected: (FontSize) -> Unit) {
    Column {
        RadioRow(
            label = "작게",
            selected = current == FontSize.SMALL,
            onClick = { onSelected(FontSize.SMALL) }
        )
        RadioRow(
            label = "보통",
            selected = current == FontSize.MEDIUM,
            onClick = { onSelected(FontSize.MEDIUM) }
        )
        RadioRow(
            label = "크게",
            selected = current == FontSize.LARGE,
            onClick = { onSelected(FontSize.LARGE) }
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun GridToggleRow(checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "카메라 화면에 가이드 사각형을 표시합니다",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

/**
 * Gemma 라이선스 동의 섹션.
 *
 * `google/gemma-3n-E2B-it-litert-lm` 는 HuggingFace Gated repo + Google Gemma Terms of Use
 * 준수가 필요. 체크박스 동의 + 약관 링크 제공.
 */
@Composable
private fun GemmaLicenseSection(
    accepted: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Gemma 모델은 Google Gemma 이용 약관 준수가 필요합니다. 약관을 확인한 뒤 체크하세요.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onOpen) { Text("Gemma 이용 약관 열기") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = accepted, onCheckedChange = onToggle)
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                text = "약관을 읽었으며 동의합니다",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * Gemma `.litertlm` 모델 관리 섹션. 네 가지 모드를 분기 렌더.
 */
@Composable
private fun GemmaModelSection(
    mode: ModelSectionMode,
    gemmaUrl: String,
    gemmaSha: String,
    progress: Float?,
    licenseAccepted: Boolean,
    onUrlChanged: (String) -> Unit,
    onShaChanged: (String) -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onRedownload: () -> Unit,
    onPickFromFile: () -> Unit,
) {
    when (mode) {
        ModelSectionMode.INSTALLED -> InstalledView(
            onRedownload = onRedownload,
            onPickFromFile = onPickFromFile,
        )
        ModelSectionMode.URL_REQUIRED -> UrlRequiredView(
            gemmaUrl = gemmaUrl,
            gemmaSha = gemmaSha,
            onUrlChanged = onUrlChanged,
            onShaChanged = onShaChanged,
            onPickFromFile = onPickFromFile,
        )
        ModelSectionMode.READY_TO_DOWNLOAD -> ReadyToDownloadView(
            gemmaUrl = gemmaUrl,
            gemmaSha = gemmaSha,
            licenseAccepted = licenseAccepted,
            onUrlChanged = onUrlChanged,
            onShaChanged = onShaChanged,
            onStartDownload = onStartDownload,
            onPickFromFile = onPickFromFile,
        )
        ModelSectionMode.DOWNLOADING -> DownloadingView(
            progress = progress,
            onCancel = onCancelDownload,
        )
    }
}

@Composable
private fun InstalledView(onRedownload: () -> Unit, onPickFromFile: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "모델이 준비되어 있습니다. 교체하려면 아래 버튼을 사용하세요.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedButton(onClick = onRedownload, modifier = Modifier.fillMaxWidth()) {
            Text("URL 에서 재다운로드")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFromFile, modifier = Modifier.fillMaxWidth()) {
            Text("파일 직접 선택 (SAF)")
        }
    }
}

@Composable
private fun UrlRequiredView(
    gemmaUrl: String,
    gemmaSha: String,
    onUrlChanged: (String) -> Unit,
    onShaChanged: (String) -> Unit,
    onPickFromFile: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "모델 다운로드 URL 이 비어 있습니다. URL 을 입력하거나 파일을 직접 선택하세요.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        UrlField(value = gemmaUrl, onChange = onUrlChanged)
        Spacer(modifier = Modifier.height(8.dp))
        ShaField(value = gemmaSha, onChange = onShaChanged)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFromFile, modifier = Modifier.fillMaxWidth()) {
            Text("파일 직접 선택 (SAF)")
        }
    }
}

@Composable
private fun ReadyToDownloadView(
    gemmaUrl: String,
    gemmaSha: String,
    licenseAccepted: Boolean,
    onUrlChanged: (String) -> Unit,
    onShaChanged: (String) -> Unit,
    onStartDownload: () -> Unit,
    onPickFromFile: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "URL 이 설정되었습니다. 3.4GB 를 다운로드합니다 — Wi-Fi 를 권장합니다.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        UrlField(value = gemmaUrl, onChange = onUrlChanged)
        Spacer(modifier = Modifier.height(8.dp))
        ShaField(value = gemmaSha, onChange = onShaChanged)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onStartDownload,
            enabled = licenseAccepted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (licenseAccepted) "다운로드 시작" else "라이선스 동의가 먼저 필요합니다")
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onPickFromFile, modifier = Modifier.fillMaxWidth()) {
            Text("파일 직접 선택 (SAF)")
        }
    }
}

@Composable
private fun DownloadingView(progress: Float?, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatProgressText(progress),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (progress != null && progress > 0f) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.padding(horizontal = 8.dp))
            OutlinedButton(onClick = onCancel) { Text("취소") }
        }
    }
}

@Composable
private fun UrlField(value: String, onChange: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("모델 URL") },
        placeholder = { Text("https://…/gemma-3n-E2B-it-int4.litertlm") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { onChange(draft) }) { Text("URL 저장") }
}

@Composable
private fun ShaField(value: String, onChange: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("SHA-256 (선택)") },
        placeholder = { Text("비워두면 검증을 건너뜁니다") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { onChange(draft) }) { Text("SHA 저장") }
}

private fun formatProgressText(progress: Float?): String = when {
    progress == null -> "다운로드 준비 중…"
    progress <= 0f -> "다운로드 시작 중 (서버 응답 대기)…"
    else -> "다운로드 중 %.1f%%".format(progress * 100f)
}
