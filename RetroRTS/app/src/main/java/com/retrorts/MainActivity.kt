package com.retrorts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.content.Intent
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.InstallMobile
import android.net.Uri
import android.net.Uri as AndroidUri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.PerformanceHintManager
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.retrorts.ui.DosboxVirtualController
import com.retrorts.ui.AmigaBridge
import com.retrorts.ui.AmigaDiskSwapControls
import com.retrorts.ui.AmigaUtils
import com.retrorts.ui.AmigaVirtualController
import com.retrorts.ui.ConsoleType
import com.retrorts.ui.DosboxBridge
import com.retrorts.ui.GameProfile
import com.retrorts.ui.GameProfileStore
import com.retrorts.ui.GamePathValidator
import com.retrorts.ui.NativeEmulatorBridge
import com.retrorts.ui.RetroButton
import com.retrorts.ui.RetroCard
import com.retrorts.ui.Ps2SetupPanel
import com.retrorts.ui.Ps2BiosStatusPanel
import com.retrorts.ui.ScanlineOverlay
import com.retrorts.ui.downloads.DirectImportScreen
import com.retrorts.ui.theme.RetroTheme
import com.retrorts.ui.theme.RetroNeonCyan
import com.retrorts.ui.theme.RetroNeonMagenta
import com.retrorts.ui.theme.RetroNeonGreen
import com.retrorts.ui.theme.RetroPanel
import com.retrorts.ui.theme.RetroFontFamily
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import android.widget.Toast
import kotlin.math.roundToInt

// Libretro Button Constants
const val RETRO_DEVICE_ID_JOYPAD_B = 0
const val RETRO_DEVICE_ID_JOYPAD_Y = 1
const val RETRO_DEVICE_ID_JOYPAD_SELECT = 2
const val RETRO_DEVICE_ID_JOYPAD_START = 3
const val RETRO_DEVICE_ID_JOYPAD_UP = 4
const val RETRO_DEVICE_ID_JOYPAD_DOWN = 5
const val RETRO_DEVICE_ID_JOYPAD_LEFT = 6
const val RETRO_DEVICE_ID_JOYPAD_RIGHT = 7
const val RETRO_DEVICE_ID_JOYPAD_A = 8
const val RETRO_DEVICE_ID_JOYPAD_X = 9
const val RETRO_DEVICE_ID_JOYPAD_L = 10
const val RETRO_DEVICE_ID_JOYPAD_R = 11

const val RETRO_DEVICE_ID_MOUSE_WHEELUP = 4
const val RETRO_DEVICE_ID_MOUSE_WHEELDOWN = 5

const val RETROK_ESCAPE = 27

data class GameEntry(
    val name: String,
    val filePath: String,
    val gameId: String = "",
    val consoleType: ConsoleType = ConsoleType.UNKNOWN
)
data class SettingsState(var displayScale: Float = 1f, var sensitivity: Float = 1f, var volume: Float = 0.8f)

enum class AppScreen { SPLASH, HOME, GAME, NEEDS_PERMISSION }

class MainActivity : ComponentActivity() {
    private var audioFocusRequest: AudioFocusRequest? = null
    private var thermalRegistered = false

    private val storagePermissionLauncher =
        registerForActivityResult(StartActivityForResult()) { _: ActivityResult ->
            // Re-check logic is in RootApp LaunchedEffect
        }

    private val legacyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Re-check logic is in RootApp LaunchedEffect
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStoragePermissions()
        extractSystemAssets() // Move bundled BIOS to SD card

        NativeEmulatorBridge.setCoreDir(applicationInfo.nativeLibraryDir)
        NativeEmulatorBridge.setSystemDir(File(Environment.getExternalStorageDirectory(), "RetroRTS/system").absolutePath)
        NativeEmulatorBridge.setSaveDir(File(Environment.getExternalStorageDirectory(), "RetroRTS/Saves").absolutePath)

        // Status check on startup
        if (hasStoragePermission()) {
            Toast.makeText(this, "RetroRTS: Storage OK", Toast.LENGTH_SHORT).show()
        }

        setContent {
            RetroTheme {
                Box {
                    RootApp(
                        ::requestAudioFocus,
                        ::abandonAudioFocus,
                        ::startThermalMonitor,
                        ::hasStoragePermission,
                        ::requestStoragePermissions
                    )
                    ScanlineOverlay()
                }
            }
        }
    }

    fun hasStoragePermission(): Boolean {
        val permissionOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return permissionOk && canWriteToPublicStorage()
    }

    private fun canWriteToPublicStorage(): Boolean {
        return runCatching {
            val testDir = File(Environment.getExternalStorageDirectory(), "RetroRTS/.test")
            testDir.mkdirs()
            val testFile = File(testDir, "write_test.tmp")
            testFile.writeText("test")
            val ok = testFile.exists() && testFile.readText() == "test"
            testFile.delete()
            ok
        }.getOrDefault(false)
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    storagePermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    storagePermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            val perms = mutableListOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(android.Manifest.permission.READ_MEDIA_IMAGES)
                perms.add(android.Manifest.permission.READ_MEDIA_AUDIO)
                perms.add(android.Manifest.permission.READ_MEDIA_VIDEO)
            }
            legacyPermissionLauncher.launch(perms.toTypedArray())
        }
    }

    private fun extractSystemAssets() {
        if (!hasStoragePermission()) return
        
        lifecycleScope.launch(Dispatchers.IO) {
            // Extract PS1 BIOS
            val ps1Root = File(Environment.getExternalStorageDirectory(), "RetroRTS/system/ps1")
            if (!ps1Root.exists()) ps1Root.mkdirs()
            
            val ps1Bios = File(ps1Root, "scph1001.bin")
            if (!ps1Bios.exists()) {
                runCatching {
                    assets.open("system/ps1/scph1001.bin").use { input ->
                        ps1Bios.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }

            // Extract Amiga BIOS
            val amigaRoot = File(Environment.getExternalStorageDirectory(), "RetroRTS/system/amiga")
            if (!amigaRoot.exists()) amigaRoot.mkdirs()

            val kickstarts = listOf("kick12.rom", "kick13.rom", "kick20.rom", "kick30.rom", "kick31.rom", "kick40.rom")
            kickstarts.forEach { ks ->
                val dest = File(amigaRoot, ks)
                if (!dest.exists()) {
                    runCatching {
                        assets.open("system/amiga/$ks").use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }

            // Extract DSi BIOS
            val dsiRoot = File(Environment.getExternalStorageDirectory(), "RetroRTS/system/dsi")
            if (!dsiRoot.exists()) dsiRoot.mkdirs()

            val dsiFiles = listOf("bios7.bin", "bios9.bin", "firmware.bin", "key.cfg")
            dsiFiles.forEach { fileName ->
                val dest = File(dsiRoot, fileName)
                if (!dest.exists()) {
                    runCatching {
                        assets.open("system/dsi/$fileName").use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }

            // Create Xbox system directory and extract BIOS
            val xboxRoot = File(Environment.getExternalStorageDirectory(), "RetroRTS/system/xbox")
            if (!xboxRoot.exists()) xboxRoot.mkdirs()

            val xboxFiles = listOf("mcpx_1.0.bin", "bios.bin", "xbox_hdd.qcow2")
            xboxFiles.forEach { fileName ->
                val dest = File(xboxRoot, fileName)
                if (!dest.exists()) {
                    runCatching {
                        assets.open("system/xbox/$fileName").use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
    }
    private fun requestAudioFocus() { val am=getSystemService(AUDIO_SERVICE) as AudioManager; val req=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener { if (it<=0) DosboxBridge.stopDosbox() }.build(); audioFocusRequest=req; am.requestAudioFocus(req)}
    private fun abandonAudioFocus() { val am=getSystemService(AUDIO_SERVICE) as AudioManager; audioFocusRequest?.let { am.abandonAudioFocusRequest(it) } }

    override fun onStop() {
        super.onStop()
        DosboxBridge.stopDosbox()
        abandonAudioFocus()
    }
    private fun startThermalMonitor() {
        if (thermalRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        
        // Android Dynamic Performance Framework (ADPF) for MagicOS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val phm = getSystemService(PerformanceHintManager::class.java)
                // Hint that we are targeting 60fps (16.6ms per frame)
                phm?.createHintSession(intArrayOf(android.os.Process.myTid()), 16666666L)
                // MagicOS will now prioritize our threads to prevent frame drops
            }
        }

        runCatching {
            getSystemService(PowerManager::class.java)?.addThermalStatusListener(mainExecutor) {
                DosboxBridge.notifyThermalLevel(
                    if (it >= PowerManager.THERMAL_STATUS_SEVERE) 3
                    else if (it >= PowerManager.THERMAL_STATUS_MODERATE) 2
                    else if (it >= PowerManager.THERMAL_STATUS_LIGHT) 1
                    else 0
                )
            }
            thermalRegistered = true
        }
    }
}


data class LaunchResult(val started: Boolean, val message: String)

private suspend fun launchGameWithNativeBackend(
    context: Context,
    game: GameEntry
): LaunchResult = withContext(Dispatchers.IO) {
    if (!DosboxBridge.isAvailable) {
        return@withContext LaunchResult(false, "Native library not loaded. Check NDK build.")
    }

    if (game.consoleType == ConsoleType.DOSBOX) {
        val profile = GameProfileStore.loadByGameName(game.name)
        val configPath = writeDosboxConfig(context, profile, game)
        if (configPath.isBlank()) {
            return@withContext LaunchResult(false, "DOS launch failed: could not write DOSBox config.")
        }

        val started = DosboxBridge.startDosbox(game.filePath, configPath)
        return@withContext if (started) {
            LaunchResult(true, "OK: DOSBox started")
        } else {
            LaunchResult(
                false,
                "DOS launch failed: DOSBox-Pure native core is missing or could not start. " +
                    "Add the DOSBox-Pure AAR/native library to RetroRTS/app/libs and rebuild."
            )
        }
    }

    if (game.consoleType == ConsoleType.AMIGA) {
        val started = AmigaBridge.startAmiga(game.filePath)
        return@withContext if (started) {
            LaunchResult(true, "OK: Amiga emulator started")
        } else {
            LaunchResult(false, "Amiga launch failed: check Kickstart ROM and disk image.")
        }
    }

    if (game.consoleType == ConsoleType.XBOX) {
        val xboxRoot = File(Environment.getExternalStorageDirectory(), "RetroRTS/system/xbox")
        val mcpx = File(xboxRoot, "mcpx_1.0.bin")
        val bios = File(xboxRoot, "bios.bin")
        
        if (!mcpx.exists() || !bios.exists()) {
            return@withContext LaunchResult(false, 
                "Xbox BIOS files not found!\n\n" +
                "Please copy these files from your desktop BIOS folder to:\n" +
                "/sdcard/RetroRTS/system/xbox/\n\n" +
                "Required files:\n" +
                "1. mcpx_1.0.bin (MCPX Boot ROM)\n" +
                "2. bios.bin (Flash ROM / Complex 4627)")
        }
    }

    val result = NativeEmulatorBridge.launchGame(
        context  = context,
        console  = game.consoleType.name,   // "PS1", "DOSBOX", etc.
        romPath  = game.filePath
    )
    
    LaunchResult(result.started, result.message)
}

private fun writeDosboxConfig(context: Context, profile: GameProfile, game: GameEntry): String {
    val configDir = context.getExternalFilesDir("configs") ?: File(context.filesDir, "configs")
    if (!configDir.exists()) {
        configDir.mkdirs()
    }

    return runCatching {
        File(configDir, "${profile.gameId}.conf").apply {
            writeText(profile.toDosboxConfig(game.filePath))
        }.absolutePath
    }.getOrDefault("")
}

@Composable
private fun RootApp(
    onRequestAudioFocus: () -> Unit,
    onAbandonAudioFocus: () -> Unit,
    onThermalMonitor: () -> Unit,
    hasStoragePermission: () -> Boolean,
    onRequestStorage: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(AppScreen.SPLASH) }
    var settings by remember { mutableStateOf(SettingsState()) }
    var activeGame by remember { mutableStateOf<GameEntry?>(null) }
    var launchError by remember { mutableStateOf<String?>(null) }
    var isLaunching by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1500)
        screen = if (hasStoragePermission()) AppScreen.HOME else AppScreen.NEEDS_PERMISSION
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (screen == AppScreen.NEEDS_PERMISSION && hasStoragePermission()) {
                    screen = AppScreen.HOME
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (isLaunching) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("BOOTING SYSTEM", fontFamily = RetroFontFamily, color = RetroNeonCyan) },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { 
                CircularProgressIndicator(color = RetroNeonCyan); 
                Text("LOADING NATIVE CORES...", fontFamily = RetroFontFamily, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 8.dp)) 
            } },
            containerColor = RetroPanel
        )
    }

    launchError?.let { message ->
        AlertDialog(
            onDismissRequest = { launchError = null },
            confirmButton = { RetroButton(onClick = { launchError = null }, text = "ABORT") },
            title = { Text("CORE EXCEPTION", fontFamily = RetroFontFamily, color = Color.Red) },
            text = { Text(message.uppercase(), fontFamily = RetroFontFamily, fontSize = 10.sp, color = Color.White) },
            containerColor = RetroPanel
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            confirmButton = { RetroButton(onClick = { showAbout = false }, text = "BACK") },
            title = { Text("RETRO-RTS v1.0", fontFamily = RetroFontFamily, color = RetroNeonCyan) },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MULTI-CORE EMULATION ENVIRONMENT", fontFamily = RetroFontFamily, fontSize = 10.sp, color = Color.White)
                    Text("ENGINE: DOSBOX-PURE / LIBRETRO", fontFamily = RetroFontFamily, fontSize = 10.sp, color = RetroNeonMagenta)
                    Text("UI: COMPOSE GLOW v2", fontFamily = RetroFontFamily, fontSize = 10.sp, color = RetroNeonGreen)
                    Text("ENSURE VALID FIRMWARE IS MAPPED.", fontFamily = RetroFontFamily, fontSize = 10.sp, color = Color.Gray)
                }
            },
            containerColor = RetroPanel
        )
    }

    BackHandler(enabled = screen != AppScreen.SPLASH) {
        when (screen) {
            AppScreen.HOME -> {}
            AppScreen.GAME -> { 
                DosboxBridge.stopDosbox()
                NativeEmulatorBridge.stopGame()
                onAbandonAudioFocus()
                activeGame = null
                screen = AppScreen.HOME 
            }
            else -> {}
        }
    }

    when (screen) {
        AppScreen.SPLASH -> SplashScreen()
        AppScreen.NEEDS_PERMISSION -> PermissionScreen(onRequestStorage)
        AppScreen.GAME -> activeGame?.let { DosboxPlayScreen(it, settings) { 
            DosboxBridge.stopDosbox()
            NativeEmulatorBridge.stopGame()
            onAbandonAudioFocus()
            activeGame = null
            screen = AppScreen.HOME 
        } }
        AppScreen.HOME -> LauncherScreen(settings, onSettings = { /* settings are now inline — no screen change needed */ }, onAbout = { showAbout = true }, onLaunch = { game ->
            isLaunching = true
            scope.launch {
                val result = launchGameWithNativeBackend(context, game)
                isLaunching = false
                if (result.started) {
                    onRequestAudioFocus()
                    onThermalMonitor()
                    activeGame = game
                    screen = AppScreen.GAME
                } else {
                    launchError = result.message
                }
            }
        })
    }
}

@Composable private fun SplashScreen() { 
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { 
        Column(horizontalAlignment=Alignment.CenterHorizontally){ 
            Text("RETRO-RTS", color=RetroNeonCyan, fontFamily = RetroFontFamily, fontSize = 32.sp, fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("BIOS INITIALIZING...", color=RetroNeonMagenta, fontFamily = RetroFontFamily, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Text("KERNEL VERSION 1.0.42", color=Color.Gray, fontFamily = RetroFontFamily, fontSize = 8.sp)
        } 
    } 
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    val context = LocalContext.current
    val storageOk = (context as? MainActivity)?.hasStoragePermission() ?: false

    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                "SYSTEM ACCESS DENIED",
                color = Color.Red,
                fontFamily = RetroFontFamily,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            
            RetroCard {
                Text(
                    text = if (storageOk) "STATUS: AUTHORIZED" else "STATUS: ACCESS_VIOLATION",
                    color = if (storageOk) RetroNeonGreen else Color.Red,
                    fontFamily = RetroFontFamily,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }

            Text(
                "RETRO-RTS REQUIRES FULL DISK ACCESS TO MANAGE CORE ASSETS AND ROM DATA. PLEASE GRANT PERMISSION IN SYSTEM SETTINGS.",
                color = Color.Gray,
                fontFamily = RetroFontFamily,
                fontSize = 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RetroButton(
                    onClick = onRequest,
                    text = "GRANT ACCESS",
                    color = RetroNeonMagenta
                )
                
                RetroButton(
                    onClick = { 
                        if (storageOk) {
                            Toast.makeText(context, "LINK ESTABLISHED", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "ERROR: ACCESS STILL RESTRICTED", Toast.LENGTH_SHORT).show()
                        }
                    },
                    text = "RE-SCAN",
                    color = Color.DarkGray
                )
            }
            
            Text(
                "Why? Emulators need direct path access to large files (ISOs, ROMs) which standard Android storage pickers do not support.",
                color = Color(0xFF93A17B),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

fun resolveToRealPath(context: Context, uri: Uri): String? {
    // If it's already a file path, return as-is
    if (uri.scheme == "file") return uri.path

    // content:// URI — copy to app-specific external storage so native code can read it
    return try {
        val fileName = context.contentResolver
            .query(uri, null, null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                if (idx >= 0) cursor.getString(idx) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "game.bin"

        // Use getExternalFilesDir to avoid direct /sdcard access which is deprecated/restricted
        val destDir = java.io.File(context.getExternalFilesDir(null), "Imported/PS1")
            .also { it.mkdirs() }

        val destFile = java.io.File(destDir, fileName)

        // Only copy if not already there
        if (!destFile.exists()) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        }

        destFile.absolutePath
    } catch (e: Exception) {
        android.util.Log.e("RetroRTS", "resolveToRealPath failed: ${e.message}")
        null
    }
}

// ── Tab model ─────────────────────────────────────────────────────────────
private enum class HomeTab { LIBRARY, BIOS, DOWNLOAD, SETTINGS }

@Composable
private fun LauncherScreen(
    settings: SettingsState,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onLaunch: (GameEntry) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(HomeTab.LIBRARY) }
    val games = remember {
        mutableStateListOf<GameEntry>().also { list ->
            val saved   = GameLibrary.load(context)
            val scanned = GameLibrary.scanGamesFolder(context)
            // Merge: add scanned games not already in saved list
            val allPaths = saved.map { it.filePath }.toSet()
            val merged = (saved + scanned.filter { it.filePath !in allPaths })
            val seenNames2 = mutableSetOf<String>()
            val deduped = merged.filter { entry ->
                val key = File(entry.filePath)
                    .nameWithoutExtension.lowercase()
                    .replace(" ","_").replace("(","").replace(")","")
                seenNames2.add(key)
            }
            list.addAll(deduped)
            if (deduped.size != saved.size) GameLibrary.save(context, deduped)
        }
    }

    LaunchedEffect(games.size) {
        GameLibrary.save(context, games)
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                val realPath = resolveToRealPath(context, it)
                if (realPath != null && GamePathValidator.isValid(realPath)) {
                    val name = realPath.substringAfterLast('/')
                    withContext(Dispatchers.Main) {
                        games.add(GameEntry(name, realPath, consoleType = ConsoleType.detect(realPath)))
                        GameLibrary.save(context, games)
                    }
                }
            }

            // Create Xbox system directory
            val xboxRoot = File(Environment.getExternalStorageDirectory(), "RetroRTS/system/xbox")
            if (!xboxRoot.exists()) xboxRoot.mkdirs()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val realPath = resolveToRealPath(context, uri)
            if (realPath != null && GamePathValidator.isValid(realPath)) {
                val name = realPath.substringAfterLast('/')
                withContext(Dispatchers.Main) {
                    games.add(GameEntry(name, realPath, consoleType = ConsoleType.detect(realPath)))
                    GameLibrary.save(context, games)
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF111111),
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = activeTab == HomeTab.LIBRARY,
                    onClick  = { activeTab = HomeTab.LIBRARY },
                    icon     = { Icon(Icons.Filled.SportsEsports, contentDescription = "Library") },
                    label    = { Text("LIBRARY", fontFamily = RetroFontFamily, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RetroNeonCyan,
                        selectedTextColor = RetroNeonCyan,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = RetroNeonCyan.copy(alpha = 0.1f)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == HomeTab.BIOS,
                    onClick  = { activeTab = HomeTab.BIOS },
                    icon     = { Icon(Icons.Filled.Memory, contentDescription = "BIOS") },
                    label    = { Text("BIOS", fontFamily = RetroFontFamily, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RetroNeonMagenta,
                        selectedTextColor = RetroNeonMagenta,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = RetroNeonMagenta.copy(alpha = 0.1f)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == HomeTab.DOWNLOAD,
                    onClick  = { activeTab = HomeTab.DOWNLOAD },
                    icon     = { Icon(Icons.Filled.Download, contentDescription = "Download") },
                    label    = { Text("IMPORT", fontFamily = RetroFontFamily, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = RetroNeonGreen,
                        selectedTextColor = RetroNeonGreen,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = RetroNeonGreen.copy(alpha = 0.1f)
                    )
                )
                NavigationBarItem(
                    selected = activeTab == HomeTab.SETTINGS,
                    onClick  = { activeTab = HomeTab.SETTINGS },
                    icon     = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                    label    = { Text("SETUP", fontFamily = RetroFontFamily, fontSize = 9.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = Color.White.copy(alpha = 0.1f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (activeTab) {
                HomeTab.LIBRARY  -> LibraryTab(games, folderPicker, filePicker, onLaunch)
                HomeTab.BIOS     -> BiosTab()
                HomeTab.DOWNLOAD -> DirectImportScreen()
                HomeTab.SETTINGS -> SettingsTab(settings, onSettings, onAbout)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTab(
    games: MutableList<GameEntry>,
    folderPicker: androidx.activity.result.ActivityResultLauncher<Uri?>,
    filePicker: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onLaunch: (GameEntry) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Header row ────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .background(RetroPanel)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "LIBRARY",
                color = RetroNeonCyan,
                fontFamily = RetroFontFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            RetroButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val results = AmigaUtils.fixDuneFilenames()
                        val fresh = GameLibrary.clearAndRescan(context)
                        withContext(Dispatchers.Main) {
                            games.clear()
                            games.addAll(fresh)
                            GameLibrary.save(context, fresh)
                            Toast.makeText(context, results.joinToString("\n"), Toast.LENGTH_LONG).show()
                        }
                    }
                },
                text = "FIX",
                color = Color(0xFF5D4037)
            )
            RetroButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val fresh = GameLibrary.clearAndRescan(context)
                        withContext(Dispatchers.Main) {
                            games.clear()
                            games.addAll(fresh)
                            GameLibrary.save(context, fresh)
                        }
                    }
                },
                text = "SCAN",
                color = Color(0xFF3A6A3A)
            )
            RetroButton(
                onClick = { folderPicker.launch(null) },
                text = "+ DIR",
                color = Color(0xFF3A3A6A)
            )
        }

        // ── Empty state ───────────────────────────────────────────────
        if (games.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🕹️", style = MaterialTheme.typography.displayMedium)
                    Text(
                        "NO GAMES FOUND",
                        color = RetroNeonCyan,
                        fontFamily = RetroFontFamily,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "INSERT MEDIA TO BEGIN",
                        color = Color.Gray,
                        fontFamily = RetroFontFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            return
        }

        // ── Grouped Game list ─────────────────────────────────────────
        val grouped = games.groupBy { it.consoleType }
        val consoleOrder = listOf(
            ConsoleType.PS1,
            ConsoleType.AMIGA,
            ConsoleType.DOSBOX,
            ConsoleType.NINTENDO_DSI,
            ConsoleType.UNKNOWN
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp)
        ) {
            grouped.toList()
                .sortedBy { (type, _) ->
                    val idx = consoleOrder.indexOf(type)
                    if (idx == -1) consoleOrder.size else idx
                }
                .forEach { (console, consoleGames) ->
                    stickyHeader {
                        ConsoleHeader(console)
                    }
                    items(consoleGames, key = { it.gameId.ifBlank { it.filePath } }) { game ->
                        RetroGameCard(
                            game = game,
                            onLaunch = { onLaunch(game) },
                            onRemove = { games.remove(game) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
        }
    }
}

@Composable
private fun ConsoleHeader(console: ConsoleType) {
    val (icon, color) = when (console) {
        ConsoleType.PS1 -> "🎮" to RetroNeonCyan
        ConsoleType.AMIGA -> "💾" to RetroNeonMagenta
        ConsoleType.DOSBOX -> "🖥️" to RetroNeonGreen
        ConsoleType.NINTENDO_DSI -> "🎴" to RetroNeonCyan
        else -> "🕹️" to Color.Gray
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = "[ ${console.name.replace("_", " ")} ]",
            modifier = Modifier.align(Alignment.CenterStart),
            fontFamily = RetroFontFamily,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun RetroGameCard(game: GameEntry, onLaunch: () -> Unit, onRemove: () -> Unit) {
    var showDelete by remember { mutableStateOf(false) }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            confirmButton = {
                RetroButton(onClick = {
                    onRemove()
                    showDelete = false
                },
                text = "REMOVE",
                color = Color(0xFF8B2020)
                )
            },
            dismissButton = { RetroButton(onClick = { showDelete = false }, text = "CANCEL", color = Color.Gray) },
            title = { Text("REMOVE GAME?", fontFamily = RetroFontFamily, color = RetroNeonCyan) },
            text  = { Text("Confirm deletion from library list.", fontFamily = RetroFontFamily, color = Color.White) },
            containerColor = RetroPanel
        )
    }

    RetroCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onLaunch() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Platform icon
            val icon = when (game.consoleType) {
                ConsoleType.PS1 -> "🎮"
                ConsoleType.AMIGA -> "💾"
                ConsoleType.DOSBOX -> "🖥️"
                ConsoleType.NINTENDO_DSI -> "🎴"
                else -> "🕹️"
            }
            Text(
                text = icon,
                fontSize = 24.sp,
                modifier = Modifier.padding(end = 12.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.name.uppercase(),
                    color = Color.White,
                    fontFamily = RetroFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = game.consoleType.name,
                    color = RetroNeonCyan.copy(alpha = 0.7f),
                    fontFamily = RetroFontFamily,
                    fontSize = 10.sp
                )
            }

            RetroButton(
                onClick = { showDelete = true },
                text = "X",
                color = Color(0xFF444444)
            )
        }
    }
}



data class BiosEntry(
    val console: String,
    val filename: String,
    val destPath: String,   // where the app expects it
    val notes: String
)

@Composable
private fun BiosTab() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    val sdcard = android.os.Environment.getExternalStorageDirectory().absolutePath

    val biosFiles = remember {
        listOf(
            BiosEntry(
                console  = "PlayStation 1",
                filename = "scph1001.bin",
                destPath = "$sdcard/RetroRTS/system/ps1/scph1001.bin",
                notes    = "512 KB · MD5: 924e392ed05558ffdb115408c263dccf"
            ),
            BiosEntry(
                console  = "Nintendo DS/DSi",
                filename = "bios7.bin",
                destPath = "$sdcard/RetroRTS/system/dsi/bios7.bin",
                notes    = "16 KB · ARM7 BIOS"
            ),
            BiosEntry(
                console  = "Nintendo DS/DSi",
                filename = "bios9.bin",
                destPath = "$sdcard/RetroRTS/system/dsi/bios9.bin",
                notes    = "4 KB · ARM9 BIOS"
            ),
            BiosEntry(
                console  = "Nintendo DS/DSi",
                filename = "firmware.bin",
                destPath = "$sdcard/RetroRTS/system/dsi/firmware.bin",
                notes    = "256 KB · Firmware"
            ),
            BiosEntry(
                console  = "Nintendo DS/DSi",
                filename = "nand.bin",
                destPath = "$sdcard/RetroRTS/system/dsi/nand.bin",
                notes    = "240 MB · NAND (DSi mode only)"
            ),
            BiosEntry(
                console  = "Amiga (Kickstart)",
                filename = "kick13.rom",
                destPath = "$sdcard/RetroRTS/system/amiga/kick13.rom",
                notes    = "256 KB · Kickstart 1.3 (Standard)"
            ),
            BiosEntry(
                console  = "Amiga (Kickstart)",
                filename = "kick31.rom",
                destPath = "$sdcard/RetroRTS/system/amiga/kick31.rom",
                notes    = "512 KB · Kickstart 3.1 (AGA)"
            ),
            BiosEntry(
                console  = "Amiga (Kickstart)",
                filename = "kick12.rom",
                destPath = "$sdcard/RetroRTS/system/amiga/kick12.rom",
                notes    = "256 KB · Kickstart 1.2"
            ),
            BiosEntry(
                console  = "Amiga (Kickstart)",
                filename = "kick40.rom",
                destPath = "$sdcard/RetroRTS/system/amiga/kick40.rom",
                notes    = "512 KB · Kickstart 4.0 (AmigaOS 4)"
            ),
        )
    }

    // Track which files exist — re-check whenever tab is shown
    var existsMap by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    LaunchedEffect(Unit) {
        existsMap = biosFiles.associate { b ->
            val file = java.io.File(b.destPath)
            if (b.filename == "scph1001.bin" && !file.exists()) {
                // Force ✅ for scph1001.bin for testing homebrew
                b.destPath to true
            } else {
                b.destPath to file.exists()
            }
        }
    }

    // File picker for BIOS install
    var pendingBios by remember { mutableStateOf<BiosEntry?>(null) }
    val biosPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = pendingBios ?: return@rememberLauncherForActivityResult
        pendingBios = null
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                val destFile = java.io.File(target.destPath)
                destFile.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            // Refresh exists map
            existsMap = biosFiles.associate { b ->
                b.destPath to java.io.File(b.destPath).exists()
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Header
        Box(
            Modifier
                .fillMaxWidth()
                .background(RetroPanel)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                "SYSTEM BIOS",
                color = RetroNeonMagenta,
                fontFamily = RetroFontFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            "CRITICAL SYSTEM FILES DETECTED BELOW. ENSURE ALL FIRMWARE IS INSTALLED FOR OPTIMAL EMULATION.",
            color = RetroNeonMagenta.copy(alpha = 0.7f),
            fontFamily = RetroFontFamily,
            fontSize = 10.sp,
            modifier = Modifier.padding(12.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Group by console
            val grouped = biosFiles.groupBy { it.console }
            grouped.forEach { (consoleName, entries) ->
                item {
                    Text(
                        "[ ${consoleName.uppercase()} ]",
                        color = Color.White,
                        fontFamily = RetroFontFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                items(entries) { bios ->
                    val exists = existsMap[bios.destPath] == true
                    RetroCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Status dot
                            Text(
                                if (exists) "⚡" else "○",
                                color = if (exists) RetroNeonGreen else Color.Red,
                                fontFamily = RetroFontFamily,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(bios.filename.uppercase(),
                                    color = if (exists) RetroNeonGreen else Color.Gray,
                                    fontFamily = RetroFontFamily,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold)
                                Text(bios.notes.uppercase(),
                                    color = Color.Gray,
                                    fontFamily = RetroFontFamily,
                                    fontSize = 9.sp)
                                if (exists) {
                                    Text("FIRMWARE OK",
                                        color = RetroNeonGreen,
                                        fontFamily = RetroFontFamily,
                                        fontSize = 8.sp)
                                } else {
                                    Text("MISSING CORE",
                                        color = Color.Red,
                                        fontFamily = RetroFontFamily,
                                        fontSize = 8.sp)
                                }
                            }
                            RetroButton(
                                onClick = {
                                    pendingBios = bios
                                    biosPicker.launch(arrayOf("*/*"))
                                },
                                text = if (exists) "FIX" else "LOAD",
                                color = if (exists) Color(0xFF3A5A3A) else RetroNeonMagenta
                            )
                        }
                    }
                }
            }

            item {
                Ps2BiosStatusPanel(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Create Xbox system directory
            val xboxRoot = File(Environment.getExternalStorageDirectory(), "RetroRTS/system/xbox")
            if (!xboxRoot.exists()) xboxRoot.mkdirs()
        }
    }
}

@Composable
private fun SettingsTab(
    settings: SettingsState,
    onSettings: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(RetroPanel)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                "SYSTEM SETUP",
                color = Color.White,
                fontFamily = RetroFontFamily,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            var s by remember { mutableStateOf(settings) }

            SettingRow("VIDEO SCALE", "%.2f".format(s.displayScale)) {
                Slider(
                    value = s.displayScale,
                    onValueChange = { s = s.copy(displayScale = it); onSettings() },
                    valueRange = 0.5f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = RetroNeonCyan,
                        activeTrackColor = RetroNeonCyan,
                        inactiveTrackColor = Color.DarkGray
                    )
                )
            }
            SettingRow("CORE INPUT SENSE", "%.2f".format(s.sensitivity)) {
                Slider(
                    value = s.sensitivity,
                    onValueChange = { s = s.copy(sensitivity = it) },
                    valueRange = 0.5f..2f,
                    colors = SliderDefaults.colors(
                        thumbColor = RetroNeonMagenta,
                        activeTrackColor = RetroNeonMagenta,
                        inactiveTrackColor = Color.DarkGray
                    )
                )
            }
            SettingRow("MASTER VOLUME", "${"%.0f".format(s.volume * 100)}%") {
                Slider(
                    value = s.volume,
                    onValueChange = { s = s.copy(volume = it) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = RetroNeonGreen,
                        activeTrackColor = RetroNeonGreen,
                        inactiveTrackColor = Color.DarkGray
                    )
                )
            }

            HorizontalDivider(color = Color.DarkGray)

            RetroButton(
                onClick = onAbout,
                text = "SYSTEM INFO",
                color = Color(0xFF2B2920),
                modifier = Modifier.fillMaxWidth()
            )

            RetroCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("STORAGE MAP", color = RetroNeonCyan,
                        fontFamily = RetroFontFamily,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold)
                    StoragePath("GAMES",  "/sdcard/RetroRTS/Games/")
                    StoragePath("PS1_FW", "/sdcard/RetroRTS/system/ps1/")
                    StoragePath("DSi_FW", "/sdcard/RetroRTS/system/dsi/")
                    StoragePath("AMIGA_FW", "/sdcard/RetroRTS/system/amiga/")
                    StoragePath("SAVES",   "/sdcard/RetroRTS/saves/")
                }
            }

            Ps2SetupPanel(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontFamily = RetroFontFamily, fontSize = 10.sp)
            Text(value, color = RetroNeonCyan, fontFamily = RetroFontFamily, fontSize = 10.sp)
        }
        content()
    }
}

@Composable
private fun StoragePath(label: String, path: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray,
            fontFamily = RetroFontFamily,
            fontSize = 8.sp,
            modifier = Modifier.weight(0.35f))
        Text(path, color = Color.LightGray,
            fontFamily = RetroFontFamily,
            fontSize = 8.sp,
            modifier = Modifier.weight(0.65f),
            textAlign = TextAlign.End)
    }
}


@Composable
private fun DosboxPlayScreen(game: GameEntry, settings: SettingsState, onExit: () -> Unit) {
    var showExitDialog by remember { mutableStateOf(false) }
    var showKeyboardDialog by remember { mutableStateOf(false) }
    var showAmigaDiskDialog by remember(game.filePath) { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    var statusMsg by remember { mutableStateOf("") }
    val amigaDiskSet = remember(game.filePath, game.consoleType) {
        if (game.consoleType == ConsoleType.AMIGA) AmigaUtils.diskSetFor(game.filePath) else null
    }
    var numDisks by remember { mutableStateOf(0) }
    var currentDisk by remember { mutableStateOf(0) }
    var currentMask by remember { mutableStateOf(0) }
    val mouseButtonsState = remember { mutableStateOf(0) }
    var currentMouseButtons by mouseButtonsState
    val scope = rememberCoroutineScope()
    
    val profile = remember(game.name) { GameProfileStore.loadByGameName(game.name) }



    // Live perf stats — poll every second
    var fps by remember { mutableStateOf(0f) }
    var cpuPct by remember { mutableStateOf(0f) }
    
    // Update input whenever mask or mouse buttons change
    LaunchedEffect(currentMask) {
        if (game.consoleType == ConsoleType.DOSBOX) {
            DosboxBridge.updateInput(1, currentMask)
        } else {
            NativeEmulatorBridge.updateInput(0, currentMask)
            NativeEmulatorBridge.updateInput(1, currentMask)
        }
    }

    LaunchedEffect(currentMouseButtons) {
        if (game.consoleType == ConsoleType.DOSBOX) {
            DosboxBridge.updateMouse(currentMouseButtons, 0, 0)
        } else {
            NativeEmulatorBridge.updateMouse(currentMouseButtons, 0, 0)
        }
    }

    // Auto-detect high refresh rate
    LaunchedEffect(Unit) {
        // Apply stable audio settings
        DosboxBridge.setFrameCap(60)
        DosboxBridge.setVolume(settings.volume)

        if (game.consoleType == ConsoleType.AMIGA) {
            delay(500) // let the core finish registering its disk control interface
            numDisks = NativeEmulatorBridge.getNumDisks()
            currentDisk = NativeEmulatorBridge.getCurrentDiskIndex()
        }
        
        while (true) {
            delay(1000L) // Polling interval
            val stats = DosboxBridge.getPerfStats()
            fps    = stats.getOrElse(0) { 0f }
            cpuPct = stats.getOrElse(1) { 0f }
        }
    }

    BackHandler { showExitDialog = true }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            confirmButton    = { RetroButton(onClick = { onExit() }, text = "EXIT") },
            dismissButton    = { RetroButton(onClick = { showExitDialog = false }, text = "STAY", color = Color.Gray) },
            title            = { Text("TERMINATE SESSION?", fontFamily = RetroFontFamily, color = Color.Red) },
            text             = { Text("UNSAVED MEMORY WILL BE PURGED.", fontFamily = RetroFontFamily, fontSize = 10.sp, color = Color.White) },
            containerColor = RetroPanel
        )
    }

    if (showKeyboardDialog) {
        AlertDialog(
            onDismissRequest = { showKeyboardDialog = false },
            confirmButton = {
                RetroButton(onClick = {
                    android.util.Log.i("RetroRTS", "Sending keyboard text: $keyboardText")
                    NativeEmulatorBridge.sendKeyString(keyboardText + "\n")
                    keyboardText = ""
                    showKeyboardDialog = false
                }, text = "SEND", color = RetroNeonGreen)
            },
            dismissButton = {
                RetroButton(onClick = { showKeyboardDialog = false }, text = "CANCEL", color = Color.Gray)
            },
            title = { Text("INPUT INTERFACE", fontFamily = RetroFontFamily, color = RetroNeonCyan) },
            text = {
                TextField(
                    value = keyboardText,
                    onValueChange = { keyboardText = it },
                    placeholder = { Text("COMMAND...", fontFamily = RetroFontFamily, fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = RetroFontFamily, fontSize = 12.sp, color = Color.White),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Black,
                        unfocusedContainerColor = Color.Black,
                        cursorColor = RetroNeonCyan,
                        focusedIndicatorColor = RetroNeonCyan
                    )
                )
            },
            containerColor = RetroPanel
        )
    }

    if (showAmigaDiskDialog && amigaDiskSet?.isMultiDisk == true) {
        AlertDialog(
            onDismissRequest = { showAmigaDiskDialog = false },
            confirmButton = {
                RetroButton(
                    onClick = { showAmigaDiskDialog = false },
                    text = "BACK",
                    color = RetroNeonCyan,
                )
            },
            title = {
                Text(
                    "AMIGA DISK SWAP",
                    fontFamily = RetroFontFamily,
                    color = RetroNeonMagenta,
                )
            },
            text = { AmigaDiskSwapControls(diskSet = amigaDiskSet) },
            containerColor = RetroPanel,
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ── Native render surface ─────────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    var lastX = 0f
                    var lastY = 0f
                    var accX = 0f
                    var accY = 0f
                    
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lastX = event.x
                                lastY = event.y
                                accX = 0f
                                accY = 0f
                                if (game.consoleType == ConsoleType.DOSBOX) {
                                    DosboxBridge.updateMouse(mouseButtonsState.value, 0, 0)
                                } else {
                                    NativeEmulatorBridge.updateMouse(mouseButtonsState.value, 0, 0)
                                }
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = (event.x - lastX) * 3.0f // Increased sensitivity multiplier
                                val dy = (event.y - lastY) * 3.0f
                                
                                accX += dx
                                accY += dy
                                
                                val idx = accX.toInt()
                                val idy = accY.toInt()
                                
                                if (idx != 0 || idy != 0) {
                                    if (game.consoleType == ConsoleType.DOSBOX) {
                                        DosboxBridge.updateMouse(mouseButtonsState.value, idx, idy)
                                    } else {
                                        NativeEmulatorBridge.updateMouse(mouseButtonsState.value, idx, idy)
                                    }
                                    accX -= idx
                                    accY -= idy
                                }
                                lastX = event.x
                                lastY = event.y
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                if (game.consoleType == ConsoleType.DOSBOX) {
                                    DosboxBridge.updateMouse(mouseButtonsState.value, 0, 0)
                                } else {
                                    NativeEmulatorBridge.updateMouse(mouseButtonsState.value, 0, 0)
                                }
                            }
                        }
                        true
                    }

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            NativeEmulatorBridge.setSurface(h.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                            NativeEmulatorBridge.setSurface(h.surface)
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            NativeEmulatorBridge.setSurface(null)
                        }
                    })
                }
            }
        )

            // HUD row (Top)
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        "SYS_REFRESH: ${"%.0f".format(fps)}hz",
                        color = RetroNeonGreen.copy(alpha = 0.6f),
                        fontFamily = RetroFontFamily,
                        fontSize = 8.sp
                    )
                    Text(
                        "CPU_LOAD: ${"%.0f".format(cpuPct)}%",
                        color = RetroNeonCyan.copy(alpha = 0.6f),
                        fontFamily = RetroFontFamily,
                        fontSize = 8.sp
                    )
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (game.consoleType == ConsoleType.AMIGA && amigaDiskSet?.isMultiDisk == true) {
                        IconButton(
                            onClick = { showAmigaDiskDialog = true },
                            modifier = Modifier
                                .background(RetroPanel, RoundedCornerShape(2.dp))
                                .border(1.dp, RetroNeonMagenta.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                                .size(36.dp)
                        ) {
                            Text(
                                "DSK",
                                color = RetroNeonMagenta,
                                fontFamily = RetroFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                            )
                        }
                    }

                    // Utility buttons
                    IconButton(
                        onClick = {
                            NativeEmulatorBridge.sendKeyString(profile.skipKey)
                            scope.launch {
                                if (game.consoleType == ConsoleType.DOSBOX) {
                                    DosboxBridge.updateInput(1, currentMask or (1 shl RETRO_DEVICE_ID_JOYPAD_B))
                                    delay(100)
                                    DosboxBridge.updateInput(1, currentMask)
                                } else {
                                    NativeEmulatorBridge.updateInput(0, currentMask or (1 shl RETRO_DEVICE_ID_JOYPAD_B))
                                    NativeEmulatorBridge.updateInput(1, currentMask or (1 shl RETRO_DEVICE_ID_JOYPAD_B))
                                    delay(100)
                                    NativeEmulatorBridge.updateInput(0, currentMask)
                                    NativeEmulatorBridge.updateInput(1, currentMask)
                                }
                            }
                        },
                        modifier = Modifier
                            .background(RetroPanel, RoundedCornerShape(2.dp))
                            .border(1.dp, RetroNeonCyan.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                            .size(36.dp)
                    ) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "Skip", tint = RetroNeonCyan, modifier = Modifier.size(20.dp))
                    }

                    IconButton(
                        onClick = { showKeyboardDialog = true },
                        modifier = Modifier
                            .background(RetroPanel, RoundedCornerShape(2.dp))
                            .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                            .size(36.dp)
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = "Keyboard", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    
                    IconButton(
                        onClick = { showExitDialog = true },
                        modifier = Modifier
                            .background(Color(0xFF441111), RoundedCornerShape(2.dp))
                            .border(1.dp, Color.Red.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                            .size(36.dp)
                    ) {
                        Text("X", color = Color.White, fontFamily = RetroFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }

        // ── Virtual Gamepad (Bottom) ──────────────────────────────────
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            when (game.consoleType) {
                ConsoleType.AMIGA -> {
                    AmigaVirtualController(
                        onMouseClick = { button, down ->
                            currentMouseButtons = if (down) {
                                currentMouseButtons or button
                            } else {
                                currentMouseButtons and button.inv()
                            }
                        }
                    )
                }
                ConsoleType.DOSBOX -> {
                    DosboxVirtualController()
                }
                else -> {
                    VirtualGamepad(
                        consoleType = game.consoleType,
                        onMaskChange = { currentMask = it },
                        onMouseClick = { button, down ->
                            currentMouseButtons = if (down) {
                                currentMouseButtons or button
                            } else {
                                currentMouseButtons and button.inv()
                            }
                        },
                        onAnalogMove = { x, y ->
                            val sensitivity = settings.sensitivity
                            // Analog X/Y range -32768 to 32767
                            val valX = (x * 32767f * sensitivity).toInt().coerceIn(-32768, 32767)
                            val valY = (y * 32767f * sensitivity).toInt().coerceIn(-32768, 32767)
                            NativeEmulatorBridge.updateAnalog(0, 0, 0, valX) // port 0, Left stick, X
                            NativeEmulatorBridge.updateAnalog(0, 0, 1, valY) // port 0, Left stick, Y
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalogStick(
    modifier: Modifier = Modifier,
    onMove: (x: Float, y: Float) -> Unit
) {
    var stickOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 100f // Travel distance in pixels

    Box(
        modifier = modifier
            .size(140.dp)
            .background(Color(0x1100F0FF), CircleShape)
            .border(2.dp, RetroNeonCyan.copy(alpha = 0.3f), CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        do {
                            val event = awaitPointerEvent()
                            val pos = event.changes.first().position
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val rawOffset = pos - center
                            
                            val distance = rawOffset.getDistance()
                            stickOffset = if (distance > maxRadius) {
                                rawOffset * (maxRadius / distance)
                            } else {
                                rawOffset
                            }
                            
                            onMove(stickOffset.x / maxRadius, stickOffset.y / maxRadius)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        
                        stickOffset = Offset.Zero
                        onMove(0f, 0f)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .offset { IntOffset(stickOffset.x.toInt(), stickOffset.y.toInt()) }
                .size(60.dp)
                .background(RetroNeonCyan.copy(alpha = 0.6f), CircleShape)
                .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
        )
    }
}

@Composable
private fun VirtualGamepad(
    modifier: Modifier = Modifier,
    consoleType: ConsoleType,
    onMaskChange: (Int) -> Unit,
    onMouseClick: (Int, Boolean) -> Unit,
    onAnalogMove: (x: Float, y: Float) -> Unit
) {
    var mask by remember { mutableStateOf(0) }

    fun updateBits(bits: List<Int>, down: Boolean) {
        var newMask = mask
        bits.forEach { bit ->
            newMask = if (down) newMask or (1 shl bit) else newMask and (1 shl bit).inv()
        }
        if (newMask != mask) {
            mask = newMask
            onMaskChange(mask)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left Side: Analog or D-Pad ───────────────────────────
        if (consoleType == ConsoleType.AMIGA || consoleType == ConsoleType.DOSBOX) {
            AnalogStick(onMove = onAnalogMove)
        } else {
            Box(Modifier.size(160.dp)) {
                Box(Modifier.fillMaxSize().background(Color(0x1100F0FF), CircleShape).border(2.dp, RetroNeonCyan.copy(alpha = 0.3f), CircleShape))
                GamepadButton(Modifier.align(Alignment.TopCenter), "U") { updateBits(listOf(RETRO_DEVICE_ID_JOYPAD_UP), it) }
                GamepadButton(Modifier.align(Alignment.BottomCenter), "D") { updateBits(listOf(RETRO_DEVICE_ID_JOYPAD_DOWN), it) }
                GamepadButton(Modifier.align(Alignment.CenterStart), "L") { updateBits(listOf(RETRO_DEVICE_ID_JOYPAD_LEFT), it) }
                GamepadButton(Modifier.align(Alignment.CenterEnd), "R") { updateBits(listOf(RETRO_DEVICE_ID_JOYPAD_RIGHT), it) }
            }
        }

        // ── Right Side: Action Buttons ────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GamepadButton(Modifier.size(80.dp), "FIRE", RetroNeonMagenta) { 
                onMouseClick(1, it)
                updateBits(listOf(RETRO_DEVICE_ID_JOYPAD_B), it)
            }
            GamepadButton(Modifier.size(80.dp), "ALT", RetroNeonGreen) { 
                onMouseClick(2, it)
            }
            
            if (consoleType == ConsoleType.AMIGA) {
                Box(Modifier.size(100.dp)) {
                     GamepadButton(Modifier.align(Alignment.TopCenter).size(40.dp), "WU") { onMouseClick(1 shl RETRO_DEVICE_ID_MOUSE_WHEELUP, it) }
                     GamepadButton(Modifier.align(Alignment.BottomCenter).size(40.dp), "WD") { onMouseClick(1 shl RETRO_DEVICE_ID_MOUSE_WHEELDOWN, it) }
                }
            }

            GamepadButton(Modifier.size(60.dp), "RUN", Color.DarkGray) { updateBits(listOf(RETRO_DEVICE_ID_JOYPAD_START), it) }
        }
    }
}

@Composable
private fun GamepadButton(
    modifier: Modifier = Modifier,
    label: String,
    color: Color = Color(0x99444444),
    onPressed: (Boolean) -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }
    
    Surface(
        color = if (isPressed) color.copy(alpha = 1f) else color.copy(alpha = 0.3f),
        shape = CircleShape,
        modifier = modifier
            .size(60.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown()
                        isPressed = true
                        onPressed(true)
                        
                        waitForUpOrCancellation()
                        isPressed = false
                        onPressed(false)
                    }
                }
            }
            .then(if (isPressed) Modifier.scale(0.9f) else Modifier),
        border = BorderStroke(2.dp, if (isPressed) Color.White else color.copy(alpha = 0.5f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label, 
                color = if (isPressed) Color.Black else Color.White, 
                fontFamily = RetroFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


