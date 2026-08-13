package com.retrorts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.retrorts.ui.NativeEmulatorBridge

@Composable
fun AmigaVirtualController(
    modifier: Modifier = Modifier,
    trackpadSensitivity: Float = 1.5f,
    stickSensitivity: Float = 1.0f,
    onMouseClick: (Int, Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        // Left Side: Analog Stick for Cursor
        Box(Modifier.padding(bottom = 16.dp)) {
            AnalogStick(
                modifier = Modifier.size(140.dp),
                onMove = { x, y ->
                    // For Amiga, we send analog values which the bridge converts to mouse movement
                    val valX = (x * 32767f * stickSensitivity).toInt().coerceIn(-32768, 32767)
                    val valY = (y * 32767f * stickSensitivity).toInt().coerceIn(-32768, 32767)
                    NativeEmulatorBridge.updateAnalog(0, 0, 0, valX)
                    NativeEmulatorBridge.updateAnalog(0, 0, 1, valY)
                }
            )
        }

        // Center: Large Trackpad
        Box(
            modifier = Modifier
                .weight(1f)
                .height(180.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x22FFFFFF))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val dx = (dragAmount.x * trackpadSensitivity).toInt()
                            val dy = (dragAmount.y * trackpadSensitivity).toInt()
                            if (dx != 0 || dy != 0) {
                                NativeEmulatorBridge.updateMouse(0, dx, dy)
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            onMouseClick(1, true)
                            waitForUpOrCancellation()
                            onMouseClick(1, false)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("TRACKPAD", color = Color(0x44FFFFFF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }

        // Right Side: LMB / RMB
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            GamepadButton(
                label = "LMB",
                color = Color(0xFF8B2020),
                modifier = Modifier.size(80.dp),
                onPressed = { onMouseClick(1, it) }
            )
            GamepadButton(
                label = "RMB",
                color = Color(0xFF3A3A6A),
                modifier = Modifier.size(80.dp),
                onPressed = { onMouseClick(2, it) }
            )
        }
    }
}

@Composable
fun AnalogStick(
    modifier: Modifier = Modifier,
    onMove: (x: Float, y: Float) -> Unit
) {
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 100f

    Box(
        modifier = modifier
            .background(Color(0x33FFFFFF), CircleShape)
            .border(2.dp, Color(0x66FFFFFF), CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        do {
                            val event = awaitPointerEvent()
                            val pos = event.changes.first().position
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val rawOffset = pos - center
                            val distance = rawOffset.getDistance()
                            stickOffset = if (distance > maxRadius) {
                                rawOffset * (maxRadius / distance)
                            } else {
                                rawOffset
                            }
                            onMove(stickOffset.x / maxRadius, stickOffset.y / maxRadius)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        stickOffset = Offset.Zero
                        onMove(0f, 0f)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .offset { IntOffset(stickOffset.x.toInt(), stickOffset.y.toInt()) }
                .size(50.dp)
                .background(Color(0xAAFFFFFF), CircleShape)
                .border(2.dp, Color.White, CircleShape)
        )
    }
}

@Composable
fun GamepadButton(
    modifier: Modifier = Modifier,
    label: String,
    color: Color = Color(0x99444444),
    onPressed: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    Surface(
        color = if (isPressed) color.copy(alpha = 1f) else color,
        shape = CircleShape,
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        isPressed = true
                        onPressed(true)
                        waitForUpOrCancellation()
                        isPressed = false
                        onPressed(false)
                    }
                }
            }
            .then(if (isPressed) Modifier.scale(0.9f) else Modifier)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}
