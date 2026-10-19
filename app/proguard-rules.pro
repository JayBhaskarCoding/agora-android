# ═══════════════════════════════════════════════════════════════════════
# ✦ AGORA — R8 / ProGuard rules for the release build
#
# Wired up in app/build.gradle.kts:
#   proguardFiles(
#       getDefaultProguardFile("proguard-android-optimize.txt"),
#       "proguard-rules.pro"
#   )
#
# ⚠️  Keep rules reference the Kotlin NAMESPACE (com.example.agora), NOT the
#     production applicationId (net.in.agora). R8 matches source-level names,
#     and the namespace deliberately stayed behind when the app id changed.
# ═══════════════════════════════════════════════════════════════════════

# --- Metadata that reflection / serializers rely on ---------------------
# Signature  -> generic parameter types (HttpClient.body<T>, List<Post>)
# Annotations -> @Serializable / @SerialName visibility for the plugin output
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes Exceptions

# ---------------------------------------------------------------------
# 1. kotlinx.serialization
# ---------------------------------------------------------------------
# The compiler plugin generates a `$$serializer` object plus a `serializer()`
# lookup that R8 cannot trace statically. Without these keeps a release build
# compiles fine and then throws at runtime:
#   SerializationException: Serializer for class 'Profile' is not found

# Serializer classes generated for every @Serializable class in the app
# (model/, viewmodel/ request bodies, utils/DisifyResponse).
-keep,includedescriptorclasses class com.example.agora.**$$serializer { *; }

# The wire models are the contract with Supabase Postgrest/Auth and Ktor.
# Keep the package wholesale (8 small data classes) so field names, defaults
# and @SerialName mappings survive obfuscation.
-keep class com.example.agora.model.** { *; }

# Safety net for @Serializable classes declared outside model/ (request DTOs
# in viewmodels, third-party response DTOs in utils). Obfuscation is allowed
# because serializers are resolved by class, not by name.
-keep,allowobfuscation @kotlinx.serialization.Serializable class com.example.agora.** { *; }

# Keep the `Companion` object and its `serializer()` for every @Serializable
# class, including library ones. This is the canonical kotlinx rule set.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable object singletons expose INSTANCE.serializer().
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# `descriptor` is read reflectively when a serializer is looked up.
-keepclassmembers class **$$serializer {
    *** descriptor;
}

# Custom @SerialName / @SerialInfo mappings on fields.
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}

-keep class kotlinx.serialization.json.** { *; }
-dontnote kotlinx.serialization.**

# ---------------------------------------------------------------------
# 2. Supabase (supabase-kt) + Ktor
# ---------------------------------------------------------------------
# postgrest-kt / auth-kt / storage-kt deserialize rows and sessions through
# generated serializers, and Ktor resolves plugins and engines by class name.
-keep class io.github.jan.supabase.** { *; }
-keep interface io.github.jan.supabase.** { *; }
-keep enum io.github.jan.supabase.** { *; }

-keep class io.ktor.** { *; }
-keep interface io.ktor.** { *; }
-keep enum io.ktor.** { *; }

# Ktor engines and content converters are resolved by name at runtime.
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.serialization.** { *; }

# Ktor/OkHttp optionally reference JVM-only APIs that Android does not ship.
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# ---------------------------------------------------------------------
# 3. Credential Manager + Google ID (Sign in with Google)
# ---------------------------------------------------------------------
# The response objects cross a process boundary and are rebuilt reflectively
# from Bundles, so both the classes and their Parcelable CREATORs must stay.
-keep class androidx.credentials.** { *; }
-keep interface androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# ---------------------------------------------------------------------
# 4. Firebase (Cloud Messaging)
# ---------------------------------------------------------------------
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Every FirebaseMessagingService subclass is instantiated by FCM through its
# class name; the service and its members must not be renamed or removed.
-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { *; }

# ---------------------------------------------------------------------
# 5. App entry points (belt and braces — AAPT also emits keep rules)
# ---------------------------------------------------------------------
-keep class com.example.agora.AgoraApplication { <init>(...); }
-keep class com.example.agora.MainActivity { <init>(...); }
-keep class com.example.agora.service.** { <init>(...); }

# ---------------------------------------------------------------------
# 6. General Android / Kotlin safety nets
# ---------------------------------------------------------------------
# Enum valueOf()/values() are invoked reflectively by generated code.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable CREATOR fields (Credential Manager, Media3, saved state).
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

-dontwarn kotlin.**
-dontwarn kotlinx.**

# Keep source file + line numbers so Play Console stack traces map back to
# real frames (Play uploads the mapping file automatically).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
