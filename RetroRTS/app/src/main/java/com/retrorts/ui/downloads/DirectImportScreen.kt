package com.retrorts.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.retrorts.download.DirectUrlImporter
import kotlinx.coroutines.delay

/**
 * Dedicated, user-authorized direct-link importer. It downloads a game file,
 * reports DownloadManager progress, then installs it into RetroRTS/Games.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectImportScreen() {
    val context = LocalContext.current
    var urlText by rememberSaveable { mutableStateOf("") }
    var selectedTarget by remember { mutableStateOf(DirectUrlImporter.TargetSystem.AUTO) }
    var targetMenuOpen by remember { mutableStateOf(false) }
    var activeDownloadId by remember { mutableStateOf(-1L) }
    var activeStatus by remember {
        mutableStateOf(DirectUrlImporter.DownloadStatus(-1L, DirectUrlImporter.Stage.IDLE, ""))
    }

    val normalizedUrl = remember(urlText) {
        urlText.trim().replace(0x00A0.toChar().toString(), "")
    }
    val preview = remember(normalizedUrl, selectedTarget) {
        DirectUrlImporter.preview(normalizedUrl, selectedTarget)
    }

    LaunchedEffect(activeDownloadId) {
        if (activeDownloadId <= 0L) return@LaunchedEffect
        while (true) {
            activeStatus = DirectUrlImporter.queryStatus(context, activeDownloadId)
            if (activeStatus.stage in terminalStages) break
            delay(750L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Direct Link Import") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Paste a direct file link for a game you own or are authorized to download. " +
                    "RetroRTS will download it and install it into the selected game folder.",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                label = { Text("Direct download URL") },
                placeholder = { Text("https://example.com/game.iso") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = urlText.isNotBlank() && !preview.valid
            )

            Box {
                OutlinedButton(onClick = { targetMenuOpen = true }) {
                    Text("Install target: ${selectedTarget.label}")
                }
                DropdownMenu(
                    expanded = targetMenuOpen,
                    onDismissRequest = { targetMenuOpen = false }
                ) {
                    DirectUrlImporter.TargetSystem.values().forEach { target ->
                        DropdownMenuItem(
                            text = { Text(target.label) },
                            onClick = {
                                selectedTarget = target
                                targetMenuOpen = false
                            }
                        )
                    }
                }
            }

            ImportPreviewCard(preview)

            if (activeDownloadId > 0L && activeStatus.stage !in terminalStages) {
                DownloadStatusCard(
                    status = activeStatus,
                    onCancel = {
                        DirectUrlImporter.cancelDownload(context, activeDownloadId)
                        activeStatus = DirectUrlImporter.queryStatus(context, activeDownloadId)
                    }
                )
            } else {
                Button(
                    onClick = {
                        // Submit the same normalized state used by the preview. This
                        // prevents pasted whitespace/non-breaking spaces from making
                        // a visible link look empty to the download action.
                        val submittedUrl = normalizedUrl
                        val submittedPreview = DirectUrlImporter.preview(submittedUrl, selectedTarget)
                        if (!submittedPreview.valid) return@Button
                        val id = DirectUrlImporter.enqueueDownload(context, submittedUrl, selectedTarget)
                        if (id > 0L) {
                            activeDownloadId = id
                            activeStatus = DirectUrlImporter.queryStatus(context, id)
                            urlText = ""
                        }
                    },
                    enabled = normalizedUrl.isNotBlank() && preview.valid,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download & Auto-Install")
                }
            }

            if (activeDownloadId > 0L && activeStatus.stage in terminalStages) {
                ImportResultCard(activeStatus)
                OutlinedButton(
                    onClick = {
                        activeDownloadId = -1L
                        activeStatus = DirectUrlImporter.DownloadStatus(-1L, DirectUrlImporter.Stage.IDLE, "")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import Another Game")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
            Text("Supported file formats", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "PS1: .bin, .cue, .img, .iso, .pbp, .chd\n" +
                    "PS2: .iso, .chd, .cso, .bin\n" +
                    "Amiga: .adf, .hdf, .dms, .ipf\n" +
                    "DOSBox: .exe, .com, .bat, .conf, .zip\n" +
                    "Nintendo DSi: .nds, .dsi, .srl",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(4.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    text = "Only import files you legally own or are permitted to download. " +
                        "The app does not host, catalogue, or bypass access controls for game files.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun ImportPreviewCard(preview: DirectUrlImporter.ImportPreview) {
    val color = if (preview.valid) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(if (preview.valid) "INSTALL PREVIEW" else "LINK CHECK", style = MaterialTheme.typography.labelLarge)
            if (preview.fileName.isNotBlank()) Text(preview.fileName, style = MaterialTheme.typography.bodyMedium)
            if (preview.destination.isNotBlank()) {
                Text("Destination: ${preview.destination}", style = MaterialTheme.typography.bodySmall)
            }
            Text(preview.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DownloadStatusCard(
    status: DirectUrlImporter.DownloadStatus,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("IMPORTING ${status.fileName}", style = MaterialTheme.typography.labelLarge)
            val progress = status.progressFraction
            if (progress != null) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%  •  ${formatBytes(status.downloadedBytes)} of ${formatBytes(status.totalBytes)}")
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("${status.stage.name.lowercase().replaceFirstChar { it.uppercase() } }…")
            }
            OutlinedButton(onClick = onCancel) { Text("Cancel Download") }
        }
    }
}

@Composable
private fun ImportResultCard(status: DirectUrlImporter.DownloadStatus) {
    val installed = status.stage == DirectUrlImporter.Stage.INSTALLED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (installed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(if (installed) "IMPORT COMPLETE" else "IMPORT ${status.stage.name}", style = MaterialTheme.typography.labelLarge)
            Text(status.fileName, style = MaterialTheme.typography.bodyMedium)
            if (status.destination.isNotBlank()) Text(status.destination, style = MaterialTheme.typography.bodySmall)
            if (status.message.isNotBlank()) Text(status.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 0L -> "unknown size"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}

private val terminalStages = setOf(
    DirectUrlImporter.Stage.INSTALLED,
    DirectUrlImporter.Stage.FAILED,
    DirectUrlImporter.Stage.CANCELLED
)
