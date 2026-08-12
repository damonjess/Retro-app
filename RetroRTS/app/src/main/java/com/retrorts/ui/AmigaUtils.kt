package com.retrorts.ui

import android.os.Environment
import java.io.File

object AmigaUtils {
    private const val AMIGA_SYSTEM_DIR = "RetroRTS/system/amiga"
    private const val AMIGA_GAMES_DIR = "RetroRTS/Games/Amiga"

    /**
     * Verifies if the required Kickstart 1.3 ROM is present.
     */
    fun isKick13Present(): Boolean {
        val root = Environment.getExternalStorageDirectory()
        val kick13 = File(root, "$AMIGA_SYSTEM_DIR/kick13.rom")
        return kick13.exists() && kick13.length() > 0
    }

    /**
     * Checks if a file is Disk 2 or 3 of Dune (1992).
     */
    fun isDuneMultiDisk(filePath: String): Boolean {
        val name = filePath.lowercase()
        if (!name.contains("dune")) return false
        return name.contains("disk 2") || name.contains("disk 3") || 
               name.contains("disk2") || name.contains("disk3")
    }

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
