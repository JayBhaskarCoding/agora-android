package com.example.agora.ui

import android.app.DatePickerDialog
import android.widget.DatePicker
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onSaveData: (firstName: String, lastName: String, handle: String, gender: String, dob: String, password: String) -> Unit,
    onThemeChanged: (isDark: Boolean?) -> Unit,
    onFinish: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(1) }

    // Step 1 State
    var firstName by remember(initialFirstName) { mutableStateOf(initialFirstName) }
    var lastName by remember(initialLastName) { mutableStateOf(initialLastName) }
    var handle by remember { mutableStateOf("") }
    var hasGeneratedHandle by remember { mutableStateOf(false) }

    // Auto-generate initial handle if Google provided first name
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

    // Step 2 State
    var password by remember { mutableStateOf("") }

    // Step 3 State
    var selectedTheme by remember { mutableStateOf("System Default") }

    val context = LocalContext.current
    val calendar = Calendar.getInstance()
    val datePickerDialog = DatePickerDialog(
        context,
        { _: DatePicker, year: Int, month: Int, dayOfMonth: Int ->
            dob = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
        },
        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.Start
        ) {
            when (currentStep) {
                1 -> {
                    Text("Profile Details", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    Text("Let's set up your identity.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))

                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("First Name *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Last Name (Optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
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
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = expandedGenderDropdown,
                        onExpandedChange = { expandedGenderDropdown = !expandedGenderDropdown },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                    ) {
                        OutlinedTextField(
                            value = gender, onValueChange = {}, readOnly = true,
                            label = { Text("Gender (Optional)") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedGenderDropdown) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedGenderDropdown, onDismissRequest = { expandedGenderDropdown = false }) {
                            genderOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = { gender = option; expandedGenderDropdown = false }
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                        OutlinedTextField(
                            value = dob, onValueChange = {}, readOnly = true,
                            label = { Text("Date of Birth (Optional)") },
                            trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = "Date") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Box(modifier = Modifier.matchParentSize().clickable { datePickerDialog.show() })
                    }

                    Button(
                        onClick = { currentStep = 2 },
                        enabled = firstName.isNotBlank() && handle.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Next: Set Password") }
                }

                2 -> {
                    Text("Secure Account", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    Text("Create a strong password for your new account.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password *") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    Text("Password requires 8+ characters, 1 uppercase, 1 number, and 1 special character.", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 32.dp))

                    Button(
                        onClick = {
                            // Save to database, then move to final customization step
                            onSaveData(firstName, lastName, handle, gender, dob, password)
                            currentStep = 3
                        },
                        enabled = password.length >= 8,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Next: Customization") }
                }

                3 -> {
                    Text("Make it yours", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                    Text("Choose your preferred app theme.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))

                    val themeOptions = listOf("Light", "Dark", "System Default")
                    Column(Modifier.selectableGroup().padding(bottom = 32.dp)) {
                        themeOptions.forEach { text ->
                            Row(
                                Modifier.fillMaxWidth().height(48.dp)
                                    .selectable(
                                        selected = (text == selectedTheme),
                                        onClick = {
                                            selectedTheme = text
                                            // Trigger real-time visual update!
                                            val isDark = when(text) { "Dark" -> true; "Light" -> false; else -> null }
                                            onThemeChanged(isDark)
                                        },
                                        role = Role.RadioButton
                                    ).padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = (text == selectedTheme), onClick = null)
                                Text(text, modifier = Modifier.padding(start = 16.dp))
                            }
                        }
                    }

                    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                        Text("Enter Agora")
                    }
                }
            }
        }
    }
}