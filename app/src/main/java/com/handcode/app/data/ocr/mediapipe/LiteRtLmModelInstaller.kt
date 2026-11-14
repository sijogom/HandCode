package com.handcode.app.data.ocr.mediapipe

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Gemma-3n E2B `.litertlm` 모델 설치 유틸.
 *
 * MediaPipe LLM Inference API 는 파일 시스템의 절대 경로만 받으므로, 사용자가 Storage Access
 * Framework(SAF) 등으로 선택한 Uri 를 앱 내부 저장소(`filesDir/models/`) 로 복사한 뒤 경로를
 * 엔진에 전달한다.
 *
 * - 배포용 APK 는 Play Store 100MB 제한으로 3.4GB `.litertlm` 번들 불가 → 외부에서 받아 SAF 로
 *   선택하거나 별도 CDN 다운로드가 현실 경로. MVP 는 SAF 복사 기반.
 * - 모델 파일명은 [TARGET_NAME] 로 고정해 엔진이 경로 탐색 없이 참조 가능.
 */
object LiteRtLmModelInstaller {
    /** 앱 내부 저장소에 저장되는 표준 파일명. */
    const val TARGET_NAME = "gemma3n.litertlm"

    /**
     * 앱 내부 저장소의 모델 파일 [File]. 부모 디렉토리는 호출 시점에 자동 생성.
     */
    fun targetFile(context: Context): File =
        File(context.filesDir, "models/$TARGET_NAME").apply { parentFile?.mkdirs() }

    /**
     * SAF 등으로 받은 [source] Uri 의 바이트를 [targetFile] 로 복사.
     * 복사 성공 시 [File] 반환, 실패 시 null.
     */
    fun installFromUri(context: Context, source: Uri): File? {
        val target = targetFile(context)
        return runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("openInputStream 이 null 을 반환함: $source")
            target
        }.getOrNull()
    }

    /** 이미 설치된 모델이 있는지 확인. */
    fun isInstalled(context: Context): Boolean = targetFile(context).exists()
}
