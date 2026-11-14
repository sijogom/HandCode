package com.handcode.app.data.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import kotlin.math.max

private const val TAG = "ImagePreparer"

/**
 * 촬영/선택된 이미지를 OCR 입력용 Bitmap 으로 정리한다.
 *
 * 수행 순서: 디코딩(해상도 축소) → EXIF 회전 보정 → 가이드 영역 중앙 크롭(세로/가로 자동).
 *
 * 설계 원칙:
 *  - ML Kit v2 Latin 모델은 내부에서 normalization 을 수행하므로 외부 전처리(그레이스케일/대비)는
 *    중복 적용이 되어 오히려 경계를 뭉갠다 → **전처리 없이 원본 비트맵을 그대로 넘긴다**.
 *  - 크롭 비율은 이미지 `width`/`height` 비교로 자동 전환(세로는 4:3, 가로는 3:4).
 *  - 모든 실패 경로는 `Log.w`/`Log.d` 로 원인이 남게 하여 실기기 재검증 시 `logcat` 추적 가능.
 *  - 중간 Bitmap(decoded, rotated) 은 다음 단계에서 새 Bitmap 을 만든 직후 `recycle()` — 저사양
 *    기기에서 연속 촬영 시 OOM 방지.
 *
 * **공개 API 주의**: `prepare(path)` 는 `File` 경로를 받지만 현재 호출부(`CameraViewModel`) 는
 * `context.cacheDir` 내부 파일만 전달한다. 외부 입력을 바로 넘기는 용도로는 설계되지 않았다.
 */
object ImagePreparer {

    // 2026-04-20: 사용자 보고 "가이드 박스 밖 글자도 인식" (v2 + backup 둘 다).
    //   0.82 × 4/3 크롭 영역이 카메라 가이드 박스와 어긋나서 가이드 안 글씨 일부가 잘리거나
    //   가이드 밖 글씨가 포함됨. 0.98 (거의 전체 너비) 로 확대 + 비율 1.3/0.6 로 변경해 가이드
    //   박스가 어디에 있든 OCR 입력 영역에 포함되도록 한다.
    private const val GUIDE_WIDTH_RATIO = 0.98f

    /**
     * 세로 이미지(height >= width) 에서 사용할 크롭 높이 비율.
     * cropHeight = cropWidth * 1.3 → 세로 직사각형 (사용자 가이드와 거의 일치).
     */
    private const val PORTRAIT_CROP_HEIGHT_RATIO = 1.3f

    /**
     * 가로 이미지(width > height) 에서 사용할 크롭 높이 비율.
     * cropHeight = cropWidth * 0.6 → 가로 직사각형 (clamp 로 실제로는 height 전체 사용).
     */
    private const val LANDSCAPE_CROP_HEIGHT_RATIO = 0.6f

    /**
     * OCR 정확도와 메모리 피크의 타협점.
     * 2026-04-20: 1600 → 800. backup VLM (Gemma-3n vision) 의 prefill 시간 단축 + decode
     * 도중 SIGSEGV (logcat XNNPack thread pool corruption) 회피용. 손글씨 코드 인식에는
     * 800px 면 충분 (글자 크기 ~30px → 가독성 유지).
     */
    private const val TARGET_MAX_SIDE = 800

    /** 파일 경로의 JPEG 을 읽어서 정리된 Bitmap 을 반환. 실패 시 null. */
    fun prepare(path: String): Bitmap? {
        val decoded = decodeScaled(path) ?: return null
        val rotated = decoded.applyExifRotation(path)
        return rotated.cropToCodeArea()
    }

    /** 갤러리에서 선택된 Uri 를 읽어 정리된 Bitmap 반환. 실패 시 null. */
    fun prepareFromUri(context: Context, uri: Uri): Bitmap? {
        val decoded = decodeScaledFromUri(context, uri) ?: return null
        val rotated = decoded.applyExifRotationFromUri(context, uri)
        return rotated.cropToCodeArea()
    }

    /* ---------- 공용 내부 함수 ---------- */

    private fun decodeScaled(path: String, targetMaxSide: Int = TARGET_MAX_SIDE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "decodeFile bounds yielded invalid size for $path — aborting")
            return null
        }
        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetMaxSide)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, opts) }
            .onFailure { Log.w(TAG, "decodeFile failed for $path", it) }
            .getOrNull()
    }

    private fun decodeScaledFromUri(
        context: Context,
        uri: Uri,
        targetMaxSide: Int = TARGET_MAX_SIDE
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri).use { stream: InputStream? ->
                stream?.let { BitmapFactory.decodeStream(it, null, bounds) }
            }
        }.onFailure { Log.w(TAG, "decodeStream bounds failed for $uri", it) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "decodeStream bounds yielded invalid size for $uri — aborting")
            return null
        }

        val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetMaxSide)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            context.contentResolver.openInputStream(uri).use { stream: InputStream? ->
                stream?.let { BitmapFactory.decodeStream(it, null, opts) }
            }
        }
            .onFailure { Log.w(TAG, "decodeStream failed for $uri", it) }
            .getOrNull()
    }

    private fun computeInSampleSize(width: Int, height: Int, targetMaxSide: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (max(width, height) / sample > targetMaxSide) {
            sample *= 2
        }
        return sample
    }

    private fun Bitmap.applyExifRotation(path: String): Bitmap {
        val degrees = readExifRotationDegrees { ExifInterface(path) }
        Log.i(TAG, "applyExifRotation: path=$path exif=${degrees}°, before=${width}x${height}")
        val rotated = rotateIfNeeded(degrees)
        Log.i(TAG, "applyExifRotation: after=${rotated.width}x${rotated.height}")
        return rotated
    }

    private fun Bitmap.applyExifRotationFromUri(context: Context, uri: Uri): Bitmap {
        val degrees = readExifRotationDegrees {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }
        Log.i(TAG, "applyExifRotationFromUri: uri=$uri exif=${degrees}°, before=${width}x${height}")
        val rotated = rotateIfNeeded(degrees)
        Log.i(TAG, "applyExifRotationFromUri: after=${rotated.width}x${rotated.height}")
        return rotated
    }

    /**
     * 회전이 필요한 경우 새 Bitmap 을 만들고 원본은 recycle — 중간 프레임 누적으로 인한 OOM 방지.
     * 0° 인 경우 원본을 그대로 반환하므로 이후 단계에서 본인을 recycle 하면 안 된다.
     */
    private fun Bitmap.rotateIfNeeded(degrees: Float): Bitmap {
        if (degrees == 0f) return this
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
        if (rotated !== this) recycle()
        return rotated
    }

    private fun readExifRotationDegrees(supplier: () -> ExifInterface?): Float {
        val exif = runCatching { supplier() }
            .onFailure { Log.d(TAG, "EXIF read failed — assuming 0°", it) }
            .getOrNull() ?: return 0f
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        // TRANSPOSE / TRANSVERSE / FLIP_* 같은 미러링 케이스도 회전값만이라도 맞춰 준다.
        // (완벽 복원은 mirror flip 이 필요하지만 글자 방향 맞추는 것이 우선.)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSPOSE -> 90f
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180f
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSVERSE -> 270f
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_UNDEFINED -> 0f
            else -> 0f
        }
        Log.i(TAG, "readExifRotationDegrees: raw=$orientation -> ${degrees}°")
        return degrees
    }

    /**
     * 이미지 방향에 맞춰 가이드 영역만큼 중앙 크롭.
     * 한 변이 원본을 초과하면 원본 범위 안으로 clamp. 크롭 이후 원본은 recycle.
     */
    private fun Bitmap.cropToCodeArea(): Bitmap {
        val aspect = if (width > height) LANDSCAPE_CROP_HEIGHT_RATIO else PORTRAIT_CROP_HEIGHT_RATIO
        val cropWidth = (width * GUIDE_WIDTH_RATIO).toInt()
            .coerceAtLeast(1)
            .coerceAtMost(width)
        val cropHeight = (cropWidth * aspect).toInt()
            .coerceAtLeast(1)
            .coerceAtMost(height)
        val left = ((width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((height - cropHeight) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(this, left, top, cropWidth, cropHeight)
        if (cropped !== this) recycle()
        return cropped
    }
}
