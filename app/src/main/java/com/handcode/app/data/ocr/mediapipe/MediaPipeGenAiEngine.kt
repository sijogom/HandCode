package com.handcode.app.data.ocr.mediapipe

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.handcode.app.data.ocr.postprocess.IndentReconstructor
import com.handcode.app.domain.model.OcrEngineType
import com.handcode.app.domain.model.OcrResult
import com.handcode.app.domain.model.RecognizedBlock
import com.handcode.app.domain.repository.OcrEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe GenAI LLM Inference API + Gemma-3n E2B (.litertlm) 기반 VLM OCR 엔진.
 *
 * ## 동작
 *  1. `context.filesDir/models/gemma3n.litertlm` 을 model path 로 사용. 파일 없으면 즉시 실패.
 *  2. 입력 Bitmap → `BitmapImageBuilder` → `MPImage`.
 *  3. Python transcribe 프롬프트 + 이미지 한 장을 세션에 추가하고 `generateResponse()` 실행.
 *  4. 응답을 `\n` 기준으로 분해해 [RecognizedBlock] 더미로 감싸고,
 *     `IndentReconstructor` (1D Meanshift) 로 들여쓰기 레벨을 뽑아 재조립.
 *     응답 선두 공백 수 × 8px 을 가상 left 좌표로 사용해 클러스터링 가능.
 *  5. 최종 텍스트 + 라인별 들여쓰기 레벨을 [OcrResult] 로 반환.
 *
 * ## 자원 관리
 *  - `LlmInference` / `LlmInferenceSession` 은 per-call `.use { }` 로 즉시 닫는다.
 *    단일 엔진 전환 직후 단순 동작 보장이 우선. 추후 지연 최적화 시 singleton 화 검토.
 *
 * ## 모델 부재
 *  - 모델 파일이 없으면 빈 `OcrResult` 반환 (호출자가 UI 메시지로 "모델을 선택해 주세요" 안내).
 *
 * ## 엔진 타입
 *  - [OcrEngineType.VLM] 로 표기. EditorScreen 배지는 "Gemma-3n" 로 UI 에서 치환.
 */
@Singleton
class MediaPipeGenAiEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrEngine {

    private val modelPath: File by lazy {
        LiteRtLmModelInstaller.targetFile(context)
    }

    private val llmOptions: LlmInference.LlmInferenceOptions by lazy {
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .setMaxNumImages(MAX_IMAGES)
            .build()
    }

    private val sessionOptions: LlmInferenceSession.LlmInferenceSessionOptions by lazy {
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(TOP_K)
            .setTemperature(TEMPERATURE)
            .setGraphOptions(
                GraphOptions.builder().setEnableVisionModality(true).build()
            )
            .build()
    }

    override suspend fun recognize(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        // 조용한 실패 방지 — 모델이 없으면 호출자(CameraViewModel) 가 빨간 배너로 안내하도록
        // 예외를 던진다. 이전 버전은 EMPTY_RESULT 를 반환해 Editor 로 넘어가 "0줄" 로 보였음.
        if (!modelPath.exists()) {
            Timber.tag(TAG).w("Gemma-3n .litertlm 모델이 %s 에 없음", modelPath.absolutePath)
            throw IllegalStateException(
                "Gemma 모델이 설치되지 않았어요. 설정 화면에서 모델 파일을 선택해 주세요."
            )
        }

        val mpImage = BitmapImageBuilder(bitmap).build()

        // 2026-04-20 logcat 분석 결과: cancel(true) 가 decode 진행 중인 native worker 의
        // 메모리를 corrupt 시켜 XNNPack thread pool 에서 SIGSEGV 발생 (LlmLiteRTExecutor::
        // DecodeInternal #11 frame). cancel 자체를 제거하고 sync generateResponse() 사용.
        // 추론 시간 단축은 MAX_TOKENS 와 이미지 해상도 축소로 처리.
        val llm = LlmInference.createFromOptions(context, llmOptions)
        val session = try {
            LlmInferenceSession.createFromOptions(llm, sessionOptions)
        } catch (t: Throwable) {
            runCatching { llm.close() }
            throw t
        }

        val rawResponse = try {
            runCatching {
                session.addQueryChunk(PROMPT)
                session.addImage(mpImage)
                session.generateResponse()
            }.onFailure {
                Timber.tag(TAG).e(it, "MediaPipe 추론 실패")
            }.getOrNull().orEmpty()
        } finally {
            runCatching { session.close() }
            runCatching { llm.close() }
        }

        // 이중 안전망: 스트리밍 조기 종료가 실패해도 (stop token 이 한 chunk 내에서
        // 여러 개 붙어서 도착하는 등 edge case) 후처리에서 한 번 더 트리밍한다.
        val response = rawResponse
            .substringBefore("<end_of_turn>")
            .substringBefore("<start_of_turn>")
            .substringBefore("<eos>")
            .trimEnd()

        // 모델이 있지만 응답이 비었을 때도 Editor 0줄 로 떨어지지 않고 에러 배너를 띄운다.
        if (response.isBlank()) {
            throw IllegalStateException(
                "인식된 글자가 없어요. 글씨가 선명한 사진으로 다시 촬영해 주세요."
            )
        }

        val lines = response.split('\n').filter { it.isNotBlank() }
        val blocks = lines.mapIndexed { idx, line ->
            val leading = line.takeWhile { it == ' ' }.length
            RecognizedBlock(
                text = line.trimStart(),
                confidence = 1.0f,
                top = idx * LINE_HEIGHT_PX,
                left = leading * SPACE_WIDTH_PX,
                width = line.length * CHAR_WIDTH_PX,
                height = LINE_HEIGHT_PX,
            )
        }
        val indented = IndentReconstructor.reconstruct(blocks)
        val finalText = indented.text.ifEmpty { response.trimEnd() }
        val lineCount = if (finalText.isEmpty()) 0 else finalText.count { it == '\n' } + 1

        OcrResult(
            recognizedText = finalText,
            confidence = 1.0f,
            lineCount = lineCount,
            textBlocks = blocks,
            engineUsed = OcrEngineType.VLM,
            indentLevels = indented.levels,
        )
    }

    companion object {
        private const val TAG = "MediaPipeGenAiEngine"

        // MediaPipe LLM 세션 파라미터.
        // MAX_TOKENS 2048 → 512: cancel 제거 후 추론 시간 단축 수단. input(이미지~256 token +
        // prompt~50) + output 합. 손글씨 코드 5~10줄은 output 약 50~100 token 이라 충분.
        // 추론 시간 5분 → 약 17~30초 예상.
        private const val MAX_TOKENS = 512
        private const val MAX_IMAGES = 1
        private const val TOP_K = 10
        private const val TEMPERATURE = 0.4f

        // Gaussian indent 재조립용 가상 좌표 스케일 (실측 기반 heuristic).
        private const val LINE_HEIGHT_PX = 30
        private const val SPACE_WIDTH_PX = 8
        private const val CHAR_WIDTH_PX = 10

        private const val PROMPT =
            "Transcribe the handwritten Python code in the image exactly as written. " +
                "Preserve indentation with spaces (use 4 spaces per level). " +
                "Keep all punctuation. Output only the code, no explanation, no markdown fences."

        // Gemma chat template stop sequences. 토큰 단위 스트리밍 콜백에서 검출되면
        // 즉시 추론을 중단해 5분 → 수초 수준으로 단축한다.
        private val STOP_TOKENS = listOf("<end_of_turn>", "<start_of_turn>", "<eos>")

        private val EMPTY_RESULT = OcrResult(
            recognizedText = "",
            confidence = 0f,
            lineCount = 0,
            textBlocks = emptyList(),
            engineUsed = OcrEngineType.VLM,
            indentLevels = emptyList(),
        )
    }
}
