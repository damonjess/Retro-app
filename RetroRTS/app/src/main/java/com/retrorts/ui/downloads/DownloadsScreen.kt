package com.retrorts.ui.downloads

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.retrorts.download.DownloadRepository
import com.retrorts.download.DownloadableSuite
import com.retrorts.ui.RetroButton
import com.retrorts.ui.RetroCard
import com.retrorts.ui.theme.RetroFontFamily
import com.retrorts.ui.theme.RetroNeonMagenta
import com.retrorts.ui.theme.RetroNeonGreen
import com.retrorts.ui.theme.RetroPanel

@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val suites = remember { DownloadRepository.getAvailableSuites() }
    val activeDownloads = remember { mutableStateMapOf<String, Long>() }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(RetroPanel)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                "SOFTWARE ACQUISITION",
                color = RetroNeonGreen,
                fontFamily = RetroFontFamily,
                fontSize = 18.sp
            )
        }
        
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(suites, key = { it.id }) { suite ->
                RetroSuiteCard(
                    suite = suite,
                    isDownloading = activeDownloads.containsKey(suite.id),
                    onDownload = {
                        val id = DownloadRepository.startDownload(context, suite)
                        if (id != -1L) {
                            activeDownloads[suite.id] = id
                            Toast.makeText(context, "Downloading ${suite.name}…", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Unable to start download.", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RetroSuiteCard(
    suite: DownloadableSuite,
    isDownloading: Boolean,
    onDownload: () -> Unit
) {
    RetroCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = suite.name.uppercase(),
                color = Color.White,
                fontFamily = RetroFontFamily,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = suite.description.uppercase(),
                color = Color.Gray,
                fontFamily = RetroFontFamily,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "TARGET: ${suite.platform.uppercase()}",
                color = RetroNeonGreen,
                fontFamily = RetroFontFamily,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            RetroButton(
                onClick = onDownload,
                enabled = !isDownloading,
                modifier = Modifier.align(Alignment.End),
                text = if (isDownloading) "FETCHING..." else "DOWNLOAD",
                color = RetroNeonGreen
            )
        }
    }
}
