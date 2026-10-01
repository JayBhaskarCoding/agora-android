package com.example.agora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.ui.components.AgoraPrimaryButton

@Composable
fun OnboardingScreen(viewModel: AuthViewModel, onFinish: () -> Unit) {
    var gender by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") } // Can be upgraded to a DatePicker later
    val errorMessage by viewModel.errorMessage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Almost there!", fontSize = 28.sp, modifier = Modifier.padding(bottom = 8.dp))
        Text("Tell us a little more about yourself.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 24.dp))

        if (errorMessage != null) {
            Text(errorMessage!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 16.dp))
        }

        OutlinedTextField(
            value = gender,
            onValueChange = { gender = it },
            label = { Text("Gender (Optional)") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = dob,
            onValueChange = { dob = it },
            label = { Text("Date of Birth (DD/MM/YYYY)") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        )

        AgoraPrimaryButton(
            text = "Complete Setup",
            onClick = {
                viewModel.completeOnboarding(gender, dob, onSuccess = onFinish)
            },
            modifier = Modifier.fillMaxWidth()
        )

        TextButton(onClick = onFinish) {
            Text("Skip for now")
        }
    }
}