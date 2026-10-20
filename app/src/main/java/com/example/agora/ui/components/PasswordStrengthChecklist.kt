package com.example.agora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.agora.utils.PasswordPolicy

@Composable
fun PasswordStrengthChecklist(password: String, modifier: Modifier = Modifier) {
    val requirements = PasswordPolicy.requirements(password)
    val fulfilled = requirements.count { it.satisfied }
    val progress by animateFloatAsState(fulfilled / 5f, label = "Password requirements progress")
    // Readable success colors on both light and dark surfaces.
    val success = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF81C784)
    } else {
        Color(0xFF256C32)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (fulfilled == 5) "Password meets all requirements" else "$fulfilled of 5 requirements met",
            style = MaterialTheme.typography.labelMedium,
            color = if (fulfilled == 5) success else MaterialTheme.colorScheme.onSurfaceVariant
        )
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            color = if (fulfilled == 5) success else MaterialTheme.colorScheme.primary
        )
        requirements.forEach { requirement ->
            val color by animateColorAsState(
                targetValue = if (requirement.satisfied) success else MaterialTheme.colorScheme.onSurfaceVariant,
                label = requirement.label
            )
            Row(
                modifier = Modifier.semantics {
                    stateDescription = if (requirement.satisfied) "Met" else "Not met"
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (requirement.satisfied) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
                Text(requirement.label, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}
