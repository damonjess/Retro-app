package com.retrorts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Direct mouse controller for DOSBox-Pure.
 *
 * Dune II is mouse-driven. The left stick therefore sends real relative mouse
 * deltas to libretro port 0 instead of relying on a gamepad-to-mouse mapper.
 * The FIRE button holds the real left mouse button and also emits Enter as a
 * fallback for the DOSBox-Pure start menu.
 */
@Composable
fun DosboxVirtualController(
    modifier: Modifier = Modifier,
    mouseSpeed: Float = 1.5f,
    deadZone: Float = 0.16f
) {
    var stickDirection by remember { mutableStateOf(Offset.Zero) }
    var mouseButtons by remember { mutableStateOf(0) }

    // We use accumulators to allow for sub-pixel movement speeds.
    // This makes fine-grained selection much easier by letting the cursor
    // move slower than 1 pixel per frame at low stick deflection.
    LaunchedEffect(stickDirection, mouseButtons) {
        if (abs(stickDirection.x) < deadZone && abs(stickDirection.y) < deadZone) {
            return@LaunchedEffect
        }
        
        var accX = 0f
        var accY = 0f
        
        while (abs(stickDirection.x) >= deadZone || abs(stickDirection.y) >= deadZone) {
            // Apply a cubic curve (x^3) to the input.
            // This makes the "slow zone" around the center much larger,
            // so you have to really push the stick to get to higher speeds.
            val curveX = stickDirection.x * stickDirection.x * stickDirection.x
            val curveY = stickDirection.y * stickDirection.y * stickDirection.y
            
            accX += curveX * mouseSpeed
            accY += curveY * mouseSpeed
            
            val dx = accX.toInt()
            val dy = accY.toInt()
            
            if (dx != 0 || dy != 0) {
                DosboxBridge.updateMouse(mouseButtons, dx, dy)
                accX -= dx
                accY -= dy
            }
            delay(16L)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Box(Modifier.padding(bottom = 16.dp)) {
            DosboxMouseStick(
                modifier = Modifier.size(140.dp),
                deadZone = deadZone,
                onDirectionChanged = { stickDirection = it },
                onReleased = {
                    stickDirection = Offset.Zero
                    DosboxBridge.updateMouse(mouseButtons, 0, 0)
                }
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            GamepadButton(
                label = "FIRE",
                color = Color(0xFF8B2020),
                modifier = Modifier.size(88.dp),
                onPressed = { pressed ->
                    mouseButtons = if (pressed) DosboxMouse.LEFT_BUTTON else 0
                    DosboxBridge.updateMouse(mouseButtons, 0, 0)
                    // This also selects items in the DOSBox-Pure launcher menu.
                    if (pressed) NativeEmulatorBridge.sendKeyCode(DosKeys.RETURN)
                }
            )
            GamepadButton(
                label = "ESC",
                color = Color(0xFF3A3A6A),
                modifier = Modifier.size(68.dp),
                onPressed = { pressed ->
                    if (pressed) NativeEmulatorBridge.sendKeyCode(DosKeys.ESCAPE)
                }
            )
        }
    }
}

private object DosboxMouse {
    const val LEFT_BUTTON = 1
}

/** libretro RETROK keysyms used for DOSBox-Pure menu fallback. */
private object DosKeys {
    const val RETURN = 13
    const val ESCAPE = 27
}

@Composable
private fun DosboxMouseStick(
    modifier: Modifier = Modifier,
    deadZone: Float,
    onDirectionChanged: (Offset) -> Unit,
    onReleased: () -> Unit
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

                            val direction = Offset(
                                x = stickOffset.x / maxRadius,
                                y = stickOffset.y / maxRadius
                            )
                            onDirectionChanged(
                                if (abs(direction.x) < deadZone && abs(direction.y) < deadZone) {
                                    Offset.Zero
                                } else {
                                    direction
                                }
                            )
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        stickOffset = Offset.Zero
                        onReleased()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .offset { IntOffset(stickOffset.x.roundToInt(), stickOffset.y.roundToInt()) }
                .size(50.dp)
                .background(Color(0xAAFFFFFF), CircleShape)
                .border(2.dp, Color.White, CircleShape)
        )
    }
}
