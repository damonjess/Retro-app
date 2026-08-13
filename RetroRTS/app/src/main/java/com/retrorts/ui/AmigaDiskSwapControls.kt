package com.retrorts.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * In-game Amiga disk selector. Add this to the existing gameplay overlay after
 * [AmigaBridge.startAmiga] succeeds. It renders every discovered disk rather
 * than a static multi-disk limitation message.
 */
@Composable
fun AmigaDiskSwapControls(
    diskSet: AmigaUtils.DiskSet,
    modifier: Modifier = Modifier,
) {
    // If not a multi-disk set, we still want to show the overlay if the core says it has disks
    val coreDiskCount = AmigaBridge.diskCount()
    if (!diskSet.isMultiDisk && coreDiskCount <= 1) {
        // Maybe show a hidden debug trigger or just return
        // For now, let's log it
        LaunchedEffect(Unit) {
            android.util.Log.d("AmigaDiskSwap", "Hiding controls: diskSet.size=${diskSet.diskPaths.size}, coreCount=$coreDiskCount")
        }
        return
    }

    var activeDiskNumber by remember(diskSet.diskPaths) {
        mutableIntStateOf((AmigaBridge.activeDiskIndex() + 1).coerceIn(1, if (coreDiskCount > 0) coreDiskCount else diskSet.diskPaths.size))
    }
    var diskControlReady by remember { mutableStateOf(AmigaBridge.isDiskControlAvailable()) }
    var statusText by remember { mutableStateOf("Preparing disk controls…") }

    LaunchedEffect(diskSet.diskPaths) {
        repeat(20) {
            diskControlReady = AmigaBridge.isDiskControlAvailable()
            if (diskControlReady) {
                activeDiskNumber = (AmigaBridge.activeDiskIndex() + 1)
                    .coerceIn(1, diskSet.diskPaths.size)
                statusText = "Disk $activeDiskNumber inserted"
                return@LaunchedEffect
            }
            delay(250)
        }
        statusText = "Disk controls are unavailable in this core session."
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Amiga disks",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                diskSet.diskPaths.forEachIndexed { index, _ ->
                    val diskNumber = index + 1
                    val isActive = diskNumber == activeDiskNumber
                    val onClick = {
                        if (AmigaBridge.swapToDisk(diskNumber)) {
                            activeDiskNumber = diskNumber
                            statusText = "Disk $diskNumber inserted"
                        } else {
                            statusText = "Unable to insert Disk $diskNumber"
                        }
                    }
                    if (isActive) {
                        Button(
                            onClick = onClick,
                            enabled = diskControlReady,
                            colors = ButtonDefaults.buttonColors(),
                        ) {
                            Text("Disk $diskNumber")
                        }
                    } else {
                        OutlinedButton(
                            onClick = onClick,
                            enabled = diskControlReady,
                        ) {
                            Text("Disk $diskNumber")
                        }
                    }
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Added debug info
            Text(
                text = "Debug: detected=${diskSet.diskPaths.size} core=$coreDiskCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Recommended gameplay-overlay integration:
 *
 * ```kotlin
 * val diskSet = remember(game.filePath) { AmigaUtils.diskSetFor(game.filePath) }
 * if (diskSet.isMultiDisk) AmigaDiskSwapControls(diskSet)
 * ```
 */
