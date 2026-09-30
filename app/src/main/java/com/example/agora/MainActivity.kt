package com.example.agora

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.agora.data.NotificationRepository
import com.example.agora.data.supabaseClient
import com.example.agora.model.FcmTokenUpdate
import com.example.agora.navigation.DeepLinkRouter
import com.example.agora.service.PushNotificationService
import com.example.agora.ui.InAppNotificationManager
import com.example.agora.ui.LoginScreen
import com.example.agora.ui.MainScreen
import com.example.agora.ui.OnboardingFlowScreen
import com.example.agora.ui.PasswordResetScreen
import com.example.agora.ui.RegisterScreen
import com.example.agora.ui.theme.AgoraTheme
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.viewmodel.ThemeViewModel
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
 *    (see [com.example.agora.data.NotificationRepository]).
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

    /**
     * Splash is released as soon as the auth session resolves (see the
     * LaunchedEffect in [setContent]) instead of for a fixed delay — this used to
     * add a hard 800ms to every cold start.
     */
    @Volatile
    private var keepSplashOnScreen = true

    // 🌟 Incremented whenever an agora://auth-callback?flow=delete-account deep
    //    link arrives (cold start or warm onNewIntent). Observed inside setContent:
    //    once the session is Authenticated, the verified-deletion RPC is fired.
    private val deletionCallbackTick = mutableStateOf(0)

    private fun isDeletionCallback(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        return data.scheme == "agora" &&
            data.host == "auth-callback" &&
            data.getQueryParameter("flow") == "delete-account"
    }

    // 🌟 INTENT INJECTION: Extract FCM notification extras / deep link URI, publish
    //    them to the DeepLinkRouter (single source of truth for post navigation) and
    //    forge intent.data so the NavHost's native navDeepLink path can also match.
    private fun injectDeepLink(intent: Intent?) {
        if (intent == null) return
        Log.d("FCM_TEST", "Extras: ${intent.extras?.keySet()} data: ${intent.data}")

        val parsed = DeepLinkRouter.parse(intent)
        val postId = parsed?.postId
        val commentId = parsed?.commentId

        if (!postId.isNullOrBlank()) {
            val uriString = if (!commentId.isNullOrBlank()) {
                "agora://post/$postId?commentId=$commentId"
            } else {
                "agora://post/$postId"
            }
            Log.d("FCM_TEST", "Routing post deep link: $uriString")
            intent.data = Uri.parse(uriString)
            intent.action = Intent.ACTION_VIEW

            // MainScreen observes this and navigates once the NavHost exists
            // (works for cold starts, warm onNewIntent and foreground banner taps).
            DeepLinkRouter.submit(postId, commentId)

            intent.removeExtra("post_id")
            intent.removeExtra("comment_id")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        injectDeepLink(intent)

        if (isDeletionCallback(intent)) deletionCallbackTick.value++
        intent?.let {
            try {
                supabaseClient.handleDeeplinks(it)
            } catch (_: Exception) {}
        }

        setContent {
            val themeViewModel: ThemeViewModel = viewModel()
            val isDarkMode by themeViewModel.isDarkMode.collectAsState()

            AgoraTheme(darkTheme = isDarkMode) {
                val authViewModel: AuthViewModel = viewModel()

                val sessionStatus by authViewModel.sessionStatus.collectAsState()
                val remoteLogout by authViewModel.remoteLogoutEvent.collectAsState()
                val passwordResetStep by authViewModel.passwordResetStep.collectAsState()

                val isSigningUpState = remember { mutableStateOf(false) }
                val isOnboarding by authViewModel.isOnboarding.collectAsState()
                val awaitingOtp by authViewModel.awaitingOtp.collectAsState()
                val otpEmail by authViewModel.otpEmail.collectAsState()

                // 🌟 Verified deletion deep link → once the (re-imported) session
                //    is Authenticated, stamp the 3-day deletion deadline via RPC.
                val deletionTick = deletionCallbackTick.value
                LaunchedEffect(deletionTick, sessionStatus) {
                    if (deletionTick > 0 && sessionStatus is SessionStatus.Authenticated) {
                        deletionCallbackTick.value = 0
                        authViewModel.completeVerifiedDeletionRequest()
                    }
                }
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
                    if (sessionStatus is SessionStatus.Authenticated && !isOnboarding && !awaitingOtp) {
                        isSigningUpState.value = false

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
                        if (passwordResetStep != null) {
                            PasswordResetScreen(
                                viewModel = authViewModel,
                                onCancel = { authViewModel.cancelPasswordReset() },
                                onSuccess = {
                                    Toast.makeText(applicationContext, "Password updated successfully!", Toast.LENGTH_LONG).show()
                                }
                            )
                        } else {
                            when (sessionStatus) {
                                is SessionStatus.Authenticated -> {
                                    if (isCheckingProfileCompleteness) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator()
                                        }
                                    } else if (awaitingOtp) {
                                        // 🌟 OTP gate for authenticated-but-unverified sessions
                                        //    (brand-new Google identities): reuse the EXACT
                                        //    RegisterScreen presentation (join-agora background +
                                        //    floating glass card) instead of a bare screen.
                                        //    initialOtpEmail injects the Google payload email and
                                        //    starts the card straight in verification mode, so the
                                        //    email form step is never rendered. verifyOtpCode
                                        //    flips awaitingOtp→false / isOnboarding→true on
                                        //    success — a state switch, not a back-stack hop, so
                                        //    back can never return to an irrelevant step.
                                        RegisterScreen(
                                            viewModel = authViewModel,
                                            onNavigateToLogin = { },
                                            initialOtpEmail = otpEmail
                                        )
                                    } else if (isOnboarding) {
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
                        }
                    }
                }
            }
        }
    }

    /**
     * Clears user-scoped local state after a remote logout. Theme preferences and
     * the persistent device id are device-scoped and intentionally preserved.
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        injectDeepLink(intent)
        if (isDeletionCallback(intent)) deletionCallbackTick.value++
        try {
            supabaseClient.handleDeeplinks(intent)
        } catch (_: Exception) {}
    }
}
