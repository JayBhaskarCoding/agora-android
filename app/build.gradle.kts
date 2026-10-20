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

/**
 * Like [requiredLocalProperty] but returns null instead of failing the build.
 * Used for values that have a sane, documented fallback.
 */
fun optionalLocalProperty(name: String): String? =
    (localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull)
        ?.takeIf { it.isNotBlank() }

/**
 * ✦ GOOGLE WEB CLIENT ID — the `serverClientId` handed to
 *   `GetGoogleIdOption` / `GetSignInWithGoogleOption`.
 *
 * Hard requirements (Google enforces all three):
 *   1. It must be an OAuth 2.0 client of type **Web application**. An
 *      Android-type client here produces
 *      `GetCredentialCancellationException: activity is cancelled by the user`
 *      the instant the account picker closes.
 *   2. It must live in the SAME Google Cloud project as `app/google-services.json`
 *      (project number 1025610780303). A client id's numeric prefix IS its
 *      project number, so a mismatch is visible in the string itself.
 *   3. The SAME project must also contain an **Android** OAuth client for
 *      `applicationId` + the SHA-1 of the certificate that signed the installed
 *      APK (debug keystore, upload key and Play App Signing key are three
 *      different certificates).
 *
 * Override without touching source: put `GOOGLE_WEB_CLIENT_ID=…` in
 * local.properties, or pass `-PGOOGLE_WEB_CLIENT_ID=…`.
 */
val defaultGoogleWebClientId =
    "925021938686-kqbu0iv5q4gkkoq1sqbnmrtk1uj83itk.apps.googleusercontent.com"

android {
    // ✦ NAMESPACE = the Kotlin/Java package, the R + BuildConfig package and
    //   the base for every relative class name in AndroidManifest.xml
    //   (".MainActivity" -> net.in.agora.MainActivity).
    //
    //   It now matches `applicationId` exactly. Sources live under
    //   app/src/*/java/net/in/agora/** and every declaration reads
    //   `package net.in.agora…`; R8 keep rules in proguard-rules.pro use the
    //   same prefix. The two must be changed together — changing only this
    //   line leaves R and BuildConfig unreachable and every relative manifest
    //   component pointing at a class that does not exist.
    namespace = "net.in.agora"

    // Compile against the newest stable tooling; what Play validates is
    // targetSdk, which is pinned to stable API 36 below.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // ✦ Play Store identity, and the only name the device sees.
        //   Install/upgrade continuity belongs to THIS value alone: installs of
        //   the old com.example.agora build cannot be upgraded in place and
        //   must be uninstalled (they were never publishable anyway — see
        //   docs/engineering-notes.md §9).
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

        // 🌟 Google Sign-In server client id. One source of truth: the UI no
        //    longer passes a literal, and the ViewModel default reads this.
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            (optionalLocalProperty("GOOGLE_WEB_CLIENT_ID") ?: defaultGoogleWebClientId)
                .asBuildConfigString()
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
