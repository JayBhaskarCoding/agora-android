package com.example.agora.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.agora.ui.components.VibrantGlassBackground
import com.example.agora.ui.theme.rememberAgoraColors
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.ThemeViewModel

// 🌟 Helper to highlight matching text queries case-insensitively
fun highlightQuery(text: String, query: String, highlightColor: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val startIndex = text.lowercase().indexOf(query.lowercase())
    if (startIndex < 0) return AnnotatedString(text)
    val endIndex = startIndex + query.length

    return buildAnnotatedString {
        append(text.substring(0, startIndex))
        withStyle(style = SpanStyle(color = highlightColor, fontWeight = FontWeight.ExtraBold)) {
            append(text.substring(startIndex, endIndex))
        }
        append(text.substring(endIndex))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSearchScreen(
    feedViewModel: FeedViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    onBack: () -> Unit,
    onUserClick: (String) -> Unit
) {
    val isDarkTheme = com.example.agora.ui.theme.LocalDarkTheme.current
    val agora = rememberAgoraColors()

    var searchQuery by remember { mutableStateOf("") }
    val searchResults by feedViewModel.searchResults.collectAsState()
    val primaryColor = MaterialTheme.colorScheme.primary

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // ✦ Agora Noir canvas — no blurred wallpaper, the same aurora gradient as the feed.
    VibrantGlassBackground(isDarkTheme = isDarkTheme) {

        // 🌟 2. SCROLLING SEARCH RESULTS
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = statusBarTop + 76.dp, // Offsets list below top search bar at rest
                bottom = navBarBottom + 32.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (searchResults.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "Type a name or @handle to search users" else "No users found.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                items(searchResults, key = { it.id }) { profile ->
                    val fullName = "${profile.firstName} ${profile.lastName ?: ""}".trim()
                    val handleText = "@${profile.handle}"

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = agora.cardSurface,
                        border = BorderStroke(1.dp, agora.cardBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onUserClick(profile.id) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (profile.avatarUrl != null) {
                                    AsyncImage(
                                        model = profile.avatarUrl,
                                        contentDescription = "Avatar",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = highlightQuery(fullName, searchQuery, primaryColor),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = highlightQuery(handleText, searchQuery, primaryColor),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 🌟 3. FLOATING SEARCH BAR — seamless scrim, no hairline divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to agora.canvasTop.copy(alpha = 0.96f),
                            0.72f to agora.canvasTop.copy(alpha = 0.88f),
                            1.0f to Color.Transparent
                        )
                    )
                )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(64.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            feedViewModel.searchusers(it)
                        },
                        placeholder = {
                            Text(
                                text = "Search users...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = agora.insetSurface,
                            unfocusedContainerColor = agora.insetSurface.copy(alpha = 0.6f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }

                // Scrim fade-out strip — replaces the hard divider line
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
