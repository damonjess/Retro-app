package com.retrorts.ui

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.retrorts.ui.theme.RetroNeonCyan
import com.retrorts.ui.theme.RetroNeonGreen
import java.io.File

/**
 * Add [Ps2SetupPanel] to the existing Setup tab. It reports whether the Play!
 * libretro core was packaged with the app and explains the PS2 game location.
 */
@Composable
fun Ps2SetupPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status = remember(context) { probePlayCore(context) }

    RetroCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "PLAYSTATION 2 / PLAY!",
                color = RetroNeonCyan,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))

            StatusRow(
                label = "PLAY! CORE",
                value = if (status.coreInstalled) "INSTALLED" else "CORE REQUIRED",
                color = if (status.coreInstalled) RetroNeonGreen else Color(0xFFFF4D4D)
            )
            StatusRow(
                label = "PS2 BIOS",
                value = "NOT REQUIRED (HLE)",
                color = RetroNeonGreen
            )
            StatusRow(
                label = "GAME FOLDER",
                value = status.gameDirectory.absolutePath,
                color = Color.LightGray
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            Text(
                text = if (status.coreInstalled) {
                    "Place legally dumped .ISO, .CHD, .CSO, or .BIN images in the PS2 folder. " +
                        "Library scan will route them to Play!."
                } else {
                    "Build the official Play! libretro core and package it as libplay_libretro.so. " +
                        "See PS2_PLAY_CORE_SETUP.md in the project."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Text(value, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private data class PlayCoreStatus(
    val coreInstalled: Boolean,
    val gameDirectory: File
)

private fun probePlayCore(context: Context): PlayCoreStatus {
    val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
    val coreInstalled = File(nativeDirectory, "libplay_libretro.so").isFile ||
        File(nativeDirectory, "libplay_libretro_android.so").isFile
    val gameDirectory = File(Environment.getExternalStorageDirectory(), "RetroRTS/Games/PS2")
    return PlayCoreStatus(coreInstalled = coreInstalled, gameDirectory = gameDirectory)
}
