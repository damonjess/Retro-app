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
        val roots = mutableListOf(
            File(context.getExternalFilesDir(null), "Imported"),
            File(android.os.Environment.getExternalStorageDirectory(), "RetroRTS/Games")
        )
        
        val validExts = setOf("bin","cue","img","iso","exe","com","bat","adf","hdf","nds","dsi","xbe")
        val found = mutableListOf<GameEntry>()

        roots.forEach { root ->
            if (!root.exists()) return@forEach
            root.walkTopDown().maxDepth(3).forEach { file ->
                val ext = file.extension.lowercase()
                val isExtensionless = !file.name.contains(".")
                
                // Recognize extensionless files if in a specific console folder (DOS/DSi)
                // Amiga games should almost always be .adf, .hdf, or .dms images.
                val isConsoleFolder = file.absolutePath.lowercase().let { 
                    it.contains("/dosbox/") || it.contains("/dsi/")
                }

                if (file.isFile && (ext in validExts || (isExtensionless && isConsoleFolder))) {
                    if (file.length() < 1024 && ext !in setOf("bat", "com", "cue") && !isExtensionless) return@forEach

                    found.add(GameEntry(
                        name        = file.nameWithoutExtension,
                        filePath    = file.absolutePath,
                        consoleType = ConsoleType.detect(file.absolutePath)
                    ))
                } else if (file.isDirectory && file != root) {
                    val hasGame = file.listFiles()?.any {
                        it.extension.lowercase() in validExts
                    } == true
                    if (hasGame) {
                        found.add(GameEntry(
                            name        = file.name,
                            filePath    = file.absolutePath,
                            consoleType = ConsoleType.detect(file.absolutePath)
                        ))
                    }
                }
            }
        }

        val binNames = found
            .filter { it.filePath.endsWith(".bin", ignoreCase = true) }
            .map { it.name.substringBeforeLast('.').lowercase() }
            .toSet()

        val filtered = found.filter { entry ->
            val isFolder = !entry.filePath.contains('.')
            if (isFolder) {
                entry.name.lowercase() !in binNames
            } else true
        }

        // Deduplicate: same base filename = same game, keep first found
        val seenNames = mutableSetOf<String>()
        return filtered.filter { entry ->
            val baseName = File(entry.filePath)
                .nameWithoutExtension
                .lowercase()
                .replace(" ", "_")
                .replace("(", "").replace(")", "")
                .replace("[", "").replace("]", "")
            seenNames.add(baseName)   // returns false if already present
        }
    }

    fun clearAndRescan(context: Context): List<GameEntry> {
        libraryFile(context).delete()
        return scanGamesFolder(context)
    }
}
