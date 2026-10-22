package net.agora.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.agora.app.data.ThemePreference

/**
 * ✦ THREE-OPTION THEME SELECTOR — System / Light / Dark.
 *
 * Replaces the old two-state "Dark Mode" switch in Account Details. That switch
 * could only describe a user choice as a device override, so the "follow the
 * device" state had no position of its own: it was the *absence* of a choice,
 * described to the user in developer language instead of being offered as an
 * option. Nobody should have to read implementation vocabulary to use a theme
 * setting.
 *
 * Three pills make the actual model visible — there is no override state left
 * to explain, because "System default" is simply one of the options, named.
 *
 * Selection is persisted by [net.agora.app.data.ThemePreferenceStore] and the
 * whole app re-themes from the observed StateFlow, so a tap applies immediately
 * with no activity restart.
 */
@Composable
fun ThemeModeSelector(
    selected: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeModePill(
                label = "System",
                icon = Icons.Default.SettingsBrightness,
                isSelected = selected == ThemePreference.SYSTEM,
                onClick = { onSelect(ThemePreference.SYSTEM) },
                modifier = Modifier.weight(1f)
            )
            ThemeModePill(
                label = "Light",
                icon = Icons.Default.LightMode,
                isSelected = selected == ThemePreference.LIGHT,
                onClick = { onSelect(ThemePreference.LIGHT) },
                modifier = Modifier.weight(1f)
            )
            ThemeModePill(
                label = "Dark",
                icon = Icons.Default.DarkMode,
                isSelected = selected == ThemePreference.DARK,
                onClick = { onSelect(ThemePreference.DARK) },
                modifier = Modifier.weight(1f)
            )
        }

        // One plain sentence, and it describes the SELECTED option rather than
        // an "override" state — nothing here can go stale.
        Text(
            text = when (selected) {
                ThemePreference.SYSTEM -> "Follows your device's Light or Dark setting."
                ThemePreference.LIGHT -> "Always light, whatever the device is set to."
                ThemePreference.DARK -> "Always dark, whatever the device is set to."
            },
            fontSize = 12.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, start = 2.dp)
        )
    }
}

@Composable
private fun ThemeModePill(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }

    val container = if (isSelected) scheme.primary else scheme.surfaceContainerHighest
    val content = if (isSelected) Color.White else scheme.onSurfaceVariant
    val border = if (isSelected) {
        BorderStroke(1.dp, scheme.primary)
    } else {
        BorderStroke(1.dp, scheme.outlineVariant)
    }

    Surface(
        // selectable() rather than clickable(): TalkBack announces "selected"
        // and the three pills behave as one radio group.
        modifier = modifier
            .selectable(
                selected = isSelected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics(mergeDescendants = true) {
                this.selected = isSelected
                this.role = Role.RadioButton
            },
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = border
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = content,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
