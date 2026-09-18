# libsu / RootService (root flavor)
-keep class com.topjohnwu.superuser.** { *; }

# Shizuku (sui flavor)
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.api.** { *; }

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# AIDL + privileged backends
-keep class com.volumemanager.app.IVolumePrivilegedService { *; }
-keep class com.volumemanager.app.IVolumePrivilegedService$Stub { *; }
-keep class com.volumemanager.app.root.** { *; }
-keep class com.volumemanager.app.privileged.** { *; }
-keep class com.volumemanager.app.shizuku.** { *; }

# Hidden audio reflection
-keepclassmembers class * {
    @android.annotation.Keep *;
}
