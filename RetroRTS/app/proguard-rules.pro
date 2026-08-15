# Keep JNI bridge classes — R8 must not rename or strip these
-keep class com.retrorts.ui.DosboxBridge {
    native <methods>;
}
-keepclassmembers class com.retrorts.ui.DosboxBridge$Companion {
    native <methods>;
}

# Native audio fallback — JNI resolves this class by name
-keep class com.retrorts.ui.NativeAudioFallback { *; }

# Native Emulator Bridge
-keep class com.retrorts.ui.NativeEmulatorBridge {
    native <methods>;
    *** launchGame(...);
    *** setSurface(...);
}

# LaunchResult data class
-keep class com.retrorts.ui.NativeEmulatorBridge$LaunchResult { *; }

# ConsoleType enum used by JNI string matching
-keepclassmembers enum com.retrorts.ui.ConsoleType { *; }

# Keep GameProfile JSON serialisation (uses org.json reflection)
-keepclassmembers class com.retrorts.ui.GameProfile {
    public <init>(...);
    public *** toJson();
    public static *** fromJson(...);
}

# Keep PerfStats data class
-keep class com.retrorts.ui.PerfStats { *; }
