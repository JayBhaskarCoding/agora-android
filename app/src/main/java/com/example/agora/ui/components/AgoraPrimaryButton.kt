package com.example.agora.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

/* ═══════════════════════════════════════════════════════════════════════
 * ✦ Agora's ONE primary-action button — the app-wide standard.
 *
 * Solid, vibrant purple (0xFFA855F7). No gradients. Full pill via
 * RoundedCornerShape(50) — the Int overload is PERCENT, so the corners
 * stay perfectly semicircular at any button height. White label, flat
 * elevation, brand-tinted disabled state.
 *
 * Every primary CTA (auth, posting, editing, onboarding, dialogs)
 * routes through this file, so a future brand refresh is a one-line
 * change instead of a 20-site audit.
 * ═══════════════════════════════════════════════════════════════════ */

/** The single primary-action purple — solid, vibrant, gradient-free. */
val AgoraPrimaryPurple = Color(0xFFA855F7)

/**
 * Standard primary button with a plain text label.
 */
@Composable
fun AgoraPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    AgoraPrimaryButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Standard primary button with custom content (icons, spinners, styled
 * labels). The content slot is the standard [Button] row — vertical
 * alignment and ripple come from Material, colors/shape come from here.
 */
@Composable
fun AgoraPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = AgoraPrimaryPurple,
            contentColor = Color.White,
            disabledContainerColor = AgoraPrimaryPurple.copy(alpha = 0.40f),
            disabledContentColor = Color.White.copy(alpha = 0.80f)
        )
    ) {
        content()
    }
}
