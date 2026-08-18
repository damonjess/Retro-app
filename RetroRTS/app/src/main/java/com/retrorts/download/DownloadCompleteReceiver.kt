package com.retrorts.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Installs completed direct-link imports without blocking Android's broadcast thread. */
class DownloadCompleteReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RetroRTS_Download"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId <= 0L) return

        // PS1/PS2 images can be large. goAsync keeps the receiver alive while
        // the file copy runs on a worker thread instead of blocking the UI.
        val pendingResult = goAsync()
        Thread {
            try {
                val handled = DirectUrlImporter.handleCompletedDownload(context.applicationContext, downloadId)
                if (handled) Log.i(TAG, "Direct import handled download $downloadId")
            } catch (exception: Exception) {
                Log.e(TAG, "Direct import completion failed", exception)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}
