package com.borasarang.droidrelay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v0.59 계측기 톤 진행 바.
 *
 * M3 기본 `LinearProgressIndicator` 는 트랙 끝에 stop indicator(점)를 그린다.
 * 4~8dp 얇은 바에서는 점이 떠 있는 것처럼 보여서, 트랙+채움만 있는 단순 바를 쓴다.
 * [progress] 는 0f..1f (범위를 벗어나면 잘라낸다).
 */
@Composable
fun DrProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(trackColor),
    ) {
        if (clamped > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}
