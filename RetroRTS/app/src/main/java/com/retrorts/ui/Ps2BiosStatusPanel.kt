package com.retrorts.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.retrorts.ui.theme.RetroFontFamily
import com.retrorts.ui.theme.RetroNeonGreen

/**
 * Insert this panel in the System BIOS tab below the PlayStation 1 section.
 * Play! uses high-level PS2 BIOS emulation, so an external PS2 BIOS is neither
 * required nor requested by RetroRTS.
 */
@Composable
fun Ps2BiosStatusPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coreInstalled = remember(context) { hasPlayCore(context) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "[ PLAYSTATION 2 / PLAY! ]",
            color = Color.White,
            fontFamily = RetroFontFamily,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(10.dp))
        RetroCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚡",
                        color = if (coreInstalled) RetroNeonGreen else Color(0xFFFF4040),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            text = "PLAY! LIBRETRO CORE",
                            color = if (coreInstalled) RetroNeonGreen else Color(0xFFFF7070),
                            fontFamily = RetroFontFamily,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (coreInstalled) "ARM64 CORE INSTALLED" else "CORE MISSING",
                            color = Color.LightGray,
                            fontFamily = RetroFontFamily,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    text = "PS2 BIOS: BUILT-IN HIGH-LEVEL EMULATION",
                    color = RetroNeonGreen,
                    fontFamily = RetroFontFamily,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "NO EXTERNAL PS2 BIOS FILE IS REQUIRED",
                    color = Color.LightGray,
                    fontFamily = RetroFontFamily,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun hasPlayCore(context: Context): Boolean {
    val nativeDirectory = java.io.File(context.applicationInfo.nativeLibraryDir)
    return java.io.File(nativeDirectory, "libplay_libretro.so").isFile ||
        java.io.File(nativeDirectory, "libplay_libretro_android.so").isFile
}
