package com.example.agora.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.util.Consumer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import coil.compose.AsyncImage
import com.example.agora.data.supabaseClient
import com.example.agora.model.Profile
import com.example.agora.navigation.DeepLinkRouter
import com.example.agora.viewmodel.AuthViewModel
import com.example.agora.viewmodel.FeedViewModel
import com.example.agora.viewmodel.PostDetailViewModel
import com.example.agora.viewmodel.ThemeViewModel
import com.example.agora.viewmodel.UploadState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun Modifier.hazeChild(
    state: HazeState,
    shape: Shape
): Modifier = this
    .clip(shape)
    .hazeEffect(state = state)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    authViewModel: AuthViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    feedViewModel: FeedViewModel = viewModel(key = supabaseClient.auth.currentUserOrNull()?.id)
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // 🌟 Notification deep links (cold start, warm taps, foreground banner taps) are
    //    routed here once the NavHost exists. Idempotent: if the target post is already
    //    open (e.g. NavHost's native navDeepLink handling won the race), we stand down.
    val pendingDeepLink by DeepLinkRouter.pending.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        val target = pendingDeepLink ?: return@LaunchedEffect

        // The NavHost publishes its graph during its first composition; wait (bounded)
        // until the controller has a current destination before navigating.
        var attempts = 0
        while (navController.currentBackStackEntry == null && attempts < 300) {
            delay(16)
            attempts++
        }
        if (navController.currentBackStackEntry == null) return@LaunchedEffect

        val currentEntry = navController.currentBackStackEntry
        val alreadyOpen =
            currentEntry?.destination?.route?.startsWith("post/") == true &&
                currentEntry.arguments?.getString("postId") == target.postId
        if (!alreadyOpen) {
            navController.navigate(target.toRoute()) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
            }
        }
        DeepLinkRouter.consume(target)
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val currentUser = supabaseClient.auth.currentUserOrNull()
    var userHandle by remember { mutableStateOf("@user") }

    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            try {
                val profile = withContext(Dispatchers.IO) {
                    supabaseClient.from("profiles")
                        .select { filter { eq("id", currentUser.id) } }
                        .decodeSingle<Profile>()
                }
                userHandle = "@${profile.handle}"
            } catch (_: Exception) {
                userHandle = "@user"
            }
        }
    }

    var showComposeScreen by remember { mutableStateOf(false) }
    val uploadState by feedViewModel.uploadState.collectAsState()

    val hazeState = remember { HazeState() }
    val showBottomBar = currentRoute != "account_details" && currentRoute?.startsWith("post/") != true

    val isPostDetailOpen = currentRoute?.startsWith("post/") == true
    val isDrawerOpen = drawerState.isOpen

    val drawerBlurRadius by animateDpAsState(
        targetValue = if (isDrawerOpen) 24.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "DrawerFrostedBlur"
    )

    val globalBlurRadius by animateDpAsState(
        targetValue = if (isPostDetailOpen) 16.dp else drawerBlurRadius,
        animationSpec = tween(durationMillis = 300),
        label = "GlobalFrostedGlassBlur"
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.Transparent, // 🌟 Transparent container for translucent glass side menu
                modifier = Modifier.width(280.dp)
            ) {
                // 🌟 Custom Translucent Glass Container for Side Menu
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.88f),
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.80f)
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            ),
                            shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        Text(
                            text = userHandle,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(16.dp))

                    NavigationDrawerItem(
                        label = { Text("Account Details", fontWeight = FontWeight.Medium, color = Color.White) },
                        icon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color.White) },
                        selected = false,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            navController.navigate("account_details")
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent,
                            selectedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Log Out", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error) },
                        icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        selected = false,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            authViewModel.signOut()
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent,
                            selectedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 🌟 Perf: attach the expensive full-tree blur ONLY while a radius is active.
            //    A permanently-attached blur node re-rasterizes the whole scaffold every
            //    frame during the drawer/post transitions (major jank source on low-end GPUs).
            val scaffoldModifier = if (globalBlurRadius > 0.dp) {
                Modifier.blur(radius = globalBlurRadius)
            } else {
                Modifier
            }

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                modifier = scaffoldModifier
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = "feed",
                    modifier = Modifier
                        .padding(innerPadding)
                        .hazeSource(state = hazeState)
                ) {
                    composable("feed") {
                        GlobalFeedScreen(
                            viewModel = feedViewModel,
                            themeViewModel = themeViewModel,
                            onNavigateToProfile = { clickedUserId ->
                                navController.navigate("profile?userId=$clickedUserId")
                            },
                            onNavigateToSearch = {
                                navController.navigate("search")
                            }
                        )
                    }

                    composable(
                        route = "profile?userId={userId}",
                        arguments = listOf(navArgument("userId") {
                            type = NavType.StringType
                            nullable = true
                        })
                    ) { backStackEntry ->
                        val userId = backStackEntry.arguments?.getString("userId")
                        ProfileScreen(
                            authViewModel = authViewModel,
                            feedViewModel = feedViewModel,
                            themeViewModel = themeViewModel,
                            userId = userId,
                            onBack = { navController.popBackStack() },
                            onNavigateToSettings = { navController.navigate("account_details") },
                            onOpenDrawer = {
                                coroutineScope.launch { drawerState.open() }
                            }
                        )
                    }

                    composable("account_details") {
                        AccountDetailsScreen(
                            viewModel = authViewModel,
                            themeViewModel = themeViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("search") {
                        UserSearchScreen(
                            feedViewModel = feedViewModel,
                            themeViewModel = themeViewModel,
                            onBack = { navController.popBackStack() },
                            onUserClick = { clickedUserId ->
                                navController.navigate("profile?userId=$clickedUserId")
                            }
                        )
                    }

                    dialog(
                        route = "post/{postId}?commentId={commentId}",
                        arguments = listOf(
                            navArgument("postId") { type = NavType.StringType },
                            navArgument("commentId") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            }
                        ),
                        deepLinks = listOf(
                            navDeepLink {
                                uriPattern = "https://auth-agora.info/post/{postId}?commentId={commentId}"
                            },
                            navDeepLink {
                                uriPattern = "https://auth-agora.info/post/{postId}"
                            },
                            navDeepLink {
                                uriPattern = "agora://post/{postId}?commentId={commentId}"
                            },
                            navDeepLink {
                                uriPattern = "agora://post/{postId}"
                            }
                        ),
                        dialogProperties = DialogProperties(
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false
                        )
                    ) { backStackEntry ->
                        // 🌟 Destination-scoped ViewModel: postId/commentId arrive through
                        //    SavedStateHandle (nav args), and the post is fetched by id —
                        //    so a notification deep link works even when the feed hasn't
                        //    loaded the post, without mutating FeedViewModel state.
                        val postDetailViewModel: PostDetailViewModel = viewModel(
                            viewModelStoreOwner = backStackEntry
                        )
                        SinglePostScreen(
                            viewModel = postDetailViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToProfile = { clickedUserId ->
                                navController.navigate("profile?userId=$clickedUserId")
                            },
                            onPostChanged = { updated -> feedViewModel.syncPostState(updated) }
                        )
                    }
                }
            }

            // 🌟 MAXIMIZED FROSTED GLASS FLOATING NAVIGATION ISLAND
            if (showBottomBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            // 1. Background Heavy Blur Layer
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(62.dp)
                    .hazeChild(state = hazeState, shape = CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.25f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        CircleShape
                    )
            )

            // 2. Foreground Crisp Icons Layer
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(62.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home Tab
                IconButton(
                    onClick = {
                        navController.navigate("feed") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (currentRoute == "feed") Icons.Filled.Home else Icons.Outlined.Home,
                        contentDescription = "Home",
                        tint = if (currentRoute == "feed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Create Post Island Button
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { showComposeScreen = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Create",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Profile Tab
                val isProfileTab = currentRoute?.startsWith("profile") == true && navBackStackEntry?.arguments?.getString("userId") == null
                IconButton(
                    onClick = {
                        navController.navigate("profile") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isProfileTab) Icons.Filled.Person else Icons.Outlined.Person,
                        contentDescription = "Profile",
                        tint = if (isProfileTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // --- INSTAGRAM-STYLE UPLOAD BANNER ---
        AnimatedVisibility(
            visible = uploadState !is UploadState.Idle,
            enter = slideInVertically(initialOffsetY = { -it - 100 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it - 100 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (val state = uploadState) {
                                is UploadState.Uploading -> state.message
                                is UploadState.Success -> state.message
                                is UploadState.Error -> state.message
                                is UploadState.Idle -> ""
                            },
                            fontWeight = FontWeight.Bold,
                            color = when (uploadState) {
                                is UploadState.Error -> MaterialTheme.colorScheme.error
                                is UploadState.Success -> Color(0xFF4CAF50)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (uploadState is UploadState.Uploading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (uploadState as? UploadState.Uploading)?.progress ?: 1f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.LightGray
                        )
                    }
                }
            }
        }
    }

    // --- FULL SCREEN CREATE POST DIALOG ---
    if (showComposeScreen) {
        CreatePostDialog(
            feedViewModel = feedViewModel,
            themeViewModel = themeViewModel,
            onDismiss = { showComposeScreen = false }
        )
    }
}
}
}
