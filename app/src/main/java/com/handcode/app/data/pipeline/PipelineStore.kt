package com.handcode.app.data.pipeline

import com.handcode.app.data.local.dao.SessionSnapshotDao
import com.handcode.app.data.local.entity.SessionSnapshotEntity
import com.handcode.app.domain.model.CodeRecord
import com.handcode.app.domain.model.CodeResult
import com.handcode.app.domain.model.CodeSession
import com.handcode.app.domain.model.OcrResult
import com.handcode.app.domain.model.RecognizedBlock
import com.handcode.app.domain.model.ValidationResult
import com.handcode.app.ui.screen.pipeline.PipelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 현재 진행 중인 코드 세션 하나를 메모리에 들고 있는 Hilt 싱글톤 저장소.
 *
 * 5주차에 파이프라인 상태머신까지 이 Store 가 함께 보관하도록 확장되었다.
 * Editor / Result / Camera 화면의 PipelineViewModel 들이 각자 인스턴스로 만들어지더라도
 * [pipelineState] 를 공유하므로 상태 손실 없이 화면 전환이 가능하다.
 *
 * 7주차 추가:
 *   - SessionSnapshotDao 를 주입받아 편집 중인 세션을 Room 에 선제 저장한다.
 *   - startWith / startFromExisting / updateCode 시점에 자동 persist.
 *   - 앱 시작 시 [restoreSnapshotIfAny] 로 복원, 정식 저장 후에는 clear 호출.
 *
 * 원칙:
 *   - 이 클래스는 상태를 "담는" 역할만. 상태 전이 로직은 PipelineViewModel 에서 결정한다.
 *   - Navigation 인자로 큰 객체(OcrResult 등)를 실어 보내는 대신 이 Store 의 StateFlow 를 구독한다.
 *   - DB 저장은 전용 CoroutineScope 에서 fire-and-forget 으로 처리해
 *     UI/ViewModel 스코프와 생명주기를 분리한다.
 */
@Singleton
class PipelineStore @Inject constructor(
    private val snapshotDao: SessionSnapshotDao
) {

    /**
     * 스냅샷 I/O 전용 application-scope.
     * Singleton 객체의 생존 기간 동안만 유지되고, 하나의 코루틴 실패가 다른 저장을 막지 않도록 SupervisorJob.
     */
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _current = MutableStateFlow<CodeSession?>(null)
    val current: StateFlow<CodeSession?> = _current

    /**
     * 5주차 핵심 필드 — 파이프라인 상태 머신의 유일한 진실 원천.
     * PipelineViewModel 들이 이걸 노출하고, 동일 ViewModel 들이 이걸 변경한다.
     */
    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val pipelineState: StateFlow<PipelineState> = _pipelineState

    /**
     * [updateCode] 호출에 대한 debounce 전용 Job.
     * 키 입력 한 글자마다 DB 쓰기가 쌓이지 않도록, 마지막 입력 이후 [SNAPSHOT_DEBOUNCE_MS] 가 지나야 실제 저장한다.
     * 다른 경로(startWith / startFromExisting / clear)의 스냅샷 I/O 는 즉시 수행되므로 이 Job 과 무관하다.
     */
    private var snapshotJob: Job? = null

    /** 새 OCR 결과로 세션 시작. 카메라/갤러리에서 인식 성공 직후 호출. */
    fun startWith(ocr: OcrResult): CodeSession {
        val session = CodeSession(
            id = System.currentTimeMillis(),
            ocrResult = ocr,
            editedCode = ocr.recognizedText,
            validation = null,
            execution = null,
            // 신규 세션이라 대응되는 DB 기록이 아직 없음 → autoGenerate 경로 사용.
            recordId = null
        )
        _current.value = session
        // 새 세션이 시작되면 편집 단계로 전이. 화면 진입 시 오버레이가 바로 사라지도록.
        _pipelineState.value = PipelineState.Editing(session)
        persistSnapshotAsync(session)
        return session
    }

    /**
     * 기존 DB 기록을 편집 세션으로 복원.
     * 홈 화면의 최근 이력 카드를 탭했을 때 사용한다.
     * OcrResult 는 DB 에 남아 있지 않으니 confidence 만 복원하고 blocks 는 빈 리스트.
     */
    fun startFromExisting(record: CodeRecord) {
        val restoredOcr = OcrResult(
            recognizedText = record.code,
            confidence = record.ocrConfidence,
            lineCount = record.code.count { it == '\n' } + 1,
            textBlocks = emptyList<RecognizedBlock>()
        )
        val session = CodeSession(
            id = record.id.takeIf { it != 0L } ?: System.currentTimeMillis(),
            ocrResult = restoredOcr,
            editedCode = record.code,
            validation = null,
            execution = null,
            // DB 에 이미 존재하는 record 이므로 id 를 끌고 다닌다 →
            // 이후 toCodeRecord() 저장 시 같은 id 로 REPLACE 되어 중복 row 가 생기지 않는다.
            recordId = record.id.takeIf { it != 0L }
        )
        _current.value = session
        _pipelineState.value = PipelineState.Editing(session)
        persistSnapshotAsync(session)
    }

    /**
     * 편집 중인 코드 텍스트만 갱신. 상태 머신은 유지 (Editing 상태 그대로).
     *
     * 스냅샷 저장은 [SNAPSHOT_DEBOUNCE_MS] 만큼 debounce 한다.
     * 사용자가 연속으로 타이핑할 때 매 글자 DB 쓰기를 쌓지 않고, 입력이 잠시 멈춘 뒤 한 번만 저장.
     * (메모리 상태 [_current] / [_pipelineState] 는 즉시 갱신되므로 UI 는 영향 없음)
     */
    fun updateCode(code: String) {
        _current.update { it?.copy(editedCode = code) }
        // Editing 상태에 담긴 session 도 최신 코드로 맞춘다 (UI 에서 세션 참조 시 편의).
        val snapshot = _pipelineState.value
        if (snapshot is PipelineState.Editing) {
            _current.value?.let { _pipelineState.value = PipelineState.Editing(it) }
        }
        val target = _current.value ?: return
        snapshotJob?.cancel()
        snapshotJob = storeScope.launch {
            delay(SNAPSHOT_DEBOUNCE_MS)
            // delay 이후 current 가 또 바뀌었을 수 있으니 최신 값으로 다시 읽는다.
            val latest = _current.value ?: target
            runCatching { saveSnapshot(latest) }
                .onFailure { Timber.tag(TAG).w(it, "스냅샷 저장 실패") }
        }
    }

    /** 검증 결과 첨부. */
    fun attachValidation(v: ValidationResult) {
        _current.update { it?.copy(validation = v) }
    }

    /**
     * 원본 이미지 파일 경로 첨부. F7 — 편집 화면 "원본 보기" 액션용.
     * 촬영 직후 `CameraViewModel` 이 호출한다 (갤러리 content Uri 는 파일 경로가 없어 호출 생략).
     */
    fun setOriginalImagePath(path: String) {
        _current.update { it?.copy(originalImagePath = path) }
    }

    /** 실행 결과 첨부. 결과 화면에서 사용할 최종 세션 반환. */
    fun attachResult(r: CodeResult): CodeSession? {
        _current.update { it?.copy(execution = r) }
        return _current.value
    }

    /**
     * 파이프라인 상태 직접 갱신. PipelineViewModel 에서만 호출한다.
     * - 상태 머신 전이는 ViewModel 로직에서 책임지고, Store 는 그 결과를 담는다.
     */
    fun setPipelineState(state: PipelineState) {
        _pipelineState.value = state
    }

    /** 세션 종료 + 상태 Idle 로. 홈으로 돌아갈 때 호출. 스냅샷도 함께 제거. */
    fun clear() {
        // debounce 대기 중이던 저장이 있으면 취소 — clear 이후에 뒤늦게 저장되면 안 된다.
        snapshotJob?.cancel()
        snapshotJob = null
        _current.value = null
        _pipelineState.value = PipelineState.Idle
        clearSnapshotAsync()
    }

    /**
     * 정식 저장 완료 후 호출 — 스냅샷만 지우고 메모리 세션은 유지.
     * (결과 화면에서 "다시 편집" 시 세션이 있어야 하므로)
     */
    fun clearSnapshotOnly() {
        clearSnapshotAsync()
    }

    /**
     * 앱 시작 시(Application.onCreate) 1회 호출.
     * DB 에 스냅샷이 남아 있으면 current / pipelineState 를 복원한다.
     * 메모리에 이미 세션이 있으면 복원하지 않는다 (우선순위: 메모리 > DB).
     */
    suspend fun restoreSnapshotIfAny() {
        if (_current.value != null) return
        val snap = runCatching { snapshotDao.peek() }
            .onFailure { Timber.tag(TAG).w(it, "스냅샷 조회 실패") }
            .getOrNull() ?: return

        val restoredOcr = OcrResult(
            recognizedText = snap.recognizedText,
            confidence = snap.confidence,
            lineCount = snap.recognizedText.count { it == '\n' } + 1,
            textBlocks = emptyList<RecognizedBlock>()
        )
        val session = CodeSession(
            id = snap.createdAt,
            ocrResult = restoredOcr,
            editedCode = snap.codeText,
            validation = null,
            execution = null,
            // SessionSnapshotEntity 에는 CodeRecord id 가 담기지 않는다 (스냅샷은 편집 중 임시 보존용).
            // 따라서 복원된 세션은 "아직 저장 안 된 신규"로 간주 → 첫 저장 시 autoGenerate.
            recordId = null
        )
        _current.value = session
        _pipelineState.value = PipelineState.Editing(session)
        Timber.tag(TAG).d("스냅샷 복원 완료: %d chars", snap.codeText.length)
    }

    /* ---------- 스냅샷 I/O (private) ---------- */

    /**
     * startWith / startFromExisting 경로에서 사용하는 즉시 저장.
     * updateCode 경로는 debounce 를 거쳐 [saveSnapshot] 을 직접 호출한다.
     */
    private fun persistSnapshotAsync(session: CodeSession) {
        storeScope.launch {
            runCatching { saveSnapshot(session) }
                .onFailure { Timber.tag(TAG).w(it, "스냅샷 저장 실패") }
        }
    }

    /** 실제 DAO 저장 호출부 — debounce 경로와 즉시 저장 경로가 공유한다. */
    private suspend fun saveSnapshot(session: CodeSession) {
        snapshotDao.save(
            SessionSnapshotEntity(
                id = SessionSnapshotEntity.SINGLE_ROW_ID,
                codeText = session.editedCode,
                recognizedText = session.ocrResult.recognizedText,
                confidence = session.ocrResult.confidence,
                createdAt = session.id
            )
        )
    }

    private fun clearSnapshotAsync() {
        storeScope.launch {
            runCatching { snapshotDao.clear() }
                .onFailure { Timber.tag(TAG).w(it, "스냅샷 삭제 실패") }
        }
    }

    companion object {
        private const val TAG = "PipelineStore"

        /** updateCode debounce 간격 (ms). 타이핑이 멈춘 이 시간만큼 후에만 DB 에 반영. */
        private const val SNAPSHOT_DEBOUNCE_MS = 500L
    }
}
