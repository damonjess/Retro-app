package com.retrorts.ui

import android.os.Environment
import org.json.JSONObject
import java.io.File

data class GameProfile(
    val gameId: String,
    val title: String,
    val os: String,
    val cycles: Int,
    val frameCap: Int,
    val memMb: Int,
    val mixerRate: Int,
    val machine: String,
    val platform: String = "dosbox",
    val skipKey: String = " "   // default: space
) {
    fun toJson(): String = JSONObject()
        .put("gameId", gameId)
        .put("title", title)
        .put("os", os)
        .put("cycles", cycles)
        .put("frameCap", frameCap)
        .put("memMb", memMb)
        .put("mixerRate", mixerRate)
        .put("machine", machine)
        .put("platform", platform)
        .put("skipKey", skipKey)
        .toString(2)

    fun toDosboxConfig(gameFilePath: String): String {
        val file = File(gameFilePath)
        val parentDir = if (file.isDirectory) file.absolutePath else (file.parentFile?.absolutePath ?: "/")
        val fileName = if (file.isDirectory) "" else file.name

        return """
            [dosbox]
            machine=$machine
            memsize=$memMb

            [cpu]
            core=dynamic
            cycles=$cycles

            [render]
            frameskip=0

            [mixer]
            rate=$mixerRate
            blocksize=1024
            prebuffer=20

            [sdl]
            priority=higher,normal

            [autoexec]
            @echo off
            mount c "$parentDir"
            c:
            ${if (fileName.isNotEmpty()) (if (fileName.contains(" ")) "\"$fileName\"" else fileName) else ""}
            if exist dune2000.exe dune2000.exe
            if exist ra95.exe ra95.exe
            if exist c&c.exe c&c.exe
            if exist play.bat call play.bat
        """.trimIndent()
    }

    fun toAmigaConfig(gameDirectoryPath: String): String = """
        [general]
        fullscreen=false
        width=640
        height=512
        amiga_model=A500
        cpu_speed=fastest
        cpu_type=68000
        fpu_type=none
        chipset=ocs
        chipram=2
        fastram=0
        bogomem=0
        z3fastram=0

        [display]
        framerate=50
        vsync=true
        linemode=scanlines
        aspect=true

        [sound]
        sound=true
        frequency=$mixerRate
        channels=2
        volume=100

        [input]
        joystick_type=automatic
        mouse_speed=100

        [cpu]
        cpu_cycle_exact=false
        cpu_compatible=true

        [blitter]
        blitter_cycle_exact=false
        blitter_compatible=true
    """.trimIndent()

    companion object {
        fun fromJson(json: String): GameProfile {
            val j = JSONObject(json)
            return GameProfile(
                gameId = j.getString("gameId"),
                title = j.getString("title"),
                os = j.getString("os"),
                cycles = j.getInt("cycles"),
                frameCap = j.getInt("frameCap"),
                memMb = j.getInt("memMb"),
                mixerRate = j.getInt("mixerRate"),
                machine = j.getString("machine"),
                platform = j.optString("platform", "dosbox"),
                skipKey = j.optString("skipKey", " ")
            )
        }

        fun presetRedAlert95() = GameProfile(
            gameId = "cnc_red_alert_win95",
            title = "Command & Conquer: Red Alert",
            os = "Windows 95",
            cycles = 30000,
            frameCap = 60,
            memMb = 64,
            mixerRate = 44100,
            machine = "svga_s3",
        )

        fun presetDune2000Win98() = GameProfile(
            gameId = "dune_2000_win98",
            title = "Dune 2000",
            os = "Windows 98",
            cycles = 35000,
            frameCap = 60,
            memMb = 128,
            mixerRate = 48000,
            machine = "svga_s3",
        )

        fun presetAmigaA500() = GameProfile(
            gameId = "amiga_a500_demo",
            title = "Amiga A500 Demo",
            os = "Amiga Kickstart 1.3",
            cycles = 0,
            frameCap = 50,
            memMb = 1,
            mixerRate = 44100,
            machine = "amiga_a500",
            platform = "amiga",
        )

        fun presetDune1992Amiga() = GameProfile(
            gameId = "dune_1992_amiga",
            title = "Dune (1992)",
            os = "Amiga Kickstart 1.3",
            cycles = 0,
            frameCap = 50,
            memMb = 1,
            mixerRate = 44100,
            machine = "amiga_a500",
            platform = "amiga",
        )

        fun presetDuneIIAmiga() = GameProfile(
            gameId = "dune_ii_amiga",
            title = "Dune II: The Battle for Arrakis",
            os = "Amiga Kickstart 1.3",
            cycles = 0,
            frameCap = 50,
            memMb = 2,
            mixerRate = 44100,
            machine = "amiga_a500",
            platform = "amiga",
        )

        fun presetNintendoDsi() = GameProfile(
            gameId = "nintendo_dsi_demo",
            title = "Nintendo DSi Demo",
            os = "Nintendo DSi firmware",
            cycles = 0,
            frameCap = 60,
            memMb = 16,
            mixerRate = 48000,
            machine = "nintendo_dsi",
            platform = "dsi",
        )

        fun presetPs1() = GameProfile(
            gameId = "ps1_game_demo",
            title = "PS1 Game",
            os = "PlayStation 1",
            cycles = 0,
            frameCap = 60,
            memMb = 2,
            mixerRate = 44100,
            machine = "psx",
            platform = "ps1",
        )

        fun presetXbox() = GameProfile(
            gameId = "xbox_game_demo",
            title = "Xbox Game",
            os = "Original Xbox",
            cycles = 0,
            frameCap = 60,
            memMb = 64,
            mixerRate = 48000,
            machine = "xbox",
            platform = "xbox",
        )
    }
}

enum class ConsoleType {
    DOSBOX,
    AMIGA,
    NINTENDO_DSI,
    PS1,
    PS2,
    XBOX,
    UNKNOWN;

    companion object {
        fun detect(filePath: String): ConsoleType {
            val n = filePath.lowercase()
            val file = java.io.File(filePath)
            val parentName = file.parentFile?.name?.lowercase() ?: ""

            return when {
                n.endsWith(".nds") || n.endsWith(".dsi") || n.endsWith(".srl")
                    -> NINTENDO_DSI
                n.endsWith(".adf") || n.endsWith(".hdf") || n.endsWith(".dms")
                    -> AMIGA
                n.endsWith(".bin") || n.endsWith(".cue") || n.endsWith(".img")
                    -> PS1
                n.endsWith(".iso") -> {
                    if (file.exists() && file.length() > 700 * 1024 * 1024) {
                        // Heuristic: PS2 ISOs are large, Xbox ISOs (XISO) can also be large.
                        // If path contains xbox, it's likely xbox.
                        if (n.contains("xbox")) XBOX else PS2
                    } else PS1
                }
                n.endsWith(".xbe") -> XBOX
                // Heuristic for extensionless files
                !file.name.contains(".") -> {
                    when {
                        parentName == "amiga" || n.contains("/amiga/") -> AMIGA
                        parentName == "dsi" || n.contains("/dsi/") -> NINTENDO_DSI
                        parentName == "xbox" || n.contains("/xbox/") -> XBOX
                        else -> DOSBOX
                    }
                }
                else -> DOSBOX
            }
        }
    }
}

object GameProfileStore {
    private val ROOT: String get() =
        "${android.os.Environment.getExternalStorageDirectory().absolutePath}/RetroRTS/profiles"

    fun ensurePresetProfiles() {
        runCatching {
            val dir = File(ROOT)
            if (!dir.exists()) dir.mkdirs()
            writeIfMissing(GameProfile.presetRedAlert95())
            writeIfMissing(GameProfile.presetDune2000Win98())
            writeIfMissing(GameProfile.presetAmigaA500())
            writeIfMissing(GameProfile.presetDune1992Amiga())
            writeIfMissing(GameProfile.presetDuneIIAmiga())
            writeIfMissing(GameProfile.presetNintendoDsi())
            writeIfMissing(GameProfile.presetPs1())
            writeIfMissing(GameProfile.presetXbox())
        }
    }

    private fun writeIfMissing(profile: GameProfile) {
        val file = File(ROOT, "${profile.gameId}.json")
        if (!file.exists()) file.writeText(profile.toJson())
    }

    fun loadByGameName(name: String): GameProfile {
        ensurePresetProfiles()
        val key = name.lowercase()
        val gameId = gameIdForName(key)
        val file = File(ROOT, "$gameId.json")
        return runCatching {
            if (file.exists()) GameProfile.fromJson(file.readText()) else presetForGameId(gameId)
        }.getOrElse { presetForGameId(gameId) }
    }

    private fun gameIdForName(key: String): String = when {
        "red alert" in key || "command" in key || "c&c" in key -> "cnc_red_alert_win95"
        "dune 2000" in key -> "dune_2000_win98"
        ("dune" in key && ("1992" in key || "adventure" in key || "disk 1" in key)) -> "dune_1992_amiga"
        "dune ii" in key || "dune 2" in key || key == "dune" -> "dune_ii_amiga"
        "amiga" in key || "a500" in key -> "amiga_a500_demo"
        "dsi" in key || "nintendo ds" in key -> "nintendo_dsi_demo"
        "ps1" in key || "playstation" in key || "psx" in key -> "ps1_game_demo"
        "xbox" in key -> "xbox_game_demo"
        else -> key.replace(" ", "_")
    }

    private fun presetForGameId(gameId: String): GameProfile = when (gameId) {
        "cnc_red_alert_win95" -> GameProfile.presetRedAlert95()
        "dune_1992_amiga" -> GameProfile.presetDune1992Amiga()
        "dune_ii_amiga" -> GameProfile.presetDuneIIAmiga()
        "amiga_a500_demo" -> GameProfile.presetAmigaA500()
        "nintendo_dsi_demo" -> GameProfile.presetNintendoDsi()
        "ps1_game_demo" -> GameProfile.presetPs1()
        "xbox_game_demo" -> GameProfile.presetXbox()
        else -> GameProfile.presetDune2000Win98()
    }
}
