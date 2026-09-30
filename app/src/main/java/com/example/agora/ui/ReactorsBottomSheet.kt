package com.example.agora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.agora.model.ReactorDetails

/**
 * ✦ Reactions bottom sheet — reusable wherever a reaction count/button lives.
 *
 * Three body states driven by [isLoading] and [reactorsList]:
 *   loading → centered spinner (ONLY while a fetch is in flight),
 *   empty   → centered "No reactions yet" invitation,
 *   loaded  → LazyColumn of reactor rows (avatar, name, @username, emoji),
 *              each row tappable through [onUserClick] to open that profile.
 *
 * Styling stays theme-aware (Material colorScheme surfaces) so the premium
 * dark/light look is preserved in both modes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactorsBottomSheet(
    reactorsList: List<ReactorDetails>,
    isLoading: Boolean = false,
    onDismissRequest: () -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .heightIn(min = 280.dp, max = 550.dp)
        ) {
            Text(
                text = "Reactions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (isLoading) {
                // ✦ Actively fetching — and only then — show the spinner.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (reactorsList.isEmpty()) {
                // ✦ Fetch finished with nothing — the old code spun forever here.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No reactions yet. Be the first to show some appreciation!",
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(reactorsList, key = { reactor -> reactor.userId }) { reactor ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                // 🌟 Avatar + name (the whole row) navigate to the
                                //    reactor's profile.
                                .clickable { onUserClick(reactor.userId) }
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Avatar
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (reactor.avatarUrl != null) {
                                    AsyncImage(
                                        model = reactor.avatarUrl,
                                        contentDescription = "Avatar",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "Default Avatar",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            // Name & Username
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = reactor.displayName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = reactor.username,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Trailing Reaction Emoji String
                            Text(
                                text = reactor.reactionType,
                                fontSize = 22.sp,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
