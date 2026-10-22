package net.agora.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import net.agora.app.data.NotificationRepository
import net.agora.app.data.ThemePreferenceStore
import net.agora.app.data.supabaseClient
import net.agora.app.model.FcmTokenUpdate
import net.agora.app.navigation.AuthCallbackGate
import net.agora.app.navigation.DeepLinkRouter
import net.agora.app.navigation.NavTransitions
import net.agora.app.utils.SessionContinuity
import net.agora.app.service.PushNotificationService
import net.agora.app.ui.InAppNotificationManager
import net.agora.app.ui.LoginScreen
import net.agora.app.ui.MainScreen
import net.agora.app.ui.OnboardingFlowScreen
import net.agora.app.ui.PasswordResetScreen
import net.agora.app.ui.RegisterScreen
import net.agora.app.ui.theme.AgoraTheme
import net.agora.app.viewmodel.AuthNavTarget
import net.agora.app.viewmodel.AuthViewModel
import net.agora.app.viewmodel.ThemeViewModel
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Best-effort FCM registration: syncs the device token into `profiles.fcm_token`
 * and subscribes to the `new_posts` topic.
 *
 * 🌟 PLAY-SERVICES RESILIENCY: on devices/emulators without Google Play
 *    Services, FirebaseMessaging throws (e.g. MISSING_INSTANCEID_SERVICE).
 *    FCM is treated as strictly optional here — every failure is logged and
 *    swallowed so authentication and app initialization never crash. The
 *    foreground in-app notification experience no longer depends on this
 *    function at all: it is delivered independently via Supabase Realtime
 *    (see [net.agora.app.data.NotificationRepository]).
 */
fun syncFcmTokenAndSubscribeTopics() {
    val messaging = try {
        FirebaseMessaging.getInstance()
    } catch (e: Exception) {
        Log.w(
            "FCM",
            "Firebase Messaging unavailable on this device (Google Play Services missing?). " +
                "Skipping FCM sync — in-app notifications continue via Supabase Realtime. " +
                "Cause: ${e.message}"
        )
        return
    }

    try {
        messaging.token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                // Expected without Google Play Services: MISSING_INSTANCEID_SERVICE.
                // Graceful log only — the auth/init flow continues untouched.
                Log.w(
                    "FCM",
                    "Fetching FCM registration token failed (device likely lacks Google Play " +
                        "Services): ${task.exception?.message}",
                    task.exception
                )
                return@addOnCompleteListener
            }
            val token = task.result ?: return@addOnCompleteListener
            val currentUser = supabaseClient.auth.currentUserOrNull() ?: return@addOnCompleteListener


            CoroutineScope(Dispatchers.IO).launch {
                try {
                    supabaseClient.from("profiles").update(
                        FcmTokenUpdate(fcmToken = token)
                    ) {
                        filter { eq("id", currentUser.id) }
                    }
                    Log.d("FCM", "Successfully updated FCM Token in Supabase for user: ${currentUser.id}")
                } catch (e: Exception) {
                    Log.e("FCM", "Failed to update FCM token in Supabase: ${e.localizedMessage}", e)
                }
            }
        }

        messaging.subscribeToTopic("new_posts").addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d("FCM", "Successfully subscribed to topic: new_posts")
            } else {
                Log.w(
                    "FCM",
                    "Failed to subscribe to topic: new_posts (device likely lacks Google Play " +
                        "Services): ${task.exception?.message}"
                )
            }
        }
    } catch (e: Exception) {
        Log.w(
            "FCM",
            "FCM sync aborted gracefully — realtime in-app notifications are unaffected. " +
                "Cause: ${e.message}"
        )
    }
}

class MainActivity : ComponentActivity() {

    companion object {
        /** Logcat tag for every deep-link decision. `adb logcat -s DeepLink`. */
        private const val TAG_DEEP_LINK = "DeepLink"

        /** App canvas in dark mode (#0B0C13) — matches NoirCanvasDark. */
        private const val WINDOW_BACKGROUND_DARK = 0xFF0B0C13.toInt()

        /** App canvas in light mode (#F8F9FA) — matches NoirCanvasLight. */
        private const val WINDOW_BACKGROUND_LIGHT = 0xFFF8F9FA.toInt()
    }

    /**
     * Splash is released as soon as the auth session resolves (see the
     * LaunchedEffect in [setContent]) instead of for a fixed delay — this used to
     * add a hard 800ms to every cold start.
     */
    @Volatile
    private var keepSplashOnScreen = true

    // 🌟 INTENT INJECTION: Extract FCM notification extras / deep link URI, publish
    //    them to the DeepLinkRouter (single source of truth for post navigation) and
    //    normalise intent.data to the `agora://post/…` form the NavHost grammar
    //    understands.
    //
    //    Everything is logged under the `DeepLink` tag so a field report can be
    //    diagnosed with `adb logcat -s DeepLink` alone:
    //
    //      D DeepLink: launch action=android.intent.action.VIEW data=https://agora.in.net/post/0c29… flags=0x10000000
    //      D DeepLink: parsed postId=0c298d8b-… commentId=null -> agora://post/0c298d8b-…
    //
    //    A post link that produces NO "parsed" line is not a routing failure —
    //    the intent never matched the manifest filter and Android handed the URL
    //    to the browser instead (App Links verification, see §11 in the docs).
    private fun injectDeepLink(intent: Intent?) {
        if (intent == null) return

        val rawData = intent.dataString
        Log.d(
            TAG_DEEP_LINK,
            "launch action=${intent.action} data=$rawData " +
                "extras=${intent.extras?.keySet()?.joinToString(",")} flags=0x${Integer.toHexString(intent.flags)}"
        )

        val parsed = DeepLinkRouter.parse(intent)
        if (parsed == null) {
            if (!rawData.isNullOrBlank()) {
                Log.d(TAG_DEEP_LINK, "not a post link (left untouched): $rawData")
            }
            return
        }
        val postId = parsed.postId
        val commentId = parsed.commentId

        // Normalise to the form the NavHost deep-link grammar declares, so a
        // `https://` App Link and an `agora://` notification converge on one URI.
        // toRoute() is "post/{id}?commentId=…"; deep links need a scheme.
        val uriString = "agora://" + parsed.toRoute()
        Log.d(TAG_DEEP_LINK, "parsed postId=$postId commentId=$commentId -> $uriString")

        intent.data = Uri.parse(uriString)
        intent.action = Intent.ACTION_VIEW

        // MainScreen observes this and navigates once the NavHost exists
        // (works for cold starts, warm onNewIntent and foreground banner taps).
        DeepLinkRouter.submit(postId, commentId)

        intent.removeExtra("post_id")
        intent.removeExtra("comment_id")
    }

    /**
     * ✦ App Links can fail silently, and when they do the URL opens in the
     *   browser with no trace in logcat. This dumps Android's verification
     *   verdict for `agora.in.net` once per process (debug builds only):
     *
     *     adb logcat -s DeepLink
     *     D DeepLink: app-links allowed=true hosts=[agora.in.net=verified]
     *
     *   `unverified` / `approved-by-user` means assetlinks.json is missing,
     *   stale or signed with a different certificate than the installed APK —
     *   `approved-by-user` additionally means the user picked the app by hand.
     *
     *   The state comes from [DomainVerificationManager] (API 31+), NOT from
     *   PackageManager: `PackageManager.getDomainVerificationUserState` is not
     *   public API, which is why this used to fail to compile.
     */
    private fun logAppLinkVerification() {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d(TAG_DEEP_LINK, "app-links verification state requires API 31+")
            return
        }
        logAppLinkVerificationS()
    }

    /** API 31+ body, split out so the version guard satisfies lint's NewApi. */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun logAppLinkVerificationS() {
        try {
            val manager = getSystemService(DomainVerificationManager::class.java)
            if (manager == null) {
                Log.d(TAG_DEEP_LINK, "app-links DomainVerificationManager unavailable")
                return
            }
            // Returns null when the package declares no http(s) domains at all —
            // i.e. the manifest lost its App Links filter.
            val state = manager.getDomainVerificationUserState(packageName)
            if (state == null) {
                Log.d(TAG_DEEP_LINK, "app-links no domain verification state for $packageName")
                return
            }
            val hosts = state.hostToStateMap.entries.joinToString(", ") { (host, value) ->
                "$host=${domainVerificationStateName(value)}"
            }
            Log.d(
                TAG_DEEP_LINK,
                "app-links allowed=${state.isLinkHandlingAllowed} hosts=[$hosts]"
            )
        } catch (e: Exception) {
            // PackageManager.NameNotFoundException, or an OEM build without the
            // service. Diagnostics must never be able to crash the launch path.
            Log.d(TAG_DEEP_LINK, "app-links verification lookup failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun domainVerificationStateName(state: Int): String = when (state) {
        DomainVerificationUserState.DOMAIN_STATE_NONE -> "unverified"
        DomainVerificationUserState.DOMAIN_STATE_SELECTED -> "approved-by-user"
        DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> "verified"
        else -> "state($state)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        /* ✦ GHOST-INSTANCE GUARD — must stay the FIRST statement in onCreate,
         * before installSplashScreen/enableEdgeToEdge/super.
         *
         * Some launchers fire a fresh MAIN/LAUNCHER intent at an app that is
         * already alive in the background, stacking a SECOND MainActivity on
         * top of the running task. The user "returns" to that brand-new ghost
         * instance — a fresh composition, a fresh NavController, startDestination
         * "feed" — and every screen they were on is gone. With
         * launchMode="singleTop" the OS resumes the existing instance instead,
         * and this check finishes any duplicate that still slips through before
         * it initializes anything. Deep-link intents (ACTION_VIEW) are NOT
         * launcher intents, so they fall through and route normally. */
        if (!isTaskRoot) {
            val intentAction = intent.action
            if (intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intentAction != null && intentAction == Intent.ACTION_MAIN) {
                finish()
                return
            }
        }

        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }

        // Fail-safe: never hold the splash screen longer than this, even if auth stalls.
        lifecycleScope.launch {
            delay(1500)
            keepSplashOnScreen = false
        }

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        createNotificationChannel()

        // 🌟 Publish any notification deep link before setContent so the NavHost and
        //    the DeepLinkRouter both see it (whichever navigates first wins; the other
        //    detects the duplicate and stands down).
        //
        //    ✦ Fresh starts only. On a configuration change the OS replays the same
        //    ACTION_VIEW intent; re-injecting it re-submits a link the NavController
        //    has already restored, which can stack a second copy of the post screen.
        if (savedInstanceState == null) {
            injectDeepLink(intent)
            logAppLinkVerification()
        }

        /* ★ Arm the auth-callback gate BEFORE the intent reaches Supabase. A magic
         *   link or email-OTP link creates the session inside handleDeeplinks —
         *   that is, before AuthViewModel exists (it is built by setContent below).
         *   The gate is sticky, so the ViewModel still sees it in its init, which is
         *   what lets it tell "session just created by a link" apart from "session
         *   restored from storage at cold start". Only fresh starts: on a
         *   recreation the same intent is replayed and the link was already used. */
        if (savedInstanceState == null) {
            AuthCallbackGate.notify(intent)
        }

        intent?.let {
            try {
                supabaseClient.handleDeeplinks(it)
            } catch (_: Exception) {}
        }

        // 🌟 FIRST-FRAME THEME: resolve the effective theme BEFORE the first frame
        //    so the window background behind Compose already matches the canvas
        //    the user is about to see. Without this, a Light-mode launch painted
        //    the dark window background for a frame (white → black flash).
        applyWindowTheme(ThemePreferenceStore.resolveIsDark(this))

        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val themePreference by themeViewModel.themePreference.collectAsState()

            // 🌟 EFFECTIVE THEME = explicit override ?: system setting. The
            //    preference is reactive, and `isSystemInDarkTheme()` re-reads the
            //    device configuration, so an OS theme change applies live.
            val systemInDarkTheme = isSystemInDarkTheme()
            val isDarkTheme = themePreference.resolveIsDark(systemInDarkTheme)

            // Keep the window (status/navigation bar icon appearance + window
            // background) in lockstep on every effective-theme change.
            LaunchedEffect(isDarkTheme) { applyWindowTheme(isDarkTheme) }

            AgoraTheme(darkTheme = isDarkTheme) {
                val authViewModel: AuthViewModel = viewModel()

                val sessionStatus by authViewModel.sessionStatus.collectAsState()
                val remoteLogout by authViewModel.remoteLogoutEvent.collectAsState()
                val passwordResetStep by authViewModel.passwordResetStep.collectAsState()

                val isSigningUpState = remember { mutableStateOf(false) }
                val isOnboarding by authViewModel.isOnboarding.collectAsState()

                /* ★ Explicit destination emitted by AuthViewModel the instant a
                 *   session is created (email OTP, magic link, sign-up, Google).
                 *   null = no instruction yet, so the shell falls back to the
                 *   reactive isOnboarding flag. */
                var authDestination by remember { mutableStateOf<AuthNavTarget?>(null) }

                LaunchedEffect(Unit) {
                    authViewModel.navEvent.collect { target ->
                        /* ★ Dropping the auth entry here is the state-driven equivalent
                         *   of `popUpTo(authGraph) { inclusive = true }`: Login /
                         *   Register can never be composed again once a session
                         *   exists, so back can never return to them. */
                        isSigningUpState.value = false
                        authDestination = target
                    }
                }

                // A signed-out shell has no destination to honour.
                LaunchedEffect(sessionStatus) {
                    if (sessionStatus !is SessionStatus.Authenticated) {
                        authDestination = null
                    }
                }

                /* 🌟 LOGOUT THEME RESET: an authenticated session may carry an
                 *   explicit LIGHT/DARK override from Account Details or the
                 *   onboarding picker. On the transition Authenticated → signed
                 *   out (manual Log Out from the drawer/Account Details, a remote
                 *   "logged in elsewhere" kick, or session expiry) the override is
                 *   dropped so the unauthenticated Login/Registration screens
                 *   follow the device theme again. Cold starts with no session are
                 *   untouched: only a real transition triggers this. */
                var hadAuthenticatedSession by remember { mutableStateOf(false) }
                LaunchedEffect(sessionStatus) {
                    val authenticated = sessionStatus is SessionStatus.Authenticated
                    /* ★ Only a real signed-in -> signed-out transition resets anything.
                     *   A token refresh re-emits Authenticated with the SAME identity;
                     *   `isIdentityTransition` is false for it, so what the user chose —
                     *   the theme override here, and the NavHost back stack in the shell —
                     *   survives coming back from the launcher. */
                    if (SessionContinuity.isIdentityTransition(hadAuthenticatedSession, authenticated) &&
                        !authenticated
                    ) {
                        themeViewModel.resetToSystem()
                    }
                    if (authenticated) hadAuthenticatedSession = true
                }

                val awaitingOtp by authViewModel.awaitingOtp.collectAsState()
                val otpEmail by authViewModel.otpEmail.collectAsState()
                val googleFirstName by authViewModel.googleFirstName.collectAsState()
                val googleLastName by authViewModel.googleLastName.collectAsState()
                val googleAvatarUrl by authViewModel.googleAvatarUrl.collectAsState()

                val context = LocalContext.current
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        syncFcmTokenAndSubscribeTopics()
                    }
                }

                // Release the splash as soon as the auth session resolves.
                LaunchedEffect(sessionStatus) {
                    if (sessionStatus !is SessionStatus.Initializing) {
                        keepSplashOnScreen = false
                    }
                }

                // 🌟 SUPABASE REALTIME IN-APP NOTIFICATIONS: independent of FCM and
                //    Google Play Services. Starts as soon as a session is authenticated
                //    (regardless of POST_NOTIFICATIONS permission or FCM health) and is
                //    torn down on logout / remote session loss. Idempotent per user.
                LaunchedEffect(sessionStatus) {
                    if (sessionStatus is SessionStatus.Authenticated) {
                        NotificationRepository.startRealtimeNotifications()
                    } else if (sessionStatus !is SessionStatus.Initializing) {
                        NotificationRepository.stopRealtimeNotifications()
                    }
                }

                LaunchedEffect(sessionStatus, isOnboarding, awaitingOtp) {
                    if (sessionStatus is SessionStatus.Authenticated) {
                        /* ★ The auth entry is gone for good the moment a session
                         *   exists. This used to be gated on `!isOnboarding &&
                         *   !awaitingOtp`, so a session created by a verification
                         *   that later dropped bounced the user back to the
                         *   Register screen instead of resuming onboarding. */
                        isSigningUpState.value = false

                        if (!isOnboarding && !awaitingOtp) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val permissionCheck = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                                if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    syncFcmTokenAndSubscribeTopics()
                                }
                            } else {
                                syncFcmTokenAndSubscribeTopics()
                            }
                        }
                    }
                }

                // 🌟 SINGLE-DEVICE LOGIN: mandatory, non-dismissible warning shown BEFORE the
                //    session is cleared. "OK" (or a 5s auto-timeout) signs the user out,
                //    wipes local user state and routes back to Login with the back stack gone.
                if (remoteLogout) {
                    AlertDialog(
                        onDismissRequest = { /* non-dismissible */ },
                        properties = DialogProperties(
                            dismissOnBackPress = false,
                            dismissOnClickOutside = false
                        ),
                        title = { Text("Session ended") },
                        text = { Text("You have been logged in on another device") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    clearLocalUserCaches(applicationContext)
                                    isSigningUpState.value = false
                                    themeViewModel.resetToSystem()
                                    authViewModel.confirmRemoteLogout()
                                }
                            ) {
                                Text("OK")
                            }
                        }
                    )

                    LaunchedEffect(Unit) {
                        delay(5000)
                        clearLocalUserCaches(applicationContext)
                        isSigningUpState.value = false
                        themeViewModel.resetToSystem()
                        authViewModel.confirmRemoteLogout()
                    }
                }

                val isCheckingProfileCompleteness by authViewModel.isCheckingProfileCompleteness.collectAsState()

                InAppNotificationManager(
                    onNotificationClick = { postId, commentId ->
                        // Foreground banner tap routes to the post via the same router
                        // used by notification taps.
                        DeepLinkRouter.submit(postId, commentId)
                    }
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {

                            when (sessionStatus) {
                                is SessionStatus.Authenticated -> {
                                    /* ★ Prefer the explicit destination emitted when the
                                     *   session was created; fall back to the reactive
                                     *   flag for paths that set it directly (Google
                                     *   sign-in, restored sessions). */
                                    val routeToOnboarding = authDestination
                                        ?.let { it is AuthNavTarget.Onboarding }
                                        ?: isOnboarding

                                    if (isCheckingProfileCompleteness) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    } else if (awaitingOtp) {
                                        // Keep the pending email challenge visible if a
                                        // session callback arrives before verification
                                        // has finished updating the local UI state.
                                        RegisterScreen(
                                            viewModel = authViewModel,
                                            onNavigateToLogin = {
                                                isSigningUpState.value = false
                                                authViewModel.signOut()
                                            },
                                            initialOtpEmail = otpEmail
                                        )
                                    } else if (routeToOnboarding) {
                                        OnboardingFlowScreen(
                                            initialFirstName = googleFirstName,
                                            initialLastName = googleLastName,
                                            initialAvatarUrl = googleAvatarUrl,
                                            themeViewModel = themeViewModel,
                                            onSaveData = { firstName, lastName, handle, gender, dob, password, avatarRemote, avatarLocal, onResult ->
                                                authViewModel.saveOnboardingDetails(
                                                    context = context,
                                                    firstName = firstName,
                                                    lastName = lastName,
                                                    handle = handle,
                                                    gender = gender,
                                                    dob = dob,
                                                    realPassword = password,
                                                    avatarRemoteUrl = avatarRemote,
                                                    avatarLocalUri = avatarLocal,
                                                    // 🌟 The flow only advances to step 3 when the
                                                    // profiles row AND the password update both
                                                    // succeeded — a silent failure here is what
                                                    // looped Google re-logins back to onboarding.
                                                    onSuccess = { onResult(true, null) },
                                                    onFailure = { message -> onResult(false, message) }
                                                )
                                            },
                                            onFinish = {
                                                authViewModel.finishOnboarding()
                                            }
                                        )
                                    } else {
                                        MainScreen(
                                            authViewModel = authViewModel,
                                            themeViewModel = themeViewModel
                                        )
                                    }
                                }
                                is SessionStatus.Initializing -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                                else -> {
                                    if (isSigningUpState.value) {
                                        RegisterScreen(
                                            viewModel = authViewModel,
                                            onNavigateToLogin = { isSigningUpState.value = false }
                                        )
                                    } else {
                                        LoginScreen(
                                            authViewModel = authViewModel,
                                            onNavigateToRegister = { isSigningUpState.value = true }
                                        )
                                    }
                                }
                            }

                            /* ★ MODAL, NOT A REPLACEMENT — this screen used to replace the
                             *   whole `when (sessionStatus)` tree, which unmounted MainScreen
                             *   and destroyed its rememberNavController(). Leaving the flow
                             *   then rebuilt the NavHost from `startDestination = feed`, so a
                             *   user who came from Account Details landed on the Feed with
                             *   their back stack gone.
                             *
                             *   Composed after the main tree inside the same Box, the flow
                             *   sits on top and the feed / Account Details NavHost is never
                             *   torn down: Back returns to where the user started, and
                             *   finishing the update leaves them there. */
                            /* ★ ANIMATED, NOT A BARE `if`.
                             *   Composed as a plain conditional, this screen mounted
                             *   and unmounted between two frames: it appeared with no
                             *   motion and Back deleted a full-screen opaque surface
                             *   in a single step. Now it slides over the app tree on
                             *   the same clock and easing as every NavHost push, so
                             *   leaving Change Password looks like leaving Account
                             *   Details. */
                            AnimatedVisibility(
                                visible = passwordResetStep != null,
                                enter = NavTransitions.overlayEnter(),
                                exit = NavTransitions.overlayExit()
                            ) {
                                PasswordResetScreen(
                                    viewModel = authViewModel,
                                    onCancel = { authViewModel.cancelPasswordReset() },
                                    onSuccess = {
                                        Toast.makeText(applicationContext, "Password updated successfully!", Toast.LENGTH_LONG).show()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies the resolved theme to the SYSTEM WINDOW: light/dark status-bar and
     * navigation-bar icons plus the window background. Called before the first
     * frame and again on every effective-theme change, so the bars never clash
     * with the canvas underneath them.
     */
    private fun applyWindowTheme(isDark: Boolean) {
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                if (isDark) WINDOW_BACKGROUND_DARK else WINDOW_BACKGROUND_LIGHT
            )
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    /**
     * Clears user-scoped local state after a remote logout. The persistent device
     * id stays (device-scoped); the theme override is reset separately by the
     * Log Out actions so the auth screens follow the device again.
     */
    private fun clearLocalUserCaches(context: Context) {
        // Supabase session snapshot (auth.signOut also clears it; be explicit).
        context.getSharedPreferences("agora_session", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PushNotificationService.CHANNEL_ID,
                PushNotificationService.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority notifications for Agora messages and updates"
                enableLights(true)
                enableVibration(true)
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * launchMode="singleTop" means the SAME MainActivity (and the same
     * AuthViewModel) is reused, so no state is reset here — we only publish the
     * new intent. The auth-callback gate is a StateFlow, so an already-running
     * ViewModel is notified too.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AuthCallbackGate.notify(intent)
        injectDeepLink(intent)
        try {
            supabaseClient.handleDeeplinks(intent)
        } catch (_: Exception) {}
    }
}
