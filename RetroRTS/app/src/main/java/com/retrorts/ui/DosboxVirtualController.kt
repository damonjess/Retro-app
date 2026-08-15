package com.retrorts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Virtual gamepad for DOSBox games.
 *
 * ROOT CAUSE (confirmed): this menu isn't listening for a mouse at all.
 * The DOSBox core maps RETRO_DEVICE_JOYPAD d-pad presses to emulated
 * keyboard arrow keys internally - that's why the old stick could nudge
 * the highlighted menu item (that was arrow-key navigation, not a cursor)
 * while a real INT33 mouse click did nothing, because the game/menu here
 * is keyboard-driven, not mouse-driven.
 *
 * Fix: stop emulating a mouse. Send real keyboard key events instead via
 * NativeEmulatorBridge.sendKeyCode(), which pushes an actual keydown+keyup
 * straight into the emulated PC (see LibretroHost::sendKeyCode /
 * keyboard_cb_ in libretro_bridge.cpp). This works regardless of whether
 * a given DOS game wants keyboard, joystick, or mouse input, so it's the
 * safest general-purpose control scheme for DOSBox menus and games.
 */
@Composable
fun DosboxVirtualController(
    modifier: Modifier = Modifier,
    repeatDelayMillis: Long = 300L, // delay before auto-repeat kicks in
    repeatRateMillis: Long = 150L,  // time between repeated arrow taps while held
    deadZone: Float = 0.35f         // stick must move this far off-center to register
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        // Left: directional stick -> arrow key taps (with auto-repeat while held)
        Box(Modifier.padding(bottom = 16.dp)) {
            DirectionalStick(
                modifier = Modifier.size(140.dp),
                repeatDelayMillis = repeatDelayMillis,
                repeatRateMillis = repeatRateMillis,
                deadZone = deadZone
            )
        }

        // Right: Fire (Enter) and Back (Escape)
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            GamepadButton(
                label = "FIRE",
                color = Color(0xFF8B2020),
                modifier = Modifier.size(88.dp),
                onPressed = { pressed ->
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

/** libretro RETROK_* keysym values used here (see libretro.h). */
private object DosKeys {
    const val RETURN = 13
    const val ESCAPE = 27
    const val UP = 273
    const val DOWN = 274
    const val RIGHT = 275
    const val LEFT = 276
}

@Composable
private fun DirectionalStick(
    modifier: Modifier = Modifier,
    repeatDelayMillis: Long,
    repeatRateMillis: Long,
    deadZone: Float
) {
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    var activeKey by remember { mutableStateOf<Int?>(null) }
    val maxRadius = 100f

    // Fires the current direction immediately, then auto-repeats it as long
    // as the stick stays deflected past the dead zone in the same direction.
    LaunchedEffect(activeKey) {
        val key = activeKey ?: return@LaunchedEffect
        NativeEmulatorBridge.sendKeyCode(key)
        delay(repeatDelayMillis)
        while (activeKey == key) {
            NativeEmulatorBridge.sendKeyCode(key)
            delay(repeatRateMillis)
        }
    }

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

                            val nx = stickOffset.x / maxRadius
                            val ny = stickOffset.y / maxRadius
                            activeKey = when {
                                abs(nx) < deadZone && abs(ny) < deadZone -> null
                                abs(nx) >= abs(ny) && nx > 0 -> DosKeys.RIGHT
                                abs(nx) >= abs(ny) && nx < 0 -> DosKeys.LEFT
                                ny > 0 -> DosKeys.DOWN
                                else -> DosKeys.UP
                            }

                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        stickOffset = Offset.Zero
                        activeKey = null
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
