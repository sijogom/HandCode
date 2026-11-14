package com.handcode.app.ui.screen.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 편집 중 원본 사진 대조용 풀스크린 오버레이.
 *
 * 구버전 Flutter 앱의 `source_image_view.dart` 탭-팝업/탭-닫기 패턴을 Compose 로 이식.
 * OCR 결과를 글자로 편집하다가, 원본 글씨체가 어떻게 생겼는지 확인해야 할 때 사용한다.
 *
 * 동작:
 *  - [imagePath] 가 유효한 파일이면 비트맵 디코딩 후 반투명 검정 배경 위에 중앙 Fit 으로 띄움
 *  - 파일이 없으면 "찾을 수 없음" 메시지
 *  - 화면 어디를 탭해도 [onDismiss] 호출 → 오버레이 닫힘
 *  - [visible] false 또는 [imagePath] null/blank 면 아무것도 렌더하지 않음
 */
@Composable
fun OriginalImageViewer(
    imagePath: String?,
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible || imagePath.isNullOrBlank()) return
    val bitmap = remember(imagePath) {
        runCatching {
            val file = File(imagePath)
            if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "원본 이미지",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        } else {
            Text(
                text = "원본 이미지를 찾을 수 없어요",
                color = Color.White
            )
        }
    }
}
