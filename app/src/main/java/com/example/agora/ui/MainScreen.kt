package com.example.agora.ui

import android.os.Build
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
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
import coil.request.ImageRequest
import coil.request.listener
import com.example.agora.data.supabaseClient
import com.example.agora.model.Post
import com.example.agora.navigation.DeepLinkRouter
import com.example.agora.ui.theme.AgoraAccentGradient
import com.example.agora.ui.theme.AgoraRingGradient
import com.example.agora.ui.theme.AgoraType
import com.example.agora.ui.theme.rememberAgoraColors
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ✦ AGORA NOIR — APP SHELL
 *
 * The navigation host, deep-link routing, drawer, upload banner and frosted
 * focus-blur choreography are preserved exactly; everything visual is rebuilt
 * in the Noir language:
 *  - an obsidian drawer panel with an identity header and chip-icon rows,
 *  - a floating morphing navigation pill — icons at rest, labels spring
 *    open on the selected tab — around a gradient create FAB with a halo,
 *  - a status-tinted upload capsule with micro-labels and a rounded progress
 *    track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    authViewModel: AuthViewModel,
    themeViewModel: ThemeViewModel = viewModel(),
    feedViewModel: FeedViewModel = viewModel(key = supabaseClient.auth.currentUserOrNull()?.id)
) {
    val colors = rememberAgoraColors()
    val context = LocalContext.current
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
    // 🌟 Drawer identity flows from AuthViewModel.profileState — the VM owns
    //    the Supabase fetch (single source of truth) and the UI just collects
    //    it. Refresh re-fires on every route change, so an avatar edited in
    //    Account Details appears the moment the user navigates back.
    val drawerProfile by authViewModel.profileState.collectAsState()
    val userHandle = drawerProfile?.handle?.let { "@$it" } ?: "@user"
    // Strict normalization: a blank avatar URL counts as "no avatar", so the
    // initial-fallback logic stays honest.
    val userAvatarUrl = drawerProfile?.avatarUrl?.takeIf { it.isNotBlank() }

    LaunchedEffect(currentRoute) {
        authViewModel.refreshProfile()
    }

    var showComposeScreen by remember { mutableStateOf(false) }
    val uploadState by feedViewModel.uploadState.collectAsState()

    val hazeState = remember { HazeState() }

    // ✦ VISIBILITY RULES: the pill lives on the top-level tabs ONLY (Feed and
    //    your own Profile). Search, Account Details, other users' profiles and
    //    post deep-dives go full-bleed — driven by currentBackStackEntryAsState.
    val isOwnProfileTab = currentRoute?.startsWith("profile") == true &&
        navBackStackEntry?.arguments?.getString("userId") == null
    val showBottomBar = currentRoute == "feed" || isOwnProfileTab

    val isPostDetailOpen = currentRoute?.startsWith("post/") == true

    // ✦ DRAWER FROST, DRAG-FRACTION DRIVEN: no chained animateDpAsState, no
    //    waiting for the settle animation. The sheet reports its true slide
    //    position every drag frame (layout pass); the blur reads that fraction
    //    in the DRAW phase via graphicsLayer — instant, zero recompositions,
    //    zero CPU-blocking bitmap work. RenderEffect blur is API 31+; older
    //    devices skip it entirely and lean on the deeper dark scrim instead.
    val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var drawerFraction by remember { mutableFloatStateOf(0f) }
    val drawerScrimAlpha = if (supportsBlur) 0.45f else 0.60f

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        // ✦ Deep glass scrim — on API 31+ the content behind is frosted by the
        //    drag-fraction blur; older devices get the heavier scrim as the
        //    sleek dark fallback.
        scrimColor = Color.Black.copy(alpha = drawerScrimAlpha),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color.Transparent, // ✦ Noir panel paints its own surface
                modifier = Modifier
                    .width(292.dp)
                    // ✦ TRUE open fraction: the pane travels -width..0 while it
                    //    slides/drags, so (x + w) / w is exact drag progress —
                    //    written in the layout pass, read in the draw pass.
                    .onGloballyPositioned { coords ->
                        val w = coords.size.width.toFloat()
                        if (w > 0f) {
                            drawerFraction =
                                ((coords.positionInWindow().x + w) / w).coerceIn(0f, 1f)
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp))
                        // ✦ Translucent glass slab (was near-opaque 0.98f) — the blurred
                        //    feed shimmers through while text stays perfectly legible.
                        .background(colors.cardSurface.copy(alpha = 0.82f))
                        .border(
                            width = 1.dp,
                            color = colors.cardBorder,
                            shape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
                        )
                ) {
                    // ── Identity header ─────────────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(AgoraRingGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(colors.cardSurface),
                                contentAlignment = Alignment.Center
                            ) {
                                // 🌟 Dynamic avatar: the logged-in user's real profile
                                // picture, read from the fetched Profile state. The
                                // initial badge stays painted UNDERNEATH as the strict
                                // fallback — it only remains visible when the remote URL
                                // is null/empty or Coil reports an error (failures are
                                // logged to Logcat under "AgoraDrawerAvatar"). Coil
                                // decodes async, so a slow network simply keeps the
                                // initial on screen until the photo crossfades in.
                                Text(
                                    text = userHandle.trimStart('@').take(1).uppercase(),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    color = colors.accent
                                )
                                if (!userAvatarUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(userAvatarUrl)
                                            .crossfade(true)
                                            // 🌟 Diagnostics: if the photo ever fails to
                                            //    decode/fetch, the reason lands in Logcat
                                            //    and the initial underlay stays visible.
                                            .listener(onError = { _, result ->
                                                Log.e(
                                                    "AgoraDrawerAvatar",
                                                    "Coil avatar load failed",
                                                    result.throwable
                                                )
                                            })
                                            .build(),
                                        contentDescription = "Your profile picture",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = userHandle,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-0.4).sp,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "AGORA MEMBER",
                            style = AgoraType.MicroLabel,
                            color = colors.textTertiary
                        )
                    }

                    // ✦ Seam, not a divider: hairline that fades to nothing at
                    //    both ends — structure without any harsh lines.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(1.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, colors.hairline, Color.Transparent)
                                )
                            )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    DrawerNavItem(
                        label = "Account Details",
                        icon = Icons.Rounded.Person,
                        tint = colors.textSecondary,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            navController.navigate("account_details")
                        }
                    )

                    DrawerNavItem(
                        label = "Log Out",
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        tint = colors.danger,
                        onClick = {
                            coroutineScope.launch { drawerState.close() }
                            authViewModel.signOut()
                        }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // ── Quiet footer ────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(top = 12.dp, bottom = 22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AGORA · V1.0",
                            style = AgoraType.MicroLabel,
                            color = colors.textTertiary.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ✦ Hardware-accelerated frost: a RenderEffect blur applied in the
            //    DRAW phase (graphicsLayer), driven directly by the drawer's
            //    slide fraction — or a constant 16dp while a post detail dialog
            //    is open. No animated-Dp chain lagging behind the gesture, no
            //    blur-node attach/detach re-rasterization, and on API < 31 the
            //    layer is skipped entirely (the dark scrim carries the look).
            val scaffoldModifier = if (supportsBlur) {
                Modifier.graphicsLayer {
                    val radius = if (isPostDetailOpen) 16f else drawerFraction * 22f
                    renderEffect = if (radius > 0.01f) {
                        val px = radius.dp.toPx()
                        // TileMode.Clamp == clipped-rect semantics (no soft
                        // transparent vignette at the screen edges).
                        BlurEffect(px, px, TileMode.Clamp)
                    } else {
                        null
                    }
                }
            } else {
                Modifier
            }

            Scaffold(
                containerColor = colors.canvasTop,
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
                            hazeState = hazeState,
                            onNavigateToProfile = { clickedUserId ->
                                navController.navigate("profile?userId=$clickedUserId")
                            },
                            onNavigateToSearch = {
                                navController.navigate("search")
                            },
                            onCreatePost = { showComposeScreen = true }
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
                        dialogProperties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
                    ) { backStackEntry ->
                        // 🌟 Destination-scoped ViewModel: postId/commentId arrive through
                        //    SavedStateHandle (nav args), and the post is fetched by id —
                        //    so a notification deep link works even when the feed hasn't
                        //    loaded the post, without mutating FeedViewModel state.
                        val postDetailViewModel: PostDetailViewModel = viewModel(
                            viewModelStoreOwner = backStackEntry
                        )

                        // Edit-from-detail: host the feed's editor here so a post
                        // opened via notification deep link gets full edit parity.
                        var detailPostToEdit by remember { mutableStateOf<Post?>(null) }

                        SinglePostScreen(
                            viewModel = postDetailViewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToProfile = { clickedUserId ->
                                navController.navigate("profile?userId=$clickedUserId")
                            },
                            onPostChanged = { updated -> feedViewModel.syncPostState(updated) },
                            onDeletePost = { id -> feedViewModel.deletePost(id) },
                            onReportPost = { id, reason -> feedViewModel.reportPost(id, reason) },
                            onEditPost = { post -> detailPostToEdit = post }
                        )

                        detailPostToEdit?.let { post ->
                            EditPostDialog(
                                post = post,
                                feedViewModel = feedViewModel,
                                onDismiss = {
                                    detailPostToEdit = null
                                    // Re-fetch the post + comments so the edited
                                    // content shows immediately on the detail screen.
                                    postDetailViewModel.retry()
                                }
                            )
                        }
                    }
                }
            }

            // ✦ FLOATING MORPHING NAVIGATION PILL — slides and fades with route
            //    changes instead of snapping in/out of existence.
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(
                    animationSpec = tween(220)
                ) { it } + fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val isHome = currentRoute == "feed"
                val isProfileTab = currentRoute?.startsWith("profile") == true &&
                    navBackStackEntry?.arguments?.getString("userId") == null
                val pillShape = RoundedCornerShape(percent = 50) // true pill: semicircle ends at any height

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        // ✦ Floating dock margins — the pill hovers above the feed.
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            // ✦ Floating glass dock: compressed horizontal footprint —
                            //    80% of the width inside the 16dp side margins, so the
                            //    pill reads tighter without touching its vertical glass
                            //    styling. The weighted rail cells keep home | create |
                            //    profile evenly distributed (SpaceEvenly equivalent).
                            .fillMaxWidth(0.8f)
                            .height(72.dp)
                            .shadow(
                                elevation = 22.dp,
                                shape = pillShape,
                                spotColor = Color.Black.copy(alpha = if (colors.isDark) 0.55f else 0.20f),
                                ambientColor = Color.Black.copy(alpha = 0.12f)
                            )
                            .clip(pillShape)
                            // ✦ True frosted glass: Haze replays the feed behind the
                            //    pill through a RenderEffect blur (API 31+; scrim
                            //    fallback below). The default style tints are cleared
                            //    so nothing washes the blur out — our own gradients
                            //    supply the colour, icons/border stay razor sharp,
                            //    and pre-31 devices keep the fallbackTint legibility.
                            .hazeEffect(state = hazeState) {
                                blurEnabled = true
                                blurRadius = 40.dp
                                backgroundColor = Color.Transparent
                                tints = emptyList()
                            }
                            // ✦ Theme-aware frosted glass slab — one vertical gradient
                            //    carries base + convex tint. Dark: highly translucent
                            //    noir (0.42→0.60) so the feed shimmers through the bar.
                            //    Light: clean frosted white glass (0.70→0.40) with zero
                            //    black stops — no more solid-white-plus-black-smudge.
                            .background(
                                Brush.verticalGradient(
                                    colorStops = if (colors.isDark) arrayOf(
                                        0f to Color(0xFF2B2F40).copy(alpha = 0.42f),
                                        0.5f to Color(0xFF1B1E2A).copy(alpha = 0.50f),
                                        1f to Color(0xFF10121B).copy(alpha = 0.60f)
                                    ) else arrayOf(
                                        0f to Color.White.copy(alpha = 0.70f),
                                        0.55f to Color.White.copy(alpha = 0.55f),
                                        1f to Color.White.copy(alpha = 0.40f)
                                    )
                                )
                            )
                            // ✦ Top-lit convex sheen. Dark keeps the subtle white→black
                            //    curvature; light gets a pure white highlight that fades
                            //    to nothing (the blackish falloff is removed entirely).
                            .background(
                                Brush.verticalGradient(
                                    colorStops = if (colors.isDark) arrayOf(
                                        0f to Color.White.copy(alpha = 0.14f),
                                        0.45f to Color.White.copy(alpha = 0.03f),
                                        1f to Color.Black.copy(alpha = 0.22f)
                                    ) else arrayOf(
                                        0f to Color.White.copy(alpha = 0.35f),
                                        0.5f to Color.White.copy(alpha = 0.08f),
                                        1f to Color.White.copy(alpha = 0f)
                                    )
                                )
                            )
                            // ✦ Top-lit inner stroke: bright hairline at the top edge
                            //    fading to FULLY transparent at the bottom of the pill.
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0f to Color.White.copy(alpha = 0.45f),
                                        0.5f to Color.White.copy(alpha = 0.10f),
                                        1f to Color.Transparent
                                    )
                                ),
                                shape = pillShape
                            )
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NavRailItem(
                            selected = isHome,
                            modifier = Modifier.weight(1f),
                            label = "Home",
                            icon = Icons.Outlined.Home,
                            selectedIcon = Icons.Filled.Home,
                            onClick = {
                                navController.navigate("feed") {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )

                        // ── Gradient create FAB — inset inside the dock ──
                        var fabPressed by remember { mutableStateOf(false) }
                        val fabScale by animateFloatAsState(
                            targetValue = if (fabPressed) 0.90f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "FabScale"
                        )
                        Box(
                            modifier = Modifier.padding(horizontal = 2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    // ✦ Fits entirely inside the 72dp dock bounds —
                                    //    14dp of vertical breathing room per side.
                                    .size(44.dp)
                                    .scale(fabScale)
                                    .shadow(
                                        elevation = 8.dp,
                                        shape = CircleShape,
                                        spotColor = Color(0xFF6366F1).copy(alpha = 0.45f),
                                        ambientColor = Color(0xFF8B5CF6).copy(alpha = 0.20f)
                                    )
                                    .clip(CircleShape)
                                    // ✦ Create-button colour deliberately kept from the
                                    //    previous design: the violet→fuchsia gradient.
                                    .background(AgoraAccentGradient)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onPress = {
                                                fabPressed = true
                                                tryAwaitRelease()
                                                fabPressed = false
                                            },
                                            onTap = { showComposeScreen = true }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Create post",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        NavRailItem(
                            selected = isProfileTab,
                            modifier = Modifier.weight(1f),
                            label = "Profile",
                            icon = Icons.Outlined.Person,
                            selectedIcon = Icons.Filled.Person,
                            onClick = {
                                navController.navigate("profile") {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }

            // ✦ UPLOAD STATUS CAPSULE
            AnimatedVisibility(
                visible = uploadState !is UploadState.Idle,
                enter = slideInVertically(
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                ) { -it - 60 } + fadeIn(tween(200)),
                exit = slideOutVertically(
                    animationSpec = tween(durationMillis = 220)
                ) { -it - 60 } + fadeOut(tween(180)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 14.dp, start = 16.dp, end = 16.dp)
            ) {
                val statusLabel = when (uploadState) {
                    is UploadState.Uploading -> "UPLOADING"
                    is UploadState.Success -> "POSTED"
                    is UploadState.Error -> "FAILED"
                    is UploadState.Idle -> ""
                }
                val statusTint = when (uploadState) {
                    is UploadState.Uploading -> colors.accent
                    is UploadState.Success -> colors.success
                    is UploadState.Error -> colors.danger
                    is UploadState.Idle -> colors.textTertiary
                }
                val statusMessage = when (val state = uploadState) {
                    is UploadState.Uploading -> state.message
                    is UploadState.Success -> state.message
                    is UploadState.Error -> state.message
                    is UploadState.Idle -> ""
                }

                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = colors.cardSurface.copy(alpha = 0.96f),
                    border = BorderStroke(1.dp, colors.cardBorder),
                    shadowElevation = 16.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Status medallion
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(statusTint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            when (uploadState) {
                                is UploadState.Uploading -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = colors.accent,
                                    strokeWidth = 2.dp
                                )
                                is UploadState.Success -> Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = colors.success,
                                    modifier = Modifier.size(19.dp)
                                )
                                is UploadState.Error -> Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = colors.danger,
                                    modifier = Modifier.size(19.dp)
                                )
                                is UploadState.Idle -> Spacer(modifier = Modifier.size(19.dp))
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = statusLabel,
                                style = AgoraType.MicroLabel,
                                color = statusTint
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = statusMessage,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (uploadState is UploadState.Uploading) {
                                Spacer(modifier = Modifier.height(9.dp))
                                LinearProgressIndicator(
                                    progress = { (uploadState as? UploadState.Uploading)?.progress ?: 1f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = colors.accent,
                                    trackColor = colors.hairline,
                                    strokeCap = StrokeCap.Round
                                )
                            }
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

/**
 * A bottom-nav rail item — strictly icon-only, filling an even share of the
 * floating glass dock. The touch target is a 48dp IconButton wrapping just
 * the glyph — Material's ripple spec draws a tight circular splash, never a
 * half-bar highlight. The active tab reads through the filled glyph in
 * accent tint; idle tabs sit in a lighter neutral tint. `label` remains as
 * the accessibility content description.
 */
@Composable
private fun NavRailItem(
    selected: Boolean,
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    selectedIcon: ImageVector,
    onClick: () -> Unit
) {
    val colors = rememberAgoraColors()
    val tint by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textSecondary,
        animationSpec = tween(durationMillis = 220),
        label = "NavRailTint"
    )

    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ✦ Strictly bounded touch target: an IconButton wraps just the glyph,
        //    so Material's own ripple spec applies — a tight circular splash
        //    (~48dp) directly behind the icon. No parent container (Column /
        //    weighted cell) carries a clickable anymore, so the highlight can
        //    never fill a half-bar rectangle again.
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp)
        ) {
            // Reference design: no glow bloom — the active tab reads purely
            // through the filled glyph in accent tint against the light idle tint.
            // Glyph proportions matched to the reference dock (24dp in 72dp).
            Icon(
                imageVector = if (selected) selectedIcon else icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/** A drawer row: chip-mounted icon + label, full-width press target. */
@Composable
private fun DrawerNavItem(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // ✦ 12dp outer + 12dp inner = icon chips start at 24dp — flush with
            //    the identity header, so the whole panel shares one gutter.
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(tint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}
