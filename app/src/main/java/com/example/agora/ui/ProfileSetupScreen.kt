package com.example.agora.ui

import android.app.DatePickerDialog
import android.widget.DatePicker
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    onSetupComplete: (gender: String, dob: String, theme: String) -> Unit
) {
    var gender by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf("System Default") }

    var expandedGenderDropdown by remember { mutableStateOf(false) }
    val genderOptions = listOf("Male", "Female", "Non-binary", "Prefer not to say")

    val context = LocalContext.current
    val calendar = Calendar.getInstance()

    // Native Android Date Picker
    val datePickerDialog = DatePickerDialog(
        context,
        { _: DatePicker, year: Int, month: Int, dayOfMonth: Int ->
            // Formats strictly as YYYY-MM-DD for your database
            dob = String.format("%04d-%02d-%02d", year, month + 1, dayOfMonth)
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Almost there!",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Tell us a bit more about yourself to personalize your experience.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            // 1. Gender Dropdown (Material 3 Exposed Dropdown)
            ExposedDropdownMenuBox(
                expanded = expandedGenderDropdown,
                onExpandedChange = { expandedGenderDropdown = !expandedGenderDropdown },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                OutlinedTextField(
                    value = gender,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Gender") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedGenderDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedGenderDropdown,
                    onDismissRequest = { expandedGenderDropdown = false }
                ) {
                    genderOptions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption) },
                            onClick = {
                                gender = selectionOption
                                expandedGenderDropdown = false
                            }
                        )
                    }
                }
            }

            // 2. Date of Birth Picker - NOW WRAPPED IN AN OUTER BOX
            Box(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                OutlinedTextField(
                    value = dob,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date of Birth") },
                    placeholder = { Text("YYYY-MM-DD") },
                    trailingIcon = {
                        Icon(Icons.Default.DateRange, contentDescription = "Select Date")
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 🌟 Transparent overlay that catches all clicks and opens the calendar
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { datePickerDialog.show() }
                )
            }

            // 3. Theme Selection
            Text("App Theme", fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            val themeOptions = listOf("Light", "Dark", "System Default")

            Column(Modifier.selectableGroup().padding(bottom = 32.dp)) {
                themeOptions.forEach { text ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .selectable(
                                selected = (text == theme),
                                onClick = { theme = text },
                                role = Role.RadioButton
                            )
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (text == theme),
                            onClick = null // null is required here when the parent Row is selectable
                        )
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
            }

            // 4. Submit Button
            Button(
                onClick = { onSetupComplete(gender, dob, theme) },
                enabled = gender.isNotBlank() && dob.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Complete Setup")
            }
        }
    }
}