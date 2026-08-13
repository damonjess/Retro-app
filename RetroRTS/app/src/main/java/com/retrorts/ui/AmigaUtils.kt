package com.retrorts.ui

import android.os.Environment
import java.io.File

object AmigaUtils {
    private const val AMIGA_SYSTEM_DIR = "RetroRTS/system/amiga"
    private const val AMIGA_GAMES_DIR = "RetroRTS/Games/Amiga"
    private val numberedDiskPattern = Regex(
        """(?i)^(.*?)[._\\s-]*(?:disk|disc)[._\\s-]*(\\d+)$"""
    )

    data class DiskSet(
        val diskPaths: List<String>,
        val selectedDiskNumber: Int,
    ) {
        val isMultiDisk: Boolean get() = diskPaths.size > 1
    }

    /**
     * Finds numbered sibling images beside [filePath] and returns them in boot
     * order. For example, selecting Dune_Disk1.adf yields Disk 1, Disk 2, and
     * Disk 3 when all three files are present. Unnumbered images remain a
     * one-item set.
     */
    fun diskSetFor(filePath: String): DiskSet {
        val selectedFile = File(filePath)
        val selectedMatch = numberedDiskPattern.matchEntire(selectedFile.nameWithoutExtension)
            ?: return DiskSet(listOf(filePath), selectedDiskNumber = 1)
        val parent = selectedFile.parentFile
            ?: return DiskSet(listOf(filePath), selectedDiskNumber = 1)
        val gameStem = selectedMatch.groupValues[1].trim().lowercase()
        val extension = selectedFile.extension.lowercase()

        val candidates = parent.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.lowercase() == extension }
            ?.mapNotNull { file ->
                val match = numberedDiskPattern.matchEntire(file.nameWithoutExtension)
                    ?: return@mapNotNull null
                val stem = match.groupValues[1].trim().lowercase()
                if (stem != gameStem) return@mapNotNull null
                val diskNumber = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                diskNumber to file.absolutePath
            }
            ?.sortedBy { it.first }
            ?.toList()
            .orEmpty()

        if (candidates.isEmpty()) return DiskSet(listOf(filePath), selectedDiskNumber = 1)
        val selectedNumber = selectedMatch.groupValues[2].toIntOrNull() ?: 1
        val selectedPosition = candidates.indexOfFirst { it.first == selectedNumber }
        return DiskSet(
            diskPaths = candidates.map { it.second },
            selectedDiskNumber = if (selectedPosition >= 0) selectedPosition + 1 else 1,
        )
    }

    /**
     * Verifies if the required Kickstart 1.3 ROM is present.
     */
    fun isKick13Present(): Boolean {
        val root = Environment.getExternalStorageDirectory()
        val kick13 = File(root, "$AMIGA_SYSTEM_DIR/kick13.rom")
        return kick13.exists() && kick13.length() > 0
    }

    /**
     * Returns true only when a Dune disk image has at least one numbered
     * sibling. Disk 1 is intentionally included because it is the normal boot
     * target for a complete Dune installation.
     */
    fun isDuneMultiDisk(filePath: String): Boolean =
        File(filePath).name.contains("dune", ignoreCase = true) && diskSetFor(filePath).isMultiDisk

    /**
     * Identifies "messy" Dune filenames that should be renamed.
     */
    fun getDuneRenameTarget(file: File): String? {
        val name = file.name.lowercase()
        if (!name.contains("dune") || !name.endsWith(".adf")) return null
        
        return when {
            name.contains("disk 1") || name.contains("disk1") -> "Dune_Disk1.adf"
            name.contains("disk 2") || name.contains("disk2") -> "Dune_Disk2.adf"
            name.contains("disk 3") || name.contains("disk3") -> "Dune_Disk3.adf"
            else -> null
        }
    }

    /**
     * Renames messy Dune ADF files to standard names in the Amiga games directory.
     * Returns a list of results.
     */
    fun fixDuneFilenames(): List<String> {
        val results = mutableListOf<String>()
        val gamesDir = File(Environment.getExternalStorageDirectory(), AMIGA_GAMES_DIR)
        
        if (!gamesDir.exists()) {
            results.add("Amiga games directory not found.")
            return results
        }

        gamesDir.listFiles()?.forEach { file ->
            val newName = getDuneRenameTarget(file)
            if (newName != null && file.name != newName) {
                val dest = File(gamesDir, newName)
                if (dest.exists()) {
                    results.add("Skipped ${file.name}: $newName already exists.")
                } else if (file.renameTo(dest)) {
                    results.add("Renamed ${file.name} to $newName")
                } else {
                    results.add("Failed to rename ${file.name}")
                }
            }
        }
        
        if (results.isEmpty()) results.add("No files needed renaming.")
        return results
    }
}
