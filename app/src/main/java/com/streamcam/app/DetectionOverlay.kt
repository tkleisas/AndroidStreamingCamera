package com.streamcam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DetectionOverlay(
    detections: List<Detection>,
    trackedIndex: Int = -1,
    onDetectionTapped: ((Detection) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val parentWidth = maxWidth
        val parentHeight = maxHeight

        for ((index, detection) in detections.withIndex()) {
            val box = detection.boundingBox
            val isTracked = index == trackedIndex
            val color = if (isTracked) Color.White else when (detection.classId) {
                0 -> Color(0xFF4FC3F7)
                32 -> Color(0xFFFFB74D)
                else -> Color(0xFF81C784)
            }

            Box(
                modifier = Modifier
                    .offset(x = parentWidth * box.left, y = parentHeight * box.top)
                    .size(
                        width = parentWidth * box.width(),
                        height = parentHeight * box.height(),
                    )
                    .border(if (isTracked) 3.dp else 2.dp, color, RoundedCornerShape(4.dp))
                    .then(
                        if (onDetectionTapped != null) {
                            Modifier.clickable { onDetectionTapped(detection) }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(
                    text = "${detection.label} ${(detection.confidence * 100).toInt()}%" +
                        if (isTracked) " TRACK" else "",
                    color = Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}
