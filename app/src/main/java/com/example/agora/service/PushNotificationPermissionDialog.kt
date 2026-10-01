package com.example.agora.service

import com.example.agora.ui.components.AgoraPrimaryButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RequestNotificationPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Notifications") },
        text = { Text("Agora uses notifications to keep you updated on likes, comments, and community messages.") },
        confirmButton = {
            AgoraPrimaryButton(text = "Allow", onClick = onConfirm)
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now")
            }
        },
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    )
}
