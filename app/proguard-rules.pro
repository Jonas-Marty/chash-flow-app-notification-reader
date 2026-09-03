# kotlinx.serialization keeps its generated serializers via @Serializable classes.
-keepclassmembers class ch.marty.finreader.** {
    *** Companion;
}
-keepclasseswithmembers class ch.marty.finreader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ch.marty.finreader.**$$serializer { *; }

# Room generated implementations.
-keep class ch.marty.finreader.data.db.** { *; }

# OkHttp / Okio platform lookups.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (via androidx.security.crypto) references compile-only annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# A personal, sideloaded app is debugged from the crash log in Settings ->
# Diagnostics. Shrinking is worth keeping; renaming everything to z5.c is not.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
