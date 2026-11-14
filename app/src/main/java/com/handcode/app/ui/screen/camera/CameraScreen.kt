package com.handcode.app.ui.screen.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.handcode.app.R
import com.handcode.app.ui.theme.LocalAppSettings
import java.io.File
import java.util.concurrent.Executor

// 8주차 5차 수정 (좌표계 통일): 7주차의 긴 폰 가이드 분기(0.78)를 제거하고 `ImagePreparer`
// 측 `GUIDE_WIDTH_RATIO` 와 동일한 0.82 로 고정한다. 분기가 남아 있으면 화면 가이드(0.78)와
// 실제 이미지 크롭(0.82) 의 좌표계가 달라, 긴 폰에서 가이드 밖 영역까지 크롭에 포함되는
// OCR 회귀가 생긴다 (5인 독립 리뷰의 High 지적). 긴 폰에서 가이드가 약간 크게 보이는 시각적
// 열화는 OCR 정확도 대비 trivial 하다고 판정.
private const val GUIDE_WIDTH_RATIO = 0.82f
private const val GUIDE_ASPECT_RATIO = 4f / 3f
private const val CAPTURE_FILE_PREFIX = "capture_"
private const val CAPTURE_FILE_SUFFIX = ".jpg"
private const val SHUTTER_SIZE_DP = 72
private const val GALLERY_BUTTON_SIZE_DP = 56

// 뒤로가기 아이콘 배경의 반투명도. 밝은 프리뷰 위에서 아이콘이 가려지지 않도록 한다.
private const val BACK_BUTTON_BG_ALPHA = 0.4f

private const val TAG = "CameraScreen"

/**
 * 3주차 카메라 화면 + 4주차 갤러리 버튼.
 * 권한 체크 → CameraX 프리뷰/캡처 바인딩 → 촬영/갤러리 선택 → ViewModel로 전달.
 */
@Composable
fun CameraScreen(
    onBack: () -> Unit,
    onNavigateToEditor: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // Android 13+ Photo Picker. READ_EXTERNAL_STORAGE 권한 불필요.
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.onImagePicked(it) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 인식 성공 시 편집 화면으로 이동
    LaunchedEffect(uiState) {
        if (uiState is CameraUiState.Recognized) {
            viewModel.consumeResult()
            onNavigateToEditor()
        }
    }

    if (!hasCameraPermission) {
        PermissionDeniedContent(
            onRetry = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onBack = onBack
        )
        return
    }

    CameraContent(
        uiState = uiState,
        onCaptured = viewModel::onImageCaptured,
        onCaptureFailed = viewModel::onCaptureFailed,
        onRetake = viewModel::reset,
        onGalleryClick = {
            galleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        onBack = onBack
    )
}

@Composable
private fun CameraContent(
    uiState: CameraUiState,
    onCaptured: (String) -> Unit,
    onCaptureFailed: (String) -> Unit,
    onRetake: () -> Unit,
    onGalleryClick: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val imageCapture = remember { buildImageCapture() }
    val previewView = remember { buildPreviewView(context) }
    // 가속도계 없는 기기 — OrientationEventListener 콜백이 오지 않아 EXIF 회전이 초기값에
    // 고정된다. 사용자에게 "세로로 촬영해 주세요" 배너로 명시 (실기기 검증 단계에서 원인
    // 추적을 단순화).
    var hasOrientationSensor by remember { mutableStateOf(true) }

    // DisposableEffect로 한 번만 UseCase 바인딩, 이탈 시 해제.
    // 8주차 3차 수정: Activity 가 portrait 고정이라 display.rotation 이 항상 0 이므로,
    // 기기 물리 방향 변화를 OrientationEventListener 로 감지해 ImageCapture.targetRotation 을
    // 직접 업데이트한다. 이렇게 해야 태블릿을 가로로 눕혀 촬영한 사진의 EXIF 가 올바르게
    // 기록되고, ImagePreparer.applyExifRotation 단계에서 글자 방향이 정방향으로 회전된다.
    //
    // 방어 포인트:
    //  - 센서 없는 기기: `canDetectOrientation()` 로 확인해 로그 남김. 동작은 초기 rotation 에 의존.
    //  - 리스너 첫 콜백 전 셔터: `buildImageCapture` 는 기본 0 도로 만들어져 있어 초기값을
    //    display.rotation 으로 미리 설정해 둔다 (portrait 고정 환경에서는 0 이지만, 명시적 의도).
    //  - DisposableEffect key 에 imageCapture / previewView 포함 — 향후 누군가 remember key 를
    //    바꿔도 stale closure 를 만들지 않도록 방어.
    // CameraX 공식 가이드: https://developer.android.com/reference/androidx/camera/core/ImageCapture#setTargetRotation(int)
    DisposableEffect(lifecycleOwner, imageCapture, previewView) {
        imageCapture.targetRotation = context.currentDisplayRotation()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                bindCameraUseCases(provider, lifecycleOwner, previewView, imageCapture)
            }.onFailure { Log.e(TAG, "CameraX bind failed", it) }
        }, executor)

        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                // Java static 상수를 클래스명으로 명시 — 상속 컨텍스트 안에서도 Kotlin
                // 컴파일러 버전에 따라 이름 해석 규칙이 흔들릴 여지가 있어 안전하게 한정한다.
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        val detectable = orientationListener.canDetectOrientation()
        if (!detectable) {
            // 가속도계/자이로 없는 기기: 방향 콜백이 오지 않아 초기 rotation 에 고정된다.
            // 로그뿐 아니라 UI 배너로도 노출해 "가로 촬영이 왜 안 되나" 원인을 사용자가 바로 인지.
            Log.w(TAG, "Device has no orientation sensor — EXIF rotation stays at initial value.")
        }
        hasOrientationSensor = detectable
        orientationListener.enable()

        onDispose {
            orientationListener.disable()
            runCatching { providerFuture.get().unbindAll() }
                .onFailure { Log.e(TAG, "CameraX unbind failed", it) }
        }
    }

    // 사용자 설정에 따라 그리드 가이드 on/off.
    val gridVisible = LocalAppSettings.current.cameraGridVisible

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )
        if (gridVisible) {
            GuideOverlay()
        }
        BottomControls(
            uiState = uiState,
            onShutter = {
                captureImage(context, imageCapture, executor, onCaptured, onCaptureFailed)
            },
            onGalleryClick = onGalleryClick
        )
        // 뒤로가기 IconButton (좌상단).
        // 반투명 검정 원형 배경 + 흰 화살표로 밝은 프리뷰에서도 시각적으로 뚜렷하다.
        // 히트박스는 IconButton 크기 48dp 로 제한되어 셔터 영역 터치를 가로채지 않는다.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = BACK_BUTTON_BG_ALPHA))
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = Color.White
            )
        }
        if (uiState is CameraUiState.Error) {
            // OCR/촬영 실패 시 상단 고정 배너 + "다시 촬영" 버튼으로 복구 동선을 확실히 제공.
            CaptureErrorBanner(
                message = uiState.message,
                onRetake = onRetake
            )
        }
        if (!hasOrientationSensor) {
            NoSensorWarningBanner()
        }
    }
}

/**
 * 가속도계/자이로 없는 기기용 경고 배너. 방향 콜백이 오지 않아 EXIF 회전이 초기값에
 * 고정되므로, 세로 촬영을 권고하는 UI 안내를 제공한다.
 */
@Composable
private fun NoSensorWarningBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, start = 16.dp, end = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.camera_no_orientation_sensor_warning),
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

/** 카메라 영역 가이드 사각형. 단일 가로 비율(`GUIDE_WIDTH_RATIO`) 로 화면 가이드와 실제
 *  크롭 영역(`ImagePreparer`) 을 동일 좌표계에서 유지한다. */
@Composable
private fun GuideOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val guideWidth = size.width * GUIDE_WIDTH_RATIO
        val guideHeight = guideWidth * GUIDE_ASPECT_RATIO
        val left = (size.width - guideWidth) / 2f
        val top = (size.height - guideHeight) / 2f
        drawRect(
            color = Color.White.copy(alpha = 0.7f),
            topLeft = Offset(left, top),
            size = Size(guideWidth, guideHeight),
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

@Composable
private fun BottomControls(
    uiState: CameraUiState,
    onShutter: () -> Unit,
    onGalleryClick: () -> Unit
) {
    // Box 전체가 fillMaxSize 로 깔려 있으면 프리뷰 터치를 먹어버리는 문제가 있었다.
    // 바깥 Box 는 fillMaxSize 로 align 기준만 제공하고, 실제 컨트롤 Row 는
    // 하단에 wrapContentHeight 로 좁게 배치해 프리뷰 영역 터치를 가로막지 않는다.
    Box(modifier = Modifier.fillMaxSize()) {
        if (uiState is CameraUiState.Processing) {
            // 2026-04-19 MediaPipe GenAI 전환:
            //   Gemma-3n E2B .litertlm 첫 세션 생성 + 이미지 추론이 수 초 ~ 수십 초 걸릴 수 있다.
            //   사용자가 "멈춘 것" 으로 오해하지 않도록 명시적인 안내 텍스트 노출.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "AI 가 글씨를 분석 중이에요…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "최대 45초 정도 걸릴 수 있어요",
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 자리 맞추기용 placeholder 로 갤러리 버튼 크기만큼 공간을 비움.
                // SpaceEvenly 가 셔터를 정확히 가운데 배치하도록 보장.
                PlaceholderSlot()
                ShutterButton(onClick = onShutter)
                GalleryButton(onClick = onGalleryClick)
            }
        }
    }
}

@Composable
private fun PlaceholderSlot() {
    Box(modifier = Modifier.size(GALLERY_BUTTON_SIZE_DP.dp))
}

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(SHUTTER_SIZE_DP.dp)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick)
    )
}

/** 우측의 갤러리 버튼. 시스템 Photo Picker를 띄운다. */
@Composable
private fun GalleryButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(GALLERY_BUTTON_SIZE_DP.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.85f))
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = stringResource(R.string.camera_gallery_description),
            tint = Color.Black
        )
    }
}

/**
 * 촬영/인식 실패 시 상단에 고정되는 배너.
 * 단순 텍스트만 띄우던 기존 ErrorBanner 와 달리 "다시 촬영" 액션을 함께 제공한다.
 */
@Composable
private fun CaptureErrorBanner(
    message: String,
    onRetake: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, start = 16.dp, end = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Button(
            onClick = onRetake,
            modifier = Modifier.padding(end = 8.dp)
        ) {
            Text(stringResource(R.string.camera_retake))
        }
    }
}

@Composable
private fun PermissionDeniedContent(onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.camera_permission_rationale))
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.camera_permission_retry))
        }
        Button(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.common_back))
        }
    }
}

/* ---------- CameraX 헬퍼 (순수 함수) ---------- */

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private fun buildPreviewView(context: Context): PreviewView =
    PreviewView(context).apply {
        // 일부 기기에서 검은 프리뷰 방지
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

private fun buildImageCapture(): ImageCapture =
    ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .build()

private fun bindCameraUseCases(
    provider: ProcessCameraProvider,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    previewView: PreviewView,
    imageCapture: ImageCapture
) {
    val preview = Preview.Builder().build().apply {
        setSurfaceProvider(previewView.surfaceProvider)
    }
    runCatching {
        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture
        )
    }.onFailure { Log.e(TAG, "bindToLifecycle failed", it) }
}

/**
 * 현재 디스플레이 rotation 을 반환. API 30+ 는 Context.display, 그 이하는 WindowManager fallback.
 * portrait 고정 환경에서는 항상 `Surface.ROTATION_0` 을 반환하지만, 명시적으로 초기화 값을
 * 넣어 주어 OrientationEventListener 첫 콜백 전 셔터 케이스에서도 의도된 값으로 촬영되게 한다.
 */
private fun Context.currentDisplayRotation(): Int {
    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
    } else {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    }
    return display?.rotation ?: Surface.ROTATION_0
}

private fun captureImage(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    onCaptured: (String) -> Unit,
    onCaptureFailed: (String) -> Unit
) {
    val outputFile = File.createTempFile(
        CAPTURE_FILE_PREFIX,
        CAPTURE_FILE_SUFFIX,
        context.cacheDir
    )
    val output = ImageCapture.OutputFileOptions.Builder(outputFile).build()

    imageCapture.takePicture(
        output,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                onCaptured(outputFile.absolutePath)
            }

            override fun onError(exception: ImageCaptureException) {
                // 조용한 실패 방지 — ViewModel 에 원인 메시지를 전달해 사용자에게 노출.
                onCaptureFailed(exception.message ?: exception.javaClass.simpleName)
            }
        }
    )
}
