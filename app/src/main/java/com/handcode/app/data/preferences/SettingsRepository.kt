package com.handcode.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.handcode.app.data.ocr.mediapipe.GemmaModelManager
import com.handcode.app.domain.model.AppSettings
import com.handcode.app.domain.model.FontSize
import com.handcode.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore<Preferences> 를 AppSettings 모델로 감싸는 Repository.
 *
 * - 읽기: settings Flow 로 구독 (실시간 갱신)
 * - 쓰기: 개별 setXxx suspend 함수
 *
 * 과거에는 currentBlocking() 으로 메인 스레드에서 동기 조회했으나
 * ANR 위험 때문에 제거했다. 초기값이 필요한 화면은 AppSettings.Default 로 렌더링한 뒤
 * Flow 가 도달하면 실제 값으로 전환한다.
 *
 * 2026-04-19 확장 (Gemma 자동 다운로드 하이브리드):
 *  - `gemma_model_url` / `gemma_model_sha256` / `gemma_license_accepted` 3종 추가.
 *  - AppSettings 는 UI 렌더링용 전역 상태라 여기 포함하지 않고 개별 Flow 로만 노출.
 *    (모델 관련 값이 테마/폰트 Flow 를 자주 흔들지 않도록 분리.)
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val keyTheme = stringPreferencesKey("theme_mode")
    private val keyFont = stringPreferencesKey("code_font_size")
    private val keyGrid = booleanPreferencesKey("camera_grid")

    // 2026-04-19 — Gemma `.litertlm` 다운로드 관련 설정 키.
    private val keyGemmaUrl = stringPreferencesKey("gemma_model_url")
    private val keyGemmaSha = stringPreferencesKey("gemma_model_sha256")
    private val keyGemmaLicense = booleanPreferencesKey("gemma_license_accepted")

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            theme = prefs[keyTheme]?.let { safeThemeOf(it) } ?: AppSettings.Default.theme,
            codeFontSize = prefs[keyFont]?.let { safeFontOf(it) } ?: AppSettings.Default.codeFontSize,
            cameraGridVisible = prefs[keyGrid] ?: AppSettings.Default.cameraGridVisible
        )
    }

    /**
     * Gemma `.litertlm` 다운로드 URL. 기본값은 `giftdinosaur/gemma-3n-handcode`
     * HuggingFace public repo 의 resolve URL (2026-04-19 업로드 + 검증 완료).
     *
     * 사용자가 Settings 에서 본인의 다른 URL 로 덮어쓸 수 있지만 기본 흐름은
     * 이 기본값으로 라이선스 수락 즉시 자동 다운로드 진입.
     */
    val gemmaModelUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[keyGemmaUrl] ?: GemmaModelManager.DEFAULT_URL
    }

    /**
     * Gemma `.litertlm` SHA-256 기대값. 기본값은 위 기본 URL 의 파일에 대해 실측된 해시.
     * 사용자가 다른 URL 을 설정하면 검증이 실패하므로 같은 화면에서 해시도 갱신해야 한다.
     */
    val gemmaModelSha256: Flow<String> = dataStore.data.map { prefs ->
        prefs[keyGemmaSha] ?: GemmaModelManager.DEFAULT_SHA256
    }

    /** Gemma 사용 라이선스 (ai.google.dev/gemma/terms) 수락 여부. */
    val gemmaLicenseAccepted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keyGemmaLicense] ?: false
    }

    suspend fun setTheme(mode: ThemeMode) {
        dataStore.edit { it[keyTheme] = mode.name }
    }

    suspend fun setCodeFontSize(size: FontSize) {
        dataStore.edit { it[keyFont] = size.name }
    }

    suspend fun setCameraGridVisible(visible: Boolean) {
        dataStore.edit { it[keyGrid] = visible }
    }

    suspend fun setGemmaModelUrl(url: String) {
        dataStore.edit { it[keyGemmaUrl] = url.trim() }
    }

    suspend fun setGemmaModelSha256(sha: String) {
        dataStore.edit { it[keyGemmaSha] = sha.trim().lowercase() }
    }

    suspend fun setGemmaLicenseAccepted(accepted: Boolean) {
        dataStore.edit { it[keyGemmaLicense] = accepted }
    }

    /* ---------- 내부 안전 파서 (저장된 문자열이 enum 에 없을 때 Default 로 fallback) ---------- */

    private fun safeThemeOf(raw: String): ThemeMode = runCatching {
        ThemeMode.valueOf(raw)
    }.getOrDefault(AppSettings.Default.theme)

    private fun safeFontOf(raw: String): FontSize = runCatching {
        FontSize.valueOf(raw)
    }.getOrDefault(AppSettings.Default.codeFontSize)
}
