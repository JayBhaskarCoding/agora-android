package com.example.agora.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.agora.data.supabaseClient
import com.example.agora.model.Profile
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.viewmodel.ThemeViewModel
import com.example.agora.ui.components.AgoraPrimaryButton
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import java.util.UUID

fun maskEmail(email: String): String {
    if (!email.contains("@")) return email
    val parts = email.split("@")
    val name = parts[0]
    val domain = parts[1]
    val visibleChars = name.take(5)
    return "$visibleChars***@$domain"
}

fun getTimeAgo(instantString: String?): String {
    if (instantString == null) return "Never"
    return try {
        val past = Instant.parse(instantString)
        val now = Instant.now()

        val days = ChronoUnit.DAYS.between(past, now)
        if (days > 0) return "$days days ago"

        val hours = ChronoUnit.HOURS.between(past, now)
        if (hours > 0) return "$hours hours ago"

        val minutes = ChronoUnit.MINUTES.between(past, now)
        if (minutes > 0) return "$minutes minutes ago"

        "Just now"
    } catch (e: Exception) {
        "Unknown"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailsScreen(
    viewModel: AuthViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    onBack: () -> Unit,
    onNavigateToPendingDeletion: () -> Unit = {},
    onNavigateToDeletionOtp: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentUser = supabaseClient.auth.currentUserOrNull()

    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()

    val isDarkMode by themeViewModel.isDarkMode.collectAsState()

    var profile by remember { mutableStateOf<Profile?>(null) }
    var isUploading by remember { mutableStateOf(false) }

    // 🌟 Account-deletion state: the live column value comes from the VM's
    //    profileState (refreshProfile() republishes it after the RPCs); the
    //    local one-shot fetch seeds the fallback.
    val liveProfile by viewModel.profileState.collectAsState()
    val deletionFlowActive by viewModel.deletionFlowActive.collectAsState()
    val deletionScheduledAt = liveProfile?.deletionScheduledAt ?: profile?.deletionScheduledAt

    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }

    var genderDropdownExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    // 🌟 Destructive-action gate: Delete Account must be confirmed before the
    //    reauthentication email is ever sent.
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // 🌟 Change Username — two-step glass dialog: 0 = local email gate,
    //    1 = new-handle prompt. Errors stay inline; RPC errors (e.g. "Username
    //    already taken") surface through the screen's errorMessage snackbar.
    var showUsernameDialog by remember { mutableStateOf(false) }
    var usernameStep by remember { mutableStateOf(0) }
    var verifyEmailInput by remember { mutableStateOf("") }
    var newUsernameInput by remember { mutableStateOf("") }
    var usernameDialogError by remember { mutableStateOf<String?>(null) }

    val hasChanges = profile != null && (
            firstName != profile?.firstName ||
                    lastName != (profile?.lastName ?: "") ||
                    gender != (profile?.gender ?: "") ||
                    dob != (profile?.dob ?: "")
            )

    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && currentUser != null) {
            scope.launch {
                isUploading = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }

                    if (bytes != null) {
                        val fileName = "${currentUser.id}/${UUID.randomUUID()}.jpg"
                        supabaseClient.storage.from("avatars").upload(fileName, bytes)
                        val newAvatarUrl = supabaseClient.storage.from("avatars").publicUrl(fileName)
                        supabaseClient.from("profiles").update(mapOf("avatar_url" to newAvatarUrl)) { filter { eq("id", currentUser.id) } }
                        profile = profile?.copy(avatarUrl = newAvatarUrl)
                        snackbarHostState.showSnackbar("Profile picture updated")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Failed to upload picture")
                } finally { isUploading = false }
            }
        }
    }

    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            val fetchedProfile = supabaseClient.from("profiles").select { filter { eq("id", currentUser.id) } }.decodeSingle<Profile>()
            profile = fetchedProfile
            firstName = fetchedProfile.firstName ?: ""
            lastName = fetchedProfile.lastName ?: ""
            gender = fetchedProfile.gender ?: ""
            dob = fetchedProfile.dob ?: ""
        }
    }

    // 🌟 The moment the reauthentication code is on its way, route to the
    //    shared OTP card exactly once. The flag is consumed immediately, so
    //    backing out of the OTP screen returns here cleanly (no re-routing
    //    loop) and Resend on the card never re-triggers navigation.
    LaunchedEffect(deletionFlowActive) {
        if (deletionFlowActive) {
            viewModel.consumeDeletionFlow()
            onNavigateToDeletionOtp()
        }
    }

    // 🌟 Change Username — glassmorphic two-step dialog: a purely LOCAL email
    //    gate (typed email must exactly match the session's cached email), then
    //    the new-handle prompt with live normalization and validation.
    if (showUsernameDialog) {
        val usernameValid = newUsernameInput.matches(Regex("^[a-z0-9_]{3,20}$"))
        AlertDialog(
            onDismissRequest = { showUsernameDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(
                    text = if (usernameStep == 0) "Verify Your Identity" else "Choose a New Username",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    if (usernameStep == 0) {
                        Text(
                            "To change your username, type the email address registered " +
                                "on this account. This check runs right on your device."
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = verifyEmailInput,
                            onValueChange = {
                                verifyEmailInput = it
                                usernameDialogError = null
                            },
                            singleLine = true,
                            isError = usernameDialogError != null,
                            placeholder = { Text("you@example.com") },
                            supportingText = usernameDialogError?.let { err ->
                                { Text(err, color = MaterialTheme.colorScheme.error) }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            "Your posts, likes and comments stay linked — the new " +
                                "@handle appears everywhere instantly."
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedTextField(
                            value = newUsernameInput,
                            onValueChange = { raw ->
                                // Live normalization: lowercase, allowed charset, max 20.
                                newUsernameInput = raw.lowercase()
                                    .filter { c -> c.isLetterOrDigit() || c == '_' }
                                    .take(20)
                                usernameDialogError = null
                            },
                            singleLine = true,
                            isError = usernameDialogError != null,
                            prefix = { Text("@") },
                            placeholder = { Text("new_username") },
                            supportingText = {
                                Text(
                                    usernameDialogError
                                        ?: "3-20 characters — lowercase letters, numbers, underscore."
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = if (usernameStep == 0) verifyEmailInput.isNotBlank() else usernameValid,
                    onClick = {
                        if (usernameStep == 0) {
                            // 🌟 Purely LOCAL — compares against the session's cached
                            //    email; never a network call.
                            if (viewModel.isEmailVerifiedForUsernameChange(verifyEmailInput)) {
                                usernameStep = 1
                                usernameDialogError = null
                            } else {
                                usernameDialogError =
                                    "That email doesn't match the one registered on this account."
                            }
                        } else {
                            viewModel.updateUsername(newUsernameInput) {
                                showUsernameDialog = false
                                scope.launch {
                                    snackbarHostState.showSnackbar("Username updated to @$newUsernameInput")
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (usernameStep == 0) "Verify" else "Update Username",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showUsernameDialog = false }) {
                    Text(
                        "Cancel",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    // 🌟 "Are you sure?" gate styled to match the glassmorphic surfaces.
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("Delete Account?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Are you sure you want to schedule your account for deletion? " +
                        "You'll confirm it's really you with a 6-digit code, then " +
                        "choose what gets erased. You can revert any time within " +
                        "the 3-day window."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.startAccountDeletion()
                    }
                ) {
                    Text(
                        "Yes, Proceed",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(
                        "Cancel",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        dob = formatter.format(Date(millis))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ✦ Noir identity: the sweep-gradient ring reserved for your own account.
    val avatarRing = com.example.agora.ui.theme.AgoraRingGradient
    val accentGradient = com.example.agora.ui.theme.AgoraAccentGradient
    val microLabel = com.example.agora.ui.theme.AgoraType.MicroLabel

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Account Details",
                        fontWeight = FontWeight.Black,
                        fontSize = 20.sp,
                        letterSpacing = (-0.4).sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 🌟 1. AVATAR HEADER WITH CAMERA BADGE
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(avatarRing)
                        .clickable { launcher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(102.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (profile?.avatarUrl != null) {
                            AsyncImage(
                                model = profile?.avatarUrl,
                                contentDescription = "Profile Avatar",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = "Default Avatar",
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isUploading) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                // Camera Badge Button overlapping bottom-right — accent gradient
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-4).dp, y = (-4).dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(accentGradient)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable { launcher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = "Change Picture",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = "@${profile?.handle ?: ""}",
                fontSize = 19.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.3).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = maskEmail(currentUser?.email ?: ""),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, bottom = 14.dp)
            )

            // 🌟 Change Username — opens the local email-verification gate.
            OutlinedButton(
                onClick = {
                    verifyEmailInput = ""
                    newUsernameInput = profile?.handle.orEmpty()
                    usernameStep = 0
                    usernameDialogError = null
                    showUsernameDialog = true
                },
                modifier = Modifier.height(38.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 18.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Change Username", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 🌟 2. PERSONAL INFORMATION GROUPED CARD
            Text(
                text = "PERSONAL INFORMATION",
                style = microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp, start = 6.dp)
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("First Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )

                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Last Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = genderDropdownExpanded,
                        onExpandedChange = { genderDropdownExpanded = !genderDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = gender,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Gender") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderDropdownExpanded) },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = genderDropdownExpanded,
                            onDismissRequest = { genderDropdownExpanded = false }
                        ) {
                            listOf("Male", "Female", "Other", "Rather not say").forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        gender = option
                                        genderDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = dob,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Date of Birth") },
                            trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = "Select Date") },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { showDatePicker = true }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 🌟 3. APP PREFERENCES GROUPED CARD
            Text(
                text = "APP PREFERENCES",
                style = microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp, start = 6.dp)
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Palette,
                                contentDescription = "Theme",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Dark Mode", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                "Toggle app color theme",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Switch(
                        checked = isDarkMode,
                        onCheckedChange = { themeViewModel.setDarkMode(it) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 🌟 4. SECURITY GROUPED CARD
            Text(
                text = "SECURITY",
                style = microLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp, start = 6.dp)
            )

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = "Security",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Password Security", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                "Last changed: ${getTimeAgo(profile?.passwordChangedAt)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = { viewModel.startChangePassword() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Change Password", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 🌟 5. ANIMATED SAVE CHANGES BUTTON
            AnimatedVisibility(
                visible = hasChanges,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                AgoraPrimaryButton(
                    onClick = {
                        viewModel.updateProfileDetails(firstName, lastName, gender, dob) {
                            profile = profile?.copy(firstName = firstName, lastName = lastName, gender = gender, dob = dob)
                            scope.launch {
                                snackbarHostState.showSnackbar("Profile updated successfully!")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Save Changes", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (hasChanges) {
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 🌟 6. DANGER ZONE / ACCOUNT ACTIONS
            Text(
                text = "ACCOUNT ACTIONS",
                style = microLabel,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp, start = 6.dp)
            )

            // 🌟 Delete Account — reauthentication-OTP verified, 3-day grace, fully revertible.
            //    If a deletion is already scheduled, this becomes the entry point
            //    to the PendingDeletionScreen instead of re-sending a link.
            OutlinedButton(
                onClick = {
                    if (!deletionScheduledAt.isNullOrBlank()) {
                        onNavigateToPendingDeletion()
                    } else {
                        // Confirm first — the reauthentication code is only
                        // sent after "Yes, Proceed".
                        showDeleteConfirmDialog = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (!deletionScheduledAt.isNullOrBlank()) "Deletion Pending — View" else "Delete Account",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { viewModel.signOut() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ExitToApp,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Log Out", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
