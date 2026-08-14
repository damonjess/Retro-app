package com.retrorts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrorts.ui.theme.RetroFontFamily
import com.retrorts.ui.theme.RetroNeonCyan

@Composable
fun ScanlineOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val scanlineHeight = 4.dp.toPx()
        val count = size.height / scanlineHeight
        for (i in 0..count.toInt()) {
            drawLine(
                color = Color.Black.copy(alpha = 0.05f),
                start = Offset(0f, i * scanlineHeight),
                end = Offset(size.width, i * scanlineHeight),
                strokeWidth = 1.dp.toPx(),

            )
        }
    }
}

@Composable
fun RetroCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .background(Color(0xFF1A1A1A))
            .border(
                width = 2.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF808080), Color(0xFF303030))
                ),
                shape = RoundedCornerShape(2.dp)
            )
            .padding(8.dp)
    ) {
        content()
    }
}

@Composable
fun RetroButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String,
    enabled: Boolean = true,
    color: Color = RetroNeonCyan
) {
    val backgroundColor = if (enabled) color else Color.Gray
    Box(
        modifier = modifier
            .clickable(enabled = enabled) { onClick() }
            .background(backgroundColor, RoundedCornerShape(2.dp))
            .border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.uppercase(),
            fontFamily = RetroFontFamily,
            color = Color.Black,
            fontSize = 12.sp,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun RetroTabItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clickable { onClick() }
            .background(
                if (isSelected) RetroNeonCyan else Color.Transparent
            )
            .border(
                1.dp,
                if (isSelected) Color.White else Color.Transparent,
                RoundedCornerShape(2.dp)
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.uppercase(),
            color = if (isSelected) Color.Black else Color.White,
            fontFamily = RetroFontFamily,
            fontSize = 10.sp
        )
    }
}
