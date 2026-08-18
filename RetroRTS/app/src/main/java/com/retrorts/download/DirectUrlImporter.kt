package com.retrorts.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.getSystemService
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Imports a direct, user-authorized game-file URL. Download state is retained
 * in SharedPreferences so the Compose screen can recover after rotation or a
 * process restart and the completion receiver can install the file safely.
 */
object DirectUrlImporter {
    private const val TAG = "RetroRTS_Importer"
    private const val PREFS = "retrorts_direct_imports"
    private const val KEY_TARGET_PREFIX = "target_"
    private const val KEY_NAME_PREFIX = "name_"
    private const val KEY_STATUS_PREFIX = "status_"
    private const val KEY_DESTINATION_PREFIX = "destination_"

    enum class TargetSystem(val label: String, val folder: String) {
        AUTO("Auto detect", ""),
        PS1("PlayStation 1", "PS1"),
        PS2("PlayStation 2", "PS2"),
        AMIGA("Amiga", "Amiga"),
        DOSBOX("DOSBox", "DOSBox"),
        DSI("Nintendo DSi", "NintendoDSi"),
        IMPORTS("Review manually", "Imports")
    }

    enum class Stage { IDLE, QUEUED, DOWNLOADING, INSTALLING, INSTALLED, FAILED, CANCELLED, UNKNOWN }

    data class ImportPreview(
        val valid: Boolean,
        val fileName: String,
        val target: TargetSystem,
        val destination: String,
        val message: String
    )

    data class DownloadStatus(
        val id: Long,
        val stage: Stage,
        val fileName: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val destination: String = "",
        val message: String = ""
    ) {
        val progressFraction: Float?
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    }

    fun preview(urlText: String, selectedTarget: TargetSystem = TargetSystem.AUTO): ImportPreview {
        val url = urlText.trim()
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || (uri.scheme != "https" && uri.scheme != "http") || uri.host.isNullOrBlank()) {
            return ImportPreview(false, "", TargetSystem.IMPORTS, "", "Enter a valid direct http:// or https:// file link.")
        }

        val fileName = filenameFromUri(uri)
        if (fileName.isBlank()) {
            return ImportPreview(false, "", TargetSystem.IMPORTS, "", "The link does not include a usable file name. Use a direct file URL.")
        }

        val detected = detectTarget(fileName)
        val target = if (selectedTarget == TargetSystem.AUTO) detected else selectedTarget
        val destination = destinationFor(target).absolutePath
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val supported = extension in supportedExtensions
        val message = when {
            !supported -> "Unknown format .$extension. Choose Review manually or use a direct supported game-file link."
            extension == "zip" -> "Archive detected. It will be stored for review; extract archives before launching when required."
            extension == "cue" -> "Cue sheet detected. Import every .bin/.wav file referenced by the cue sheet as well."
            target == TargetSystem.IMPORTS -> "Format needs manual review before it can be launched."
            else -> "Ready to install into $destination"
        }
        return ImportPreview(supported || target == TargetSystem.IMPORTS, fileName, target, destination, message)
    }

    fun enqueueDownload(context: Context, urlText: String, target: TargetSystem = TargetSystem.AUTO): Long {
        val preview = preview(urlText, target)
        if (!preview.valid) return -1L

        val uri = Uri.parse(urlText.trim())
        val safeFileName = sanitizeFileName(preview.fileName)
        val request = DownloadManager.Request(uri)
            .setTitle("Importing: $safeFileName")
            .setDescription("RetroRTS is downloading an authorized game file")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "retrorts_import_$safeFileName"
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val manager = context.getSystemService<DownloadManager>() ?: return -1L
        return try {
            val id = manager.enqueue(request)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_TARGET_PREFIX + id, preview.target.name)
                .putString(KEY_NAME_PREFIX + id, safeFileName)
                .putString(KEY_STATUS_PREFIX + id, Stage.QUEUED.name)
                .putString(KEY_DESTINATION_PREFIX + id, preview.destination)
                .apply()
            id
        } catch (exception: SecurityException) {
            Log.e(TAG, "Download permission error", exception)
            -1L
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "Download request rejected", exception)
            -1L
        }
    }

    fun queryStatus(context: Context, downloadId: Long): DownloadStatus {
        if (downloadId <= 0L) return DownloadStatus(-1L, Stage.IDLE, "")
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fileName = prefs.getString(KEY_NAME_PREFIX + downloadId, "Game file") ?: "Game file"
        val destination = prefs.getString(KEY_DESTINATION_PREFIX + downloadId, "") ?: ""
        val savedStage = prefs.getString(KEY_STATUS_PREFIX + downloadId, "") ?: ""
        val persistedStage = runCatching { Stage.valueOf(savedStage) }.getOrNull()
        if (persistedStage in setOf(Stage.INSTALLED, Stage.FAILED, Stage.CANCELLED)) {
            return DownloadStatus(downloadId, persistedStage!!, fileName, destination = destination)
        }

        val manager = context.getSystemService<DownloadManager>()
            ?: return DownloadStatus(downloadId, Stage.UNKNOWN, fileName, destination = destination, message = "Download service unavailable")
        manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return DownloadStatus(downloadId, Stage.UNKNOWN, fileName, destination = destination)
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val stage = when (status) {
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> Stage.QUEUED
                DownloadManager.STATUS_RUNNING -> Stage.DOWNLOADING
                DownloadManager.STATUS_SUCCESSFUL -> Stage.INSTALLING
                DownloadManager.STATUS_FAILED -> Stage.FAILED
                else -> Stage.UNKNOWN
            }
            if (stage == Stage.FAILED) saveStage(context, downloadId, Stage.FAILED)
            return DownloadStatus(
                id = downloadId,
                stage = stage,
                fileName = fileName,
                downloadedBytes = downloaded,
                totalBytes = total,
                destination = destination,
                message = if (stage == Stage.FAILED) "Download failed (code $reason)" else ""
            )
        }
        return DownloadStatus(downloadId, Stage.UNKNOWN, fileName, destination = destination)
    }

    fun cancelDownload(context: Context, downloadId: Long) {
        context.getSystemService<DownloadManager>()?.remove(downloadId)
        saveStage(context, downloadId, Stage.CANCELLED)
    }

    /** Called by [DownloadCompleteReceiver]. Returns true when this is our import. */
    fun handleCompletedDownload(context: Context, downloadId: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val expectedName = prefs.getString(KEY_NAME_PREFIX + downloadId, null) ?: return false
        val manager = context.getSystemService<DownloadManager>() ?: return false
        manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                saveStage(context, downloadId, Stage.FAILED)
                return true
            }
            saveStage(context, downloadId, Stage.INSTALLING)
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: run {
                saveStage(context, downloadId, Stage.FAILED)
                return true
            }
            val downloadedFile = File(Uri.parse(localUri).path ?: "")
            if (!downloadedFile.isFile || !downloadedFile.name.startsWith("retrorts_import_")) {
                saveStage(context, downloadId, Stage.FAILED)
                return true
            }

            val selected = prefs.getString(KEY_TARGET_PREFIX + downloadId, TargetSystem.AUTO.name)
                ?.let { runCatching { TargetSystem.valueOf(it) }.getOrNull() } ?: TargetSystem.AUTO
            val target = if (selected == TargetSystem.AUTO) detectTarget(expectedName) else selected
            val targetDir = destinationFor(target)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Log.e(TAG, "Failed to create game folder: ${targetDir.absolutePath}")
                saveStage(context, downloadId, Stage.FAILED)
                return true
            }

            val destination = uniqueFile(targetDir, expectedName)
            return try {
                downloadedFile.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                downloadedFile.delete()
                prefs.edit()
                    .putString(KEY_STATUS_PREFIX + downloadId, Stage.INSTALLED.name)
                    .putString(KEY_DESTINATION_PREFIX + downloadId, destination.absolutePath)
                    .apply()
                Log.i(TAG, "Installed ${destination.name} to ${destination.absolutePath}")
                true
            } catch (exception: Exception) {
                Log.e(TAG, "Import installation failed", exception)
                saveStage(context, downloadId, Stage.FAILED)
                false
            }
        }
        return false
    }

    private fun saveStage(context: Context, downloadId: Long, stage: Stage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS_PREFIX + downloadId, stage.name)
            .apply()
    }

    private fun filenameFromUri(uri: Uri): String {
        val raw = uri.lastPathSegment ?: return ""
        return URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).trim()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120).ifBlank {
            "download_${System.currentTimeMillis()}"
        }
    }

    private fun detectTarget(fileName: String): TargetSystem {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".cso") -> TargetSystem.PS2
            lower.endsWith(".iso") || lower.endsWith(".chd") -> if (lower.contains("ps2")) TargetSystem.PS2 else TargetSystem.PS1
            lower.endsWith(".bin") || lower.endsWith(".cue") || lower.endsWith(".img") || lower.endsWith(".pbp") -> TargetSystem.PS1
            lower.endsWith(".adf") || lower.endsWith(".hdf") || lower.endsWith(".dms") || lower.endsWith(".ipf") -> TargetSystem.AMIGA
            lower.endsWith(".exe") || lower.endsWith(".com") || lower.endsWith(".bat") || lower.endsWith(".conf") -> TargetSystem.DOSBOX
            lower.endsWith(".nds") || lower.endsWith(".dsi") || lower.endsWith(".srl") || lower.endsWith(".ids") -> TargetSystem.DSI
            lower.endsWith(".zip") -> TargetSystem.IMPORTS
            else -> TargetSystem.IMPORTS
        }
    }

    private fun destinationFor(target: TargetSystem): File {
        val base = File(Environment.getExternalStorageDirectory(), "RetroRTS/Games")
        return File(base, if (target == TargetSystem.AUTO) TargetSystem.IMPORTS.folder else target.folder)
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val candidate = File(directory, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val renamed = File(directory, "$base ($index)" + if (extension.isBlank()) "" else ".$extension")
            if (!renamed.exists()) return renamed
            index++
        }
    }

    private val supportedExtensions = setOf(
        "bin", "cue", "img", "iso", "pbp", "chd", "cso",
        "adf", "hdf", "dms", "ipf",
        "exe", "com", "bat", "conf", "zip",
        "nds", "dsi", "srl", "ids"
    )
}
