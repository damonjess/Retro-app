package com.retrorts

import android.content.Context
import com.retrorts.ui.ConsoleType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object GameLibrary {

    private fun libraryFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Config")
            .also { it.mkdirs() }
        return File(dir, "library.json")
    }

    fun save(context: Context, games: List<GameEntry>) {
        runCatching {
            val arr = JSONArray()
            games.forEach { g ->
                arr.put(JSONObject().apply {
                    put("name",        g.name)
                    put("filePath",    g.filePath)
                    put("gameId",      g.gameId)
                    put("consoleType", g.consoleType.name)
                })
            }
            libraryFile(context).writeText(arr.toString())
        }
    }

    fun load(context: Context): List<GameEntry> {
        return runCatching {
            val text = libraryFile(context).readText()
            val arr  = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val filePath = o.getString("filePath")
                val consoleName = o.optString("consoleType", "")
                val console = if (consoleName.isNotEmpty()) {
                    runCatching { ConsoleType.valueOf(consoleName) }.getOrDefault(ConsoleType.detect(filePath))
                } else {
                    ConsoleType.detect(filePath)
                }
                
                GameEntry(
                    name        = o.getString("name"),
                    filePath    = filePath,
                    consoleType = console,
                    gameId      = o.optString("gameId", o.getString("name")
                        .lowercase().replace(" ", "_"))
                )
            }
        }.getOrElse {
             // Migration check: try reading from legacy location
             runCatching {
                 val legacy = File(android.os.Environment.getExternalStorageDirectory(), "RetroRTS/library.json")
                 if (legacy.exists()) {
                     val list = JSONArray(legacy.readText()).let { a ->
                         (0 until a.length()).map { i ->
                             val o = a.getJSONObject(i)
                             val path = o.getString("filePath")
                             GameEntry(
                                 name = o.getString("name"),
                                 filePath = path,
                                 consoleType = ConsoleType.detect(path)
                             )
                         }
                     }
                     save(context, list)
                     legacy.delete() // Cleanup legacy file after migration
                     list
                 } else emptyList()
             }.getOrDefault(emptyList())
        }
    }

    fun scanGamesFolder(context: Context): List<GameEntry> {
        val externalRoot = android.os.Environment.getExternalStorageDirectory()
        val roots = listOf(
            File(context.getExternalFilesDir(null), "Imported"),
            File(externalRoot, "RetroRTS/Games"),
            // Support older installs that created this directory with a lower-case name.
            File(externalRoot, "RetroRTS/games")
        ).distinctBy { it.absolutePath }

        // Include all formats advertised by the app. PS1/PS2 images are often
        // CHD/PBP/CSO rather than only BIN/CUE, which the previous scan skipped.
        val validExts = setOf(
            "bin", "cue", "img", "iso", "pbp", "chd", "cso", "ecm", "ccd",
            "exe", "com", "bat", "conf",
            "adf", "hdf", "dms", "ipf",
            "nds", "dsi", "srl", "xbe"
        )
        val smallValidFiles = setOf("bat", "com", "cue", "ccd", "conf")
        val found = mutableListOf<GameEntry>()

        roots.forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown().maxDepth(6).forEach { file ->
                if (!file.isFile) return@forEach
                val ext = file.extension.lowercase()
                val isExtensionless = !file.name.contains('.')
                val path = file.absolutePath.lowercase()
                val isConsoleFolder = path.contains("/dosbox/") || path.contains("/dsi/")
                if (ext !in validExts && !(isExtensionless && isConsoleFolder)) return@forEach
                if (file.length() < 1024 && ext !in smallValidFiles && !isExtensionless) return@forEach

                // A .cue is the launch entry for a multi-track PS1 disc. Hide
                // its paired .bin image, but preserve standalone BIN discs.
                if (ext == "bin") {
                    val cue = File(file.parentFile, file.nameWithoutExtension + ".cue")
                    if (cue.isFile) return@forEach
                }

                found.add(
                    GameEntry(
                        name = file.nameWithoutExtension,
                        filePath = file.absolutePath,
                        consoleType = ConsoleType.detect(file.absolutePath)
                    )
                )
            }
        }

        val seenPaths = mutableSetOf<String>()
        return found
            .sortedWith(compareBy({ it.consoleType.name }, { it.name.lowercase() }))
            .filter { seenPaths.add(it.filePath.lowercase()) }
    }

    fun clearAndRescan(context: Context): List<GameEntry> {
        libraryFile(context).delete()
        val scanned = scanGamesFolder(context)
        // Persist immediately. Existing UI code that reloads GameLibrary after
        // pressing Scan will now receive the newly discovered games.
        save(context, scanned)
        return scanned
    }
}
