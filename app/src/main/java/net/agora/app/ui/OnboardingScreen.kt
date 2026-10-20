package net.agora.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.agora.app.utils.DateOfBirth
import net.agora.app.viewmodel.AuthViewModel
import net.agora.app.ui.components.AgoraPrimaryButton
import net.agora.app.ui.components.MinorAccountWarningBanner
import java.time.LocalDate

@Composable
fun OnboardingScreen(viewModel: AuthViewModel, onFinish: () -> Unit) {
    var gender by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    val errorMessage by viewModel.errorMessage.collectAsState()

    // 🌟 Same DOB rules as the picker-driven onboarding: strict yyyy-MM-dd,
    //    [today − 120 years, today], with an informational under-18 notice.
    val today = LocalDate.now()
    val dobError = DateOfBirth.validationError(dob, today)
    // 🌟 Only a VALID, non-empty DOB can raise the under-18 notice: blank,
    //    unparsable, future or >120-year values all resolve to `false`.
    val showMinorBanner = DateOfBirth.shouldShowMinorNotice(dob, today)

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
            onValueChange = { input ->
                // Keep manual entry shaped like yyyy-MM-dd; validation below
                // still rejects impossible/out-of-range dates.
                dob = input.filter { it.isDigit() || it == '-' }.take(10)
            },
            label = { Text("Date of Birth (YYYY-MM-DD)") },
            singleLine = true,
            isError = dobError != null,
            supportingText = {
                Text(dobError ?: "Optional — enter as YYYY-MM-DD.")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )

        MinorAccountWarningBanner(visible = showMinorBanner)

        Spacer(modifier = Modifier.height(16.dp))

        AgoraPrimaryButton(
            text = "Complete Setup",
            onClick = {
                if (dobError == null) {
                    viewModel.completeOnboarding(gender, dob, onSuccess = onFinish)
                }
            },
            enabled = dobError == null,
            modifier = Modifier.fillMaxWidth()
        )

        TextButton(onClick = onFinish) {
            Text("Skip for now")
        }
    }
}