package com.handcode.app.data.ocr.mediapipe

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Gemma-3n `.litertlm` 모델 자동 다운로드 매니저.
 *
 * ## 설계
 * Ente `mobile_ocr` 의 `ModelManager.kt` 패턴을 단일 파일 / 3.4GB 규모에 맞게 포팅.
 * - 순수 `HttpURLConnection` + `MessageDigest` (SHA-256) + `Mutex` + Coroutine 조합.
 *   WorkManager / OkHttp 의존성을 추가하지 않는다.
 * - 저장 경로는 기존 [LiteRtLmModelInstaller.targetFile] 재사용 → MediaPipe 엔진 변경 없음.
 *
 * ## 왜 URL 이 기본 placeholder 인가
 * - HuggingFace `google/gemma-3n-E2B-it-litert-lm` 은 Gated repo (로그인 + 라이선스 수락 필수).
 *   단순 GET 으로 403 이 떨어지므로 공개 CDN 미러가 필요하다.
 * - 팀 자체 CDN (예: Cloudflare R2, S3) 에 호스팅한 뒤 사용자가 Settings 에서 URL 만 교체
 *   하는 흐름을 염두에 둔다. 빈 문자열이면 SAF 폴백 (기존 [LiteRtLmModelInstaller]) 로.
 *
 * ## SHA-256 검증
 * - `expectedSha256` 가 blank 이면 검증 스킵 (placeholder / 테스트 URL 대응).
 * - 지정된 경우 다운로드 완료 후 실제 digest 와 소문자 대조.
 *
 * ## 진행률 콜백
 * - `onProgress(downloaded, total)` — 매 8KB 버퍼마다 호출.
 * - `total` 은 `Content-Length` 헤더 값. 서버가 미제공 시 -1.
 *
 * ## 재진입 방지
 * - [modelMutex] 로 동시 호출 차단. 이미 다운로드 중일 때 두 번째 호출은 대기.
 */
class GemmaModelManager(private val context: Context) {

    private val modelMutex = Mutex()

    /**
     * 모델 파일이 이미 존재하고 (선택적) SHA-256 이 일치하면 그 파일을 그대로 반환.
     * 없거나 불일치 시 [url] 에서 다운로드 → 최종 [File] 반환.
     *
     * 실패 시 [Result.failure] 로 반환하므로 호출자가 UI 알림 등으로 처리.
     */
    suspend fun ensureModel(
        url: String,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = runCatching {
        modelMutex.withLock {
            if (url.isBlank()) {
                throw IOException("다운로드 URL 이 비어 있습니다 — SAF 폴백 또는 URL 설정이 필요합니다.")
            }
            val target = LiteRtLmModelInstaller.targetFile(context)

            // 이미 설치되어 있고 해시가 유효하면 재다운로드 스킵.
            if (target.exists() && isValidSha(target, expectedSha256)) {
                Timber.tag(TAG).i("모델 재사용: %s (%.1f MB)", target.absolutePath, target.length() / 1024f / 1024f)
                return@withLock target
            }
            if (target.exists()) {
                Timber.tag(TAG).w("기존 모델이 유효하지 않음 — 재다운로드")
                target.delete()
            }

            downloadToFile(url, target, onProgress)

            if (!isValidSha(target, expectedSha256)) {
                target.delete()
                throw IOException("다운로드 완료 후 SHA-256 검증 실패")
            }

            Timber.tag(TAG).i("모델 다운로드 완료: %s (%.1f MB)", target.absolutePath, target.length() / 1024f / 1024f)
            target
        }
    }

    /**
     * 파일 시스템 기반 존재 여부만 확인. (해시 미검증 — 단순 UI 상태용)
     */
    fun isInstalled(context: Context): Boolean = LiteRtLmModelInstaller.isInstalled(context)

    /**
     * 모델 파일을 삭제해 "미설치" 상태로 되돌림. 재다운로드 진입용.
     */
    fun uninstall(context: Context) {
        val target = LiteRtLmModelInstaller.targetFile(context)
        if (target.exists()) {
            val ok = target.delete()
            Timber.tag(TAG).i("모델 삭제 — ok=%b, path=%s", ok, target.absolutePath)
        }
    }

    /* ---------- 내부 구현 ---------- */

    private suspend fun downloadToFile(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val parent = target.parentFile ?: error("target 의 parent directory 가 null: $target")
        if (!parent.exists()) parent.mkdirs()

        // 고정 이름의 부분 다운로드 파일 — 중단 시 다음 재시도 때 이어받을 수 있도록
        // 기존 `File.createTempFile` 의 랜덤 이름 방식 (매 호출 새 파일) 을 버리고
        // target 파일 옆에 `<target>.download` 로 고정 경로를 쓴다.
        val tempFile = File(parent, "${target.name}$TMP_SUFFIX")
        val existingBytes = if (tempFile.exists()) tempFile.length() else 0L

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                if (existingBytes > 0) {
                    // RFC 7233 Range — 기존 받은 바이트 이후부터만 요청.
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
            }

            val code = connection.responseCode
            val appendMode = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    Timber.tag(TAG).i("이어받기 — existing=%d bytes", existingBytes)
                    true
                }
                HttpURLConnection.HTTP_OK -> {
                    // 서버가 Range 를 무시하고 전체 파일을 주는 경우. 기존 부분 파일 버리고 처음부터.
                    if (existingBytes > 0) {
                        Timber.tag(TAG).w("서버가 Range 응답 안 함 (HTTP 200) — 처음부터 다운로드")
                        tempFile.delete()
                    }
                    false
                }
                else -> throw IOException("다운로드 실패 — HTTP $code (url=$url)")
            }

            val remainingContent = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val totalFileSize = if (appendMode && remainingContent > 0) existingBytes + remainingContent
                                else remainingContent
            Timber.tag(TAG).i(
                "다운로드 진행 — start=%d, total=%d, url=%s",
                if (appendMode) existingBytes else 0L,
                totalFileSize,
                url
            )

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, appendMode).use { output ->
                    val startOffset = if (appendMode) existingBytes else 0L
                    streamWithProgress(input, output, totalFileSize, startOffset, onProgress)
                }
            }

            if (target.exists()) target.delete()
            if (!tempFile.renameTo(target)) {
                // rename 실패 시 복사 → 원본 삭제로 fallback.
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
            }
        } finally {
            connection?.disconnect()
            // tempFile 은 의도적으로 남긴다 — 다운로드가 성공하면 위 renameTo 로 이미 사라졌고,
            // 중간 실패라면 다음 호출 때 existingBytes 로 이어받기 위해 남겨둬야 한다.
        }
    }

    private fun streamWithProgress(
        input: InputStream,
        output: FileOutputStream,
        total: Long,
        startOffset: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var downloaded = startOffset
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            downloaded += read
            onProgress(downloaded, total)
        }
        output.flush()
    }

    private fun isValidSha(file: File, expectedSha256: String?): Boolean {
        // expectedSha256 가 null / blank 이면 검증 스킵 (placeholder URL 대응).
        if (expectedSha256.isNullOrBlank()) return file.exists() && file.length() > 0L
        if (!file.exists()) return false
        val actual = computeSha256(file)
        val match = actual.equals(expectedSha256, ignoreCase = true)
        if (!match) {
            Timber.tag(TAG).w("SHA-256 불일치 — expected=%s, actual=%s", expectedSha256, actual)
        }
        return match
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val TAG = "GemmaModelManager"

        // HttpURLConnection 기본 타임아웃 — 초기 응답 15s, 개별 read 60s.
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val BUFFER_SIZE = 8 * 1024

        private const val TMP_PREFIX = "gemma3n_"
        private const val TMP_SUFFIX = ".download"

        /**
         * 공식 다운로드 URL. `giftdinosaur/gemma-3n-handcode` HuggingFace public repo 에
         * `.litertlm` 을 업로드해 두었으며, 302 → xethub.hf.co S3 presigned URL 로 리다이렉트됨.
         * `HttpURLConnection.instanceFollowRedirects=true` 로 자동 추적.
         *
         * 검증 (2026-04-19 실측):
         *  - HTTP 302 Found
         *  - 리다이렉트 후 HTTP 200
         *  - 파일 크기 3,655,827,456 bytes (3.66 GB)
         *  - SHA-256 `2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6`
         *  - 첫 1MB 해시 로컬 원본과 일치 확인 완료
         */
        const val DEFAULT_URL =
            "https://huggingface.co/giftdinosaur/gemma-3n-handcode/resolve/main/gemma-3n-E2B-it-int4.litertlm"

        const val DEFAULT_SHA256 =
            "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6"

        const val DEFAULT_SIZE_BYTES = 3_655_827_456L

        /** 기존 코드 경로 호환용 alias (placeholder 라는 이름은 이제 부정확). */
        @Deprecated("실제 URL 상수로 승격됨. DEFAULT_URL 사용 권장.", ReplaceWith("DEFAULT_URL"))
        const val DEFAULT_URL_PLACEHOLDER = DEFAULT_URL
    }
}
