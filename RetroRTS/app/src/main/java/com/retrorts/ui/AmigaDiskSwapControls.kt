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
 * Selects the currently inserted image in an Amiga multi-disk playlist.
 * The native bridge performs Libretro's required eject -> select -> insert
 * sequence, so taps on Disk 2 or Disk 3 are safe while a game is running.
 */
@Composable
fun AmigaDiskSwapControls(
    diskSet: AmigaUtils.DiskSet,
    modifier: Modifier = Modifier,
) {
    // A single image never needs a selector. A detected numbered set must stay
    // visible even while the core is still registering its disk interface.
    if (!diskSet.isMultiDisk) return

    var activeDiskNumber by remember(diskSet.diskPaths) { mutableIntStateOf(1) }
    var diskControlReady by remember(diskSet.diskPaths) { mutableStateOf(false) }
    var statusText by remember(diskSet.diskPaths) { mutableStateOf("Preparing disk controls…") }

    LaunchedEffect(diskSet.diskPaths) {
        repeat(32) {
            if (AmigaBridge.isDiskControlAvailable()) {
                val coreDiskCount = AmigaBridge.diskCount()
                activeDiskNumber = (AmigaBridge.activeDiskIndex() + 1)
                    .coerceIn(1, minOf(diskSet.diskPaths.size, coreDiskCount.coerceAtLeast(1)))
                diskControlReady = true
                statusText = "Disk $activeDiskNumber inserted"
                return@LaunchedEffect
            }
            delay(250)
        }
        statusText = "Disk control is still loading. Close and reopen this panel in a moment."
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "INSERT AMIGA DISK",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                diskSet.diskPaths.forEachIndexed { index, _ ->
                    val diskNumber = index + 1
                    val isActive = diskNumber == activeDiskNumber
                    val insertDisk = {
                        if (AmigaBridge.swapToDisk(diskNumber)) {
                            activeDiskNumber = diskNumber
                            statusText = "Disk $diskNumber inserted"
                        } else {
                            statusText = "Unable to insert Disk $diskNumber. Wait a moment and try again."
                        }
                    }

                    if (isActive) {
                        Button(
                            onClick = insertDisk,
                            enabled = diskControlReady,
                            colors = ButtonDefaults.buttonColors(),
                        ) {
                            Text("Disk $diskNumber")
                        }
                    } else {
                        OutlinedButton(
                            onClick = insertDisk,
                            enabled = diskControlReady,
                        ) {
                            Text("Insert Disk $diskNumber")
                        }
                    }
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
