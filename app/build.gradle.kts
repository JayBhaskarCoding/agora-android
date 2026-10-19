import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10"
    id("com.google.gms.google-services")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.isFile) {
        localPropertiesFile.inputStream().use(::load)
    }
}

/**
 * Resolves a local-only credential. CI can supply the same property with -P<PropertyName>.
 */
fun requiredLocalProperty(name: String): String =
    (localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull)
        ?.takeIf { it.isNotBlank() }
        ?: throw GradleException(
            "Missing $name. Add it to local.properties (see local.properties.example) " +
                "or provide it as a Gradle project property."
        )

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    // ✦ NAMESPACE stays com.example.agora on purpose: it is the Kotlin/Java
    //   package and the R class package, so the production applicationId can
    //   change without moving a single source file. R8 keep rules must
    //   therefore reference the NAMESPACE (com.example.agora.*), never the
    //   applicationId.
    namespace = "com.example.agora"

    // Compile against the newest stable tooling; what Play validates is
    // targetSdk, which is pinned to stable API 36 below.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // ✦ Play Store identity. Changing this from com.example.agora makes
        //   net.in.agora a BRAND-NEW app for Play/Android: existing installs of
        //   the old id cannot be upgraded in place.
        applicationId = "net.in.agora"
        minSdk = 26

        // ✅ Google Play 2026 policy: new apps must target a STABLE API level.
        //    API 36 = Android 16. Targeting a preview SDK (e.g. 37) is rejected
        //    at upload time, so this must stay on the stable level even though
        //    compileSdk can be newer.
        targetSdk = 36

        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The anon key is expected to be public in a mobile app, but keeping it out of
        // source control makes endpoint rotation and environment separation deliberate.
        buildConfigField(
            "String",
            "SUPABASE_URL",
            requiredLocalProperty("SUPABASE_URL").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            requiredLocalProperty("SUPABASE_ANON_KEY").asBuildConfigString()
        )
    }

    buildTypes {
        release {
            // ✦ Never ship a debuggable artifact.
            isDebuggable = false

            // ✦ R8: shrink, obfuscate and optimize the release build. The
            //   keep rules that protect kotlinx.serialization models,
            //   Supabase/Ktor, Credential Manager, Google ID and Firebase
            //   live in app/proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            // Explicit (and the ADT default) — guards against a future
            // refactor accidentally inheriting release configuration.
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Compose compiler stability metrics & recomposition reports (opt-in):
//   ./gradlew :app:assembleDebug -PenableComposeCompilerMetrics=true
// Output lands in app/build/compose_compiler/ (look for:
//   - *-composables.txt        -> skippability/restartability per composable
//   - *-module.json / .csv     -> unstable classes & reasons)
composeCompiler {
    if (providers.gradleProperty("enableComposeCompilerMetrics").orNull == "true") {
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation.graphics)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.postgrest.kt)
    implementation(libs.ktor.client.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.auth.kt)
    implementation(libs.multiplatform.settings)
    implementation(libs.realtime.kt)
    implementation(libs.storage.kt)
    implementation(libs.coil.compose)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.haze)
    implementation(libs.blurview)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.coil.video)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.okhttp)
}
