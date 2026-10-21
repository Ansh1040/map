# Drive Alert release shrinking rules.

# kotlinx.serialization: keep generated serializers for network DTOs.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.drivealert.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.drivealert.app.**$$serializer { *; }

# Retrofit service interfaces are accessed reflectively.
-keep,allowobfuscation interface com.drivealert.app.network.CameraDataApi
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Strip verbose/debug logging from release builds (no coordinates in production logs).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
