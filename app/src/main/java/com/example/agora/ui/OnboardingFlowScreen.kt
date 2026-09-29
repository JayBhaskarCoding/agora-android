package com.example.agora.ui

import android.app.DatePickerDialog
import android.net.Uri
import android.widget.DatePicker
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.agora.viewmodel.ThemePreference
import com.example.agora.viewmodel.ThemeViewModel
import java.util.Calendar

fun generateUniqueHandle(firstName: String, lastName: String): String {
    val first = firstName.trim().lowercase().replace("\\s+".toRegex(), "")
    val last = lastName.trim().lowercase().replace("\\s+".toRegex(), "")
    val base = if (last.isNotEmpty()) "${first}_${last.first()}" else first
    return "$base${(1000..9999).random()}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFlowScreen(
    initialFirstName: String = "",
    initialLastName: String = "",
    initialAvatarUrl: String? = null,
    themeViewModel: ThemeViewModel,
    onSaveData: (
        firstName: String,
        lastName: String,
        handle: String,
        gender: String,
        dob: String,
        password: String,
        avatarRemoteUrl: String?,
        avatarLocalUri: Uri?
    ) -> Unit,
    onFinish: () -> Unit
) {
    // Steps: 1: Profile Details & Avatar, 2: Secure Password, 3: Theme Customization
    var currentStep by remember { mutableIntStateOf(1) }

    // Step 1: Profile & Avatar State
    var firstName by remember(initialFirstName) { mutableStateOf(initialFirstName) }
    var lastName by remember(initialLastName) { mutableStateOf(initialLastName) }
    var handle by remember { mutableStateOf("") }
    var hasGeneratedHandle by remember { mutableStateOf(false) }

    var selectedAvatarUri by remember { mutableStateOf<Uri?>(null) }
    val googleAvatar = remember(initialAvatarUrl) { initialAvatarUrl }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedAvatarUri = uri
        }
    }

    LaunchedEffect(initialFirstName, initialLastName) {
        if (!hasGeneratedHandle && firstName.isNotBlank() && handle.isBlank()) {
            handle = generateUniqueHandle(firstName, lastName)
            hasGeneratedHandle = true
        }
    }

    var gender by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var expandedGenderDropdown by remember { mutableStateOf(false) }
    val genderOptions = listOf("Male", "Female", "Non-binary", "Prefer not to say")

    // Step 2: Password State
    var password by remember { mutableStateOf("") }

    // Step 3: Theme State
    val currentThemePref by themeViewModel.themePreference.collectAsState()

    val context = LocalContext.current
    val calendar = Calendar.getInstance()
    val datePickerDialog = DatePickerDialog(
        context,
        { _: DatePicker, year: Int, month: Int, dayOfMonth: Int ->
            dob = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
        },
        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sleek Step Indicator Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (step in 1..3) {
                    val isActive = step <= currentStep
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut()
                        )
                    }
                },
                label = "OnboardingStepAnimation"
            ) { step ->
                when (step) {
                    1 -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Create Your Profile",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Text(
                                text = "Set up your avatar and identity on Agora.",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.Start)
                                    .padding(bottom = 24.dp)
                            )

                            // Avatar Selection Header with Glow Effect
                            Box(
                                modifier = Modifier
                                    .size(118.dp)
                                    .padding(4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .border(
                                            2.5.dp,
                                            Brush.linearGradient(
                                                listOf(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.colorScheme.secondary
                                                )
                                            ),
                                            CircleShape
                                        )
                                        .clickable {
                                            photoPickerLauncher.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val avatarModel = selectedAvatarUri ?: googleAvatar
                                    if (avatarModel != null) {
                                        AsyncImage(
                                            model = avatarModel,
                                            contentDescription = "Avatar",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = "Default Avatar",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(54.dp)
                                        )
                                    }
                                }

                                // Small floating badge for gallery edit
                                SmallFloatingActionButton(
                                    onClick = {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    shape = CircleShape,
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraAlt,
                                        contentDescription = "Change photo",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            TextButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
                            ) {
                                Text(
                                    text = if (selectedAvatarUri != null || googleAvatar != null) "Change Photo" else "Upload Profile Photo",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 14.sp
                                )
                            }

                            // Input fields
                            OutlinedTextField(
                                value = firstName,
                                onValueChange = { firstName = it },
                                label = { Text("First Name *") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            )

                            OutlinedTextField(
                                value = lastName,
                                onValueChange = { lastName = it },
                                label = { Text("Last Name (Optional)") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused && !hasGeneratedHandle && firstName.isNotBlank()) {
                                            handle = generateUniqueHandle(firstName, lastName)
                                            hasGeneratedHandle = true
                                        }
                                    }
                            )

                            OutlinedTextField(
                                value = handle,
                                onValueChange = { handle = it; hasGeneratedHandle = true },
                                label = { Text("Username (@handle) *") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp)
                            )

                            ExposedDropdownMenuBox(
                                expanded = expandedGenderDropdown,
                                onExpandedChange = { expandedGenderDropdown = !expandedGenderDropdown },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            ) {
                                OutlinedTextField(
                                    value = gender,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Gender (Optional)") },
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedGenderDropdown) },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )
                                ExposedDropdownMenu(
                                    expanded = expandedGenderDropdown,
                                    onDismissRequest = { expandedGenderDropdown = false }
                                ) {
                                    genderOptions.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option) },
                                            onClick = {
                                                gender = option
                                                expandedGenderDropdown = false
                                            }
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 28.dp)
                            ) {
                                OutlinedTextField(
                                    value = dob,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Date of Birth (Optional)") },
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = "Date") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable { datePickerDialog.show() }
                                )
                            }

                            Button(
                                onClick = { currentStep = 2 },
                                enabled = firstName.isNotBlank() && handle.isNotBlank(),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    2 -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "Secure Account",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Set a secure password for account recovery.",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 28.dp)
                            )

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password *") },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            )
                            Text(
                                text = "Password requires 8+ characters, 1 uppercase, 1 number, and 1 special character.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(bottom = 32.dp)
                            )

                            Button(
                                onClick = {
                                    // Save all user details including avatar selection
                                    onSaveData(
                                        firstName,
                                        lastName,
                                        handle,
                                        gender,
                                        dob,
                                        password,
                                        googleAvatar,
                                        selectedAvatarUri
                                    )
                                    currentStep = 3
                                },
                                enabled = password.length >= 8,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text("Next: Theme Customization", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    3 -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "Choose Appearance",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Select your preferred app theme. Changes apply instantly.",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 28.dp)
                            )

                            ThemeOptionCard(
                                title = "System Default",
                                subtitle = "Follows your device system settings",
                                icon = Icons.Default.SettingsBrightness,
                                isSelected = currentThemePref == ThemePreference.SYSTEM,
                                onClick = { themeViewModel.setThemePreference(ThemePreference.SYSTEM) }
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            ThemeOptionCard(
                                title = "Dark Mode",
                                subtitle = "Pure dark theme designed for OLED displays",
                                icon = Icons.Default.DarkMode,
                                isSelected = currentThemePref == ThemePreference.DARK,
                                onClick = { themeViewModel.setThemePreference(ThemePreference.DARK) }
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            ThemeOptionCard(
                                title = "Light Mode",
                                subtitle = "Crisp and clear high-contrast light theme",
                                icon = Icons.Default.LightMode,
                                isSelected = currentThemePref == ThemePreference.LIGHT,
                                onClick = { themeViewModel.setThemePreference(ThemePreference.LIGHT) }
                            )

                            Spacer(modifier = Modifier.height(36.dp))

                            Button(
                                onClick = onFinish,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                            ) {
                                Text("Enter Agora", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
