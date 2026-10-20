# Agora — Engineering Notes

Three workstreams landed together: **(1) notification → post deep-link routing**,
**(2) Compose/ExoPlayer performance refactor**, **(3) single-device login policy**.

---

## 1. Notification routing (`agora://post/{postId}`)

### What was broken

| Layer | Problem |
|---|---|
| Client → DB | `notifications.data` never contained `post_id` — the Edge Function could never route to a post (`customData.post_id` was always `""`). |
| Edge Function | `broadcast-post` omitted `post_id` entirely; `push-notification` didn't forward `sender_id`/`reporter_id` (self-action filter dead code). |
| PendingIntent | Built with `Intent(ACTION_VIEW, https://…)` (implicit-ish, no parent stack), `FLAG_ACTIVITY_CLEAR_TOP/SINGLE_TOP` only — no `TaskStackBuilder`. |
| `MainActivity` | `injectDeepLink` only read `?post_id=` **query params**, so `https://agora.in.net/post/{id}` path links parsed to nothing. Warm taps (`onNewIntent`) set `intent.data` but nothing ever told the NavController — hence "opens the home screen". |
| `SinglePostScreen` | Looked the post up in `FeedViewModel.posts` only — a cold-start deep link to a post outside the loaded feed showed "Post not found or loading…" forever. |

### Architecture now

```
notification tap / banner tap / share link
        │
        ▼
DeepLinkRouter (singleton, sticky StateFlow<PendingPostLink>)   ◄── MainActivity.injectDeepLink (onCreate + onNewIntent)
        │                                                             parses extras AND both URI shapes
        ▼
MainScreen LaunchedEffect → navController.navigate("post/{id}?commentId=…") {
    popUpTo(graph.findStartDestination()) { saveState = true }   // back → feed
    launchSingleTop = true
} …skipped if that post is already open (dedupe vs NavHost's native navDeepLink path)

dialog("post/{postId}?commentId={commentId}") {
    PostDetailViewModel(viewModelStoreOwner = backStackEntry)    // nav args via SavedStateHandle
    SinglePostScreen(viewModel = postDetailViewModel, …)
}
```

- **PendingIntent** (`PushNotificationService`): explicit `MainActivity` +
  `Intent.ACTION_VIEW` + `agora://post/{postId}[?commentId=…]` data + extras,
  wrapped in `TaskStackBuilder.addNextIntentWithParentStack(...)` so the system
  back stack is preserved. Request codes are `postId.hashCode()` (dedupe instead
  of `currentTimeMillis()`).
- **NavHost**: `navDeepLink` patterns for `agora://post/{postId}`, the `https://agora.in.net/post/{postId}`
  app links and the `commentId` variants are kept; the router is deterministic and
  idempotent on top, so whichever mechanism fires first wins and the other stands down.
- **ViewModel scoping**: `PostDetailViewModel` is created against the dialog
  destination's `NavBackStackEntry`. `postId`/`commentId` come from its
  `SavedStateHandle` (survives process death), the post is fetched **by id** on
  `Dispatchers.IO`, and its `@Immutable` `PostDetailUiState` is fully isolated
  from `FeedViewModel`. Likes mirror back via `FeedViewModel.syncPostState(post)`
  (no fetch, no conflict).

### Notification payload contract (kept in `utils/NotificationHelper.kt`)

`notifications.data` (jsonb) → Edge Function `data` → FCM `message.data`:

| key | purpose |
|---|---|
| `post_id` | deep link target `agora://post/{post_id}` |
| `comment_id` | optional scroll-to-comment + highlight |
| `sender_id` / `author_id` / `reporter_id` | self-action filter in `PushNotificationService.onMessageReceived` |

**Note:** `addComment` currently sends `post_id` but not `comment_id` (the insert
doesn't return the generated comment id). If you want comment-level deep links,
either change the insert to `insert(...) { select() }` to read back the id, or
tell me your `comments.id` generation strategy and I'll wire it.

---

## 2. Performance audit → fixes

### Compose recomposition & stability
- **Findings:** `Post`, `AuthorProfile`, `Comment`, `Profile`, `ReactorDetails`,
  `PostLike`, `EditMediaItem`, `UploadState` were *unstable* (plain data classes
  with `List` fields). Every `PostCard` was unskippable — any list emission
  recomposed every card. `Post.timeAgo` re-parsed ISO timestamps in `get()`
  (ran per recomposition). `fetchPostsFromCloud` emitted a brand-new list every
  30 s auto-refresh and flipped `_isLoading` each time (global recomposition
  churn). Two full-tree `Modifier.blur` nodes were permanently attached and
  animated (re-rasterizing the whole screen per frame on low-end GPUs).
- **Fixes:**
  - `@Immutable` on all model/UI-state classes (lists are treated as stable;
    all fields are `val`-only).
  - `Post.timeAgo`/`imageUrls`/etc. precomputed once per instance in the body
    initializer (recomputed on `copy()`).
  - `fetchPostsFromCloud`: `_isLoading` only for first load; list emission is
    skipped when the merged result equals the current list.
  - Blur modifiers are attached **only while a radius > 0** (feed comments sheet,
    post detail / drawer transitions).
  - Compose compiler metrics are now available on demand:
    `./gradlew :app:assembleDebug -PenableComposeCompilerMetrics=true`
    → `app/build/compose_compiler/` (`*-composables.txt` for skippability,
    `*-module.json` for unstable declarations).

### ExoPlayer & media
- **Findings:** every inline video built + `prepare()`d its own `ExoPlayer` and
  released it on dispose — create/destroy churn on every scroll; no pre-caching.
- **Fixes:**
  - `FeedPlayerPool`: free-list pool (2 warm players) of app-context players
    bound to the shared `VideoCache` SimpleCache. Never steals from in-use
    cells; destroyed only when returned to a full free list. Global session
    mute (`FeedPlayerPool.isMuted`) replaces per-item `rememberSaveable` mute.
  - `FeedVideoPlayer` acquires on composition, hands the player back on dispose
    (`PlayerView` detaches in `onRelease`).
  - `VideoPreloader`: pushes the **next 1–2 videos** through a `CacheDataSource`
    (first ~3 MB each) into the SimpleCache on `Dispatchers.IO`; re-armed on
    scroll (`firstVisibleItemIndex`), previous batch cancelled — a fast scroll
    never queues downloads.

### Network & state
- All Supabase calls in `AuthViewModel`, `MainScreen` and `PostDetailViewModel`
  are now explicitly dispatched on `Dispatchers.IO` (`FeedViewModel` already was).
- No network calls run inside composable bodies; nothing that can loop lives in
  composition scope.
- `FeedViewModel.currentUserId` stays a cheap auth lookup (in-memory).

### App startup
- Removed the **hard 800 ms splash delay**; the splash now releases as soon as
  `SessionStatus` leaves `Initializing` (fail-safe at 1.5 s). Typical cold start
  is ~0.8–1.5 s faster.
- `initializeSupabase` stays synchronous **by design** (`PushNotificationService`
  can run before any Activity; the global is `lateinit`) but performs no I/O;
  Coil's `ImageLoader` is factory-lazy; FCM token sync / topic subscribe / feed
  fetch all run post-auth.

---

## 3. Single-device login

### SQL — run this in Supabase (also in `backend/supabase/migrations/…single_device_login.sql`)

```sql
alter table public.profiles
  add column if not exists current_device_id text;

-- realtime must publish the table for postgres_changes subscriptions
do $$
begin
  alter publication supabase_realtime add table public.profiles;
exception
  when duplicate_object then null;
end $$;

-- RLS: users manage their own row only (skipped if you already have equivalents)
do $$
begin
  if not exists (select 1 from pg_policies
                 where schemaname='public' and tablename='profiles'
                   and policyname='profiles_select_own') then
    create policy profiles_select_own on public.profiles
      for select to authenticated using (auth.uid() = id);
  end if;
  if not exists (select 1 from pg_policies
                 where schemaname='public' and tablename='profiles'
                   and policyname='profiles_update_own') then
    create policy profiles_update_own on public.profiles
      for update to authenticated using (auth.uid() = id) with check (auth.uid() = id);
  end if;
end $$;
```

(The migration file also backfills `current_device_id` from the legacy
`active_session_id` column when present, and adds an index.)

### App flow

1. **Device identity** — `DeviceIdProvider`: UUID generated once per install,
   persisted in `SharedPreferences("agora_device")`, initialized in `AgoraApplication`.
2. **Login claim** — after `signIn` (email), `signInWithGoogle`, and
   `saveOnboardingDetails` (registration completion) the app writes
   `profiles.current_device_id = <deviceId>` (**latest login wins** → Device A is
   kicked). `claimDeviceOnNextSession` is set *before* the auth call so the
   session collector can't race and misread the previous device's claim.
   Cold starts **never steal**: they adopt the claim only when the column is
   empty (legacy rows), otherwise they raise the conflict immediately.
3. **Watchers** — `AuthViewModel.listenForDeviceChanges`:
   - fast path: Supabase Realtime `postgres_changes` UPDATE on the user's
     `profiles` row (filter `id = userId`);
   - fallback: 5 s polling (covers your "Realtime is disabled for billing"
     situation — if Realtime is down, policy still holds).
   On a mismatch (`current_device_id` non-null and ≠ local) →
   `SessionConflictRelay.notifySessionRevoked()` — a **global SharedFlow**.
4. **UI** — `MainActivity` observes `AuthViewModel.remoteLogoutEvent` and shows a
   **non-dismissible** `AlertDialog` (`dismissOnBackPress/OnClickOutside = false`)
   with *"You have been logged in on another device"*. The session is **not**
   touched until the user taps **OK** (or a 5 s auto-timeout): then
   `confirmRemoteLogout()` → `supabase.auth.signOut()` + local user caches cleared
   + back to Login (the whole NavHost unmounts — full back stack pop).
   The claim-release on sign-out is guarded (`where current_device_id = <ours>`),
   so a kicked device can never clear the new device's claim.
5. Realtime record reads treat `JsonNull` as null (a released claim must not
   fake a mismatch).

### Assumptions (tell me if any are wrong and I'll adjust)
- `notifications.data` is `jsonb` and is what the `push-notification` Edge
  Function receives as `customData` (matches the code you shipped).
- `profiles` has RLS allowing `authenticated` users to `select`/`update` their
  own row (the SQL above creates policies only when missing).
- Supabase Realtime may be unavailable (your disabled-flag comments); hence the
  polling fallback is part of the design, not an afterthought.

---

## File map

**New:** `navigation/DeepLinkRouter.kt`, `viewmodel/PostDetailViewModel.kt`,
`media/FeedPlayerPool.kt`, `media/VideoPreloader.kt`, `data/DeviceIdProvider.kt`,
`service/SessionConflictRelay.kt`,
`backend/supabase/migrations/20990101000000_single_device_login.sql`

**Reworked:** `service/PushNotificationService.kt`, `MainActivity.kt`,
`ui/MainScreen.kt`, `ui/SinglePostScreen.kt`, `ui/FeedScreen.kt`,
`ui/FeedVideoPlayer.kt`, `viewmodel/FeedViewModel.kt`, `viewmodel/AuthViewModel.kt`,
`model/*.kt`, `utils/NotificationHelper.kt`, `AgoraApplication.kt`,
`app/build.gradle.kts`, both Edge Functions.

## Verify manually (I could not compile here — no JDK/SDK in this sandbox)

1. `./gradlew :app:assembleDebug` — then `adb shell am start -a android.intent.action.VIEW -d "agora://post/<uuid>"`
   while logged in: post dialog must open over the feed; Back → feed.
2. Send yourself a notification (insert into `notifications` with `data:
   {"post_id": "…", "sender_id": "<other-user>"}`), background the app, tap it.
3. Log in on a second device/emulator with the same account: within ~5 s the
   first device must show the non-dismissible dialog, then land on Login.

---

## 5. Catbox playback: black screen + 0-byte `media3_video_cache`

### Attempt 1 — User-Agent (necessary, but not sufficient)

Media3's `DefaultHttpDataSource` identifies itself as `ExoPlayerLib/<version>`, and
Catbox rejects that (and Coil's `okhttp/x.y.z`) with a **403 + HTML block page**.
That alone explains a black `PlayerView` with an empty cache: no bytes reach the
parser, so `cacheDir/media3_video_cache` stays at 0.

Fixed by sending a desktop Chrome UA from a single constant, shared by the player
upstream and Coil.

### Attempt 2 — the real failure was transport, not headers

The 403 hypothesis was disproved by the next device log:

```
Playback error: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED (code=2001)
  Caused by: HttpDataSourceException: IOException: unexpected end of stream
  Caused by: EOFException: \n not found: size=0 content=...
      at com.android.okhttp.internal.http.Http1xStream.readResponse(Http1xStream.java:203)
      at androidx.media3.datasource.DefaultHttpDataSource.makeConnection(DefaultHttpDataSource.java:553)
```

Read the depth of that trace: **zero response bytes**, no HTTP status line, no
`InvalidResponseCodeException`. The User-Agent was never rejected — the connection
was closed before any headers came back. Two things conspired:

1. `DefaultHttpDataSource` rides on `HttpURLConnection`, i.e. Android's *platform*
   repackaged OkHttp (`com.android.okhttp`, note the namespace — different code
   from the app's `okhttp3`). That stack reuses pooled sockets with no staleness
   detection and no retry, so a keep-alive connection the peer closed while idle
   detonates the next request exactly like this.
2. `files.catbox.moe` is itself widely blocked by ISPs/DNS resolvers (there is a
   whole userscript ecosystem that rewrites `files.catbox.moe` →
   `files.pixstash.moe` just to work around it). In that case the request dies
   before/at TCP and *no* header change can help.

### What the code does now

| File | Change |
|---|---|
| `media/MediaHttpClient.kt` **(new)** | One `OkHttpClient` for all remote media. `retryOnConnectionFailure` (fresh connection when a pooled one is dead) is the transport fix for failure (1); it also walks every resolved IP instead of only the first. Browser UA, timeouts tastefully short of a `callTimeout` (which would kill long progressive reads), and a debug-only request logger. Exposes `dataSourceFactory()` → `OkHttpDataSource.Factory` via `media3-datasource-okhttp`. |
| `media/VideoCache.kt` | The `CacheDataSource` upstream is now `MediaHttpClient.dataSourceFactory()`. Playback **and** `VideoPreloader` share it, so pre-cached bytes land in `SimpleCache`. |
| `media/ExoPlayerHelper.kt` | `onPlayerError` logs error code/message/cause, and now explicitly distinguishes *"host answered with HTTP nnn"* (status + headers + 512-byte body snippet) from *"host never answered"* (transport). Every failing URL triggers `MediaHttpClient.diagnoseOnFailure`. |
| `AgoraApplication.kt` | Coil uses the same client, so thumbnails share UA/timeouts/retry instead of carrying their own `okhttp/<v>` UA. |

### The failure probe (`adb logcat -s MediaHttp`)

A player error alone can't separate "blocked by the network" from "our bug" —
both are 2001. On failure the app now runs one debounced (30 s/URL) probe:

```
── playback failed for https://files.catbox.moe/x.mp4 — probing the network path ──
DNS files.catbox.moe → 108.181.20.35 (IPv4)
probe[files.catbox.moe] → FAILED after 10001ms — SocketTimeoutException: timeout
probe[files.pixstash.moe] → HTTP 206 (h2) in 240ms — content-type=video/mp4, …
DIAGNOSIS: files.catbox.moe is blocked on this network but files.pixstash.moe works
— this is an ISP/DNS-level block, not a User-Agent or app bug. Fixes, in order of
preference: (1) relay media through your own backend/domain, (2) enable
HOST_REWRITES in MediaHttpClient, (3) let the user use a VPN or DNS-over-HTTPS.
```

### Escaping a blocked host

`MediaHttpClient.HOST_REWRITES` maps host → host for every upstream request (the
cache key follows the rewritten URI). It ships **empty on purpose**: pushing user
media through a third-party mirror is a product/privacy decision, not a library
default. Recommended order:

1. **Relay through your own backend** (best): a Supabase Edge Function on
   `agora.in.net` that streams the object and caches it — user media then never
   touches a host their ISP may block, and you drop a dependency on Catbox for
   playback entirely. Requires a new Edge Function plus rewriting URLs at upload
   time.
2. `HOST_REWRITES = mapOf("files.catbox.moe" to "files.pixstash.moe")` — one line,
   works today, but delegates privacy to a community passthrough.
3. Ship the UA fix + probe and let the diagnostics prove the block before doing
   either.

### Known gap

This work was authored in an environment with no JDK or Android SDK, so it was
reviewed against the media3 1.11.1 sources but never compiled. The first
`assembleDebug` is the first real type-check — the first Kotlin-level mistake it
found (a missing `return` in `MediaHttpClient`'s request interceptor, where a
block-bodied function relied on a trailing expression) is fixed.

### Verify manually

1. Play a Catbox video (feed, post detail, fullscreen). It must render instead of
   going black; `adb shell run-as net.in.agora ls -l cache/media3_video_cache`
   must show non-zero files.
2. On failure: `adb logcat -s ExoPlayerHelper MediaHttp` prints (a) whether an HTTP
   status existed, and (b) the DNS/probe verdict naming the guilty layer.
3. Turn on Wi-Fi *and* mobile data (and a VPN) to see whether the verdict changes —
   a block that disappears on one path is an ISP block, not an app bug.

---

## 6. Posts blank / black on a *different* device

### Symptom

Media posted from device A renders there, but on device B (fresh install, same account)
the post shows no image and a black video frame.

### Why it looked device-specific

Device A renders from **cache** — Coil's disk/memory cache for images, `SimpleCache` for
video — so it keeps working even when the media host is unreachable. Device B has an empty
cache and must fetch from `files.catbox.moe`, which is exactly the host failing in §5. Two
separate defects made this worse:

1. **`FeedViewModel.createPost` dropped failed uploads silently.** Uploads are mapped with
   `async { CatboxClient.uploadBytes(...) }.awaitAll().filterNotNull()`, so an upload that
   fails (very likely when Catbox is blocked) simply disappears, and the post row is still
   inserted with `media_urls = []`. The author sees their local preview; every other device
   sees a post with no media — **permanently**, because the bytes were never stored anywhere.
2. **`PostMediaCarousel` used `AsyncImage` with no error slot**, so a failed load renders
   nothing at all — indistinguishable from "post has no media". A black `PlayerView` is the
   video equivalent of the same silence.

### Fix

| File | Change |
|---|---|
| `viewmodel/FeedViewModel.kt` | `createPost` and `savePostChanges` now **abort and tell the user** when any media upload fails (`UploadState.Error` + a `FeedUpload` Logcat line), instead of persisting a post with lost media. |
| `ui/PostMediaCarousel.kt` | Images load through `SubcomposeAsyncImage` with explicit loading/error states: failures show a "Media unavailable" placeholder, and the URL + throwable are logged under tag `PostMedia`. A blank box can no longer masquerade as a post without media. |

### The durable fix (not yet implemented)

Both §5 and this section point at the same conclusion: **stop storing user media on
Catbox.** The app already requires Supabase to be reachable for everything else, so media
hosted in Supabase Storage has no *new* failure mode, whereas Catbox adds a host that is
blocked on many networks (and is a third party that can delete files).

There is already a partial precedent in the codebase: `editPost` uploads to the
`post-media` bucket (falling back to `post_images`) and stores `publicUrl(...)`, while
`createPost` still posts to Catbox — so the same app already produces both kinds of URLs.

Recommended migration:
1. Point `createPost` at Supabase Storage (same two-bucket fallback as `editPost`).
2. Add a `MediaStorage` seam so the upload target is one decision in one file.
3. Backfill: posts whose `media_urls` still reference `files.catbox.moe` can be re-hosted
   lazily on first successful fetch, or left to whatever `HOST_REWRITES` route is chosen.

---

## 7. Theme follows the device, and Light Mode is readable

### Symptom

A fresh install opened in Dark Mode on a phone set to Light Mode; after Log Out the
Login/Registration screens kept whatever theme the account last used; and in Light Mode
the auth cards, input borders, labels and the "Enter the social arena." subtitle were
washed out to near-invisibility.

### Root cause

`ThemeViewModel` persisted **two** values: a `theme_preference` string (default `SYSTEM`)
and a boolean `is_dark_mode` that defaulted to `true` and was the only thing
`MainActivity` actually read. `SYSTEM` also mapped to `true` inside
`setThemePreference`. So "no preference" and "follow system" both resolved to dark, and
the boolean outlived logout because nothing cleared it.

Light Mode contrast was a token problem: `outline` was `#D9DAE0` (≈1.3:1 on white),
fields used `outline @ 40%`, subtitles used the same muted grey as dark mode, and the
`agora` wordmark faded to 66% alpha on a near-white canvas.

### Fix

| Layer | Change |
|---|---|
| `data/ThemePreference.kt` | New single source of truth. `SYSTEM` = **no stored key** = follow the device; only `LIGHT`/`DARK` are explicit overrides. The legacy `is_dark_mode` key is deleted on first read. |
| `ThemeViewModel` | Pure facade over the store — no duplicated state, no derived `isDarkMode` flow. |
| `MainActivity` | Effective theme = `preference.resolveIsDark(isSystemInDarkTheme())`; `applyWindowTheme()` sets `isAppearanceLightStatusBars`/`NavigationBars` **and** the window background before the first frame and on every change. |
| Logout | Drawer, Account Details, remote-logout dialog and the `Authenticated → signed-out` transition all call `resetToSystem()`. Cold starts never reset. |
| `Theme.kt` / tokens | Light outline `#AEB3BF`, secondary text `#4B505A`; light cards get a soft shadow; auth fields get an inset fill + solid border; wordmark is solid ink in Light Mode. |
| `res/values*` | `windowBackground` / splash background come from `agora_window_background` (#F8F9FA light, #0B0C13 dark) so launch → first Compose frame never flashes. |

### Verify manually

1. Fresh install on a Light-mode device → Login renders light, status bar icons dark.
2. Toggle to Dark in Account Details → bars flip, then Log Out → back to Light.
3. Change the OS theme while the app is foregrounded (no override) → UI and bars follow.
4. Light mode: field borders, labels and both subtitles are legible at a glance.

---

## 8. Under-18 banner must require a real Date of Birth

`DateOfBirth.validAgeOrNull()` now returns `null` for `null`/blank/unparsable values
instead of flowing into `Period.between`, and every screen gates the banner through one
predicate — `DateOfBirth.shouldShowMinorNotice(dob)` — which requires a *valid* date
inside `[today − 120y, today]` and an age of `0..17`. A blank, malformed, future or
120-year-plus DOB can never render "Minor account notice". See `DateOfBirthTest`.

### The banner was dark-only

`MinorAccountWarningBanner` shipped with four hardcoded dark-mode colours and no
light branch: a 95%-opaque `#1A1710` surface, cream `#F7EDDC` body text, a
`#FFD48A` title and an 8dp shadow. On the pearl canvas it rendered as a black
slab with an amber halo — the one element on the screen belonging to the other
theme. It is now theme-resolved through `MinorBannerPalette` exactly the way
`authSubtitleColor(isDark)` / `agoraAuthFieldColors(isDark)` handle the split:

| token | dark | light |
|---|---|---|
| surface | `#1A1710` @ 95% | `#FFF7E6` cream |
| accent (border · icon) | amber-500 `#F59E0B` | amber-700 `#B45309` |
| title | `#FFD48A` | amber-800 `#92400E` (6.7:1) |
| body | `#F7EDDC` | warm ink `#5A4A32` (8.1:1) |
| shadow | 8 dp | 2 dp |

Dark mode is byte-for-byte unchanged. Light mode keeps the notice legible
without the black card, and `isDark` is a parameter (defaulting to
`LocalDarkTheme.current`) so a preview can force either mode.

---

## 9. Production release configuration (applicationId, targeting, R8)

### What was wrong

| Symptom | Root cause |
|---|---|
| Play Console rejects the upload / logcat shows `target_sdk_version=37` | The template was generated against API 37, which is not a shippable stable target. Google Play requires a released API level for `targetSdk`. |
| App installs as `com.example.agora` | The template default `applicationId` shipped unchanged. `com.example.*` is not an owned namespace and cannot be published. |
| Release build shrank **nothing** | `buildTypes.release` had `optimization { enable = false }` and there was no `proguard-rules.pro` at all, so the release APK was an unoptimized, unshrunk debug-equivalent artifact. |

### Decisions

**`applicationId = "net.in.agora"`, `namespace` stays `com.example.agora`.**
The namespace is the Kotlin package *and* the `R` class package. Decoupling it from
the app id means the Play identity changes without moving a single source file, and
every `R.id.*` / `BuildConfig.*` reference keeps resolving. The price is one rule that
must never be forgotten: **R8 keep rules match the namespace, not the applicationId.**

> ⚠️ Changing `applicationId` makes `net.in.agora` a *new* app to Play. Installs of
> `com.example.agora` cannot be upgraded in place; the old listing must be retired or
> kept as a separate track.

**`targetSdk = 36`, `compileSdk` stays on 37.** Compiling against the newest platform
is fine (AGP 9.4 supports up to API 37) — what Play validates is `targetSdk`, which is
pinned to stable Android 16. Bump this deliberately, never by regenerating the template.

**`versionCode = 1` / `versionName = "1.0.0"`.** Semver for a first store release;
`versionCode` is monotonic forever, so it only ever increases from here.

**R8 on, release non-debuggable.** `isDebuggable = false`, `isMinifyEnabled = true`,
`isShrinkResources = true`, with `proguard-android-optimize.txt` (the non-optimizing
`proguard-android.txt` is rejected outright by AGP 9+). AGP 9.3+ also offers the newer
`optimization { enable = true }` DSL, but the legacy DSL is still fully supported and is
the documented way to reference a single `proguard-rules.pro`; the 9.3+ `.keep` source
set is still experimental.

**Keep rules (`app/proguard-rules.pro`).** The release build must not crash by
reflection, so the file protects: kotlinx.serialization (`$$serializer`, `Companion`,
`serializer()`, `@SerialName` fields), `com.example.agora.model.**`, supabase-kt, Ktor,
Credential Manager + Google ID, and Firebase Messaging. Note there are `@Serializable`
DTOs outside `model/` (`utils/DisifyResponse`, `viewmodel/PostInsertRequest`,
`PostUpdateRequest`) — the `@Serializable`-conditional rules cover them by annotation,
not by package.

**Manifest.** `enableOnBackInvokedCallback="true"` (predictive back on 33+),
`usesCleartextTraffic="false"` (all endpoints are HTTPS; this is a hard floor, not a
suggestion). `MainActivity` is `exported="true"` because it owns MAIN/LAUNCHER and the
`agora://` + `https://agora.in.net/post/` filters; `PushNotificationService` is
`exported="false"` because only Play Services may bind to it. No provider exists today —
if a `FileProvider` is ever added, its authority must be `${applicationId}.fileprovider`,
never a literal.

**Backups.** `allowBackup` stays `true` so theme settings survive a device restore, but
`data_extraction_rules.xml` / `backup_rules.xml` now exclude `agora_session.xml`
(Supabase access/refresh tokens), `agora_device.xml` (the single-device UUID) and
`agora_verification.xml` (device-local grace state). Backing up a refresh token hands it
to anyone with access to the user's cloud backup, and restoring the device UUID to a
second handset would let two devices claim the same install identity.

**Open item — Firebase.** `app/google-services.json` now carries *two* client entries so
the Google Services plugin resolves either id. The `net.in.agora` entry still points at
the old `mobilesdk_app_id`; register the Android app in the Firebase console and swap in
the real file before the first production build, otherwise FCM analytics conflate the two
packages.

---

## 10. Google Sign-In aborts after account selection (error 28444)

### Symptom

The Google account sheet appears, the user picks an account, the sheet closes and
the app reports:

```
androidx.credentials.exceptions.GetCredentialCancellationException:
    activity is cancelled by the user
```

or, on other Play Services versions, `10: [28444] Developer console is not set up
correctly` / `16: Account reauth failed`. The user never cancelled anything.

### What the message actually means

Credential Manager has exactly one bucket for "the picker closed without giving
me a credential", and Google uses it for *both* a real dismissal **and** every
rejection of the calling app. `[28444]`, `[16] Account reauth failed` and
`not registered to use OAuth2.0` are all Google refusing to mint a token, not a
user action — Google deliberately does not tell the app which check failed.

Google validates a Google ID token request against **three independent things**:

| # | Requirement | Where it lives |
|---|---|---|
| 1 | `serverClientId` is an OAuth client of type **Web application** | `GOOGLE_WEB_CLIENT_ID` |
| 2 | It belongs to the **same Google Cloud project** as `google-services.json` | project number `1025610780303` |
| 3 | That project has an **Android** OAuth client for `applicationId` + the SHA-1 of the certificate that signed the **installed** APK | Google Cloud Console |

Anything wrong in 1–3 produces the "cancel".

### Root cause in this repo (configuration-side)

The code was correct in shape — `GetGoogleIdOption` → `GoogleIdTokenCredential`
→ `supabase.auth.signInWith(IDToken) { provider = Google }` — but two
configuration facts made it impossible to succeed:

1. **The Web client id belonged to a different Google Cloud project.** The
   hardcoded value was `925021938686-…`, while `google-services.json` declares
   project `1025610780303`. An OAuth client id's numeric prefix *is* its project
   number, so this is provable from the repository alone.
2. **The owning project has no OAuth clients at all.** `google-services.json`
   now carries a real Firebase Android app for `net.in.agora`
   (`mobilesdk_app_id 1:1025610780303:android:00130b70a449f1bb83aafa`), but its
   `"oauth_client"` array is still **empty**. That array is populated by the
   Firebase tooling from the project's OAuth clients, so an empty array means
   project `1025610780303` holds neither the Web client the app sends nor an
   Android client for `net.in.agora` + the signing SHA-1. Both halves of the
   handshake are missing, from the same cause.
3. **The Android OAuth client was registered for the old package.** OAuth
   Android clients are keyed on `applicationId` + signing SHA-1. `applicationId`
   moved from `com.example.agora` to `net.in.agora` (§9), so any client created
   for the old package stopped matching the moment the id changed.

Neither is fixable in Kotlin. What *was* fixable is that the failure was
invisible: the catch block mapped every `GetCredentialException` to
"Google Sign-In was canceled.", and `handleAppError()` returned the generic
message for the rest.

### Fix

| Layer | Change |
|---|---|
| `app/build.gradle.kts` | New `BuildConfig.GOOGLE_WEB_CLIENT_ID`, overridable with `GOOGLE_WEB_CLIENT_ID` in `local.properties` / `-P`. Documents all three requirements next to the value. |
| `LoginScreen` | No longer passes a literal client id; calls `signInWithGoogle(context)`. |
| `AuthViewModel.signInWithGoogle` | Default parameter is `BuildConfig.GOOGLE_WEB_CLIENT_ID`. Rejects a non-`*.apps.googleusercontent.com` id *before* the picker. Logs a one-line preflight (package, runtime SHA-1, client id, client project number vs Firebase project number). |
| `AuthViewModel` error handling | Classifies on the **payload**: `[28444]`, `account reauth failed`, `developer_error`, `not registered to use oauth` → `ErrorMessages.GOOGLE_CONFIG_ERROR` (a real, honest message). Genuine dismissal still says "canceled". `NoCredentialException`, `GetCredentialProviderConfigurationException`, `GetCredentialUnsupportedException`, `GetCredentialInterruptedException` each get their own copy. |
| `AuthViewModel` session flags | `claimDeviceOnNextSession` / `hasCompletedColdStartCheck` / `sessionCreatedInThisRun` are now set *after* the Supabase exchange succeeds instead of before it. |
| `proguard-rules.pro` | Added keeps for `com.google.android.gms.auth.api.identity.**` and the `GoogleIdTokenCredential` `CREATOR`/`KEY_*` fields. |

### The client-side fix you must still do (Google Cloud Console)

1. Google Cloud Console → APIs & Services → Credentials, in project
   **agora-application / 1025610780303**.
2. Create (or open) an OAuth client of type **Android**:
   package name `net.in.agora`, plus the SHA-1 of **every** certificate you
   install with — debug keystore, upload key and **Play App Signing key**
   (Play Console → App integrity).
3. Create (or open) an OAuth client of type **Web application**. Use its client
   id as `GOOGLE_WEB_CLIENT_ID`.
4. Supabase dashboard → Authentication → Providers → Google: *Authorized Client
   IDs* must contain that same Web client id (it is the `aud` Supabase verifies).
5. Wait a few minutes — OAuth client changes are not instant, and a
   `net.in.agora` client takes up to a few hours to propagate.

### Verify

```bash
# 1. what the app thinks it is
adb logcat -c && adb shell am start -n net.in.agora/com.example.agora.MainActivity
adb logcat -s GoogleAuth
# -> preflight package=net.in.agora sha1=… serverClientId=…(project …) firebaseProject=1025610780303

# 2. the SHA-1 of the artifact actually installed
keytool -printcert -jarfile app/build/outputs/apk/debug/app-debug.apk
# or: ./gradlew signingReport

# 3. token exchange, independent of the app
curl -s "https://oauth2.googleapis.com/tokeninfo?id_token=<token>" | jq '{aud, azp, email}'
#    aud  = your WEB client id      (must match GOOGLE_WEB_CLIENT_ID)
#    azp  = your ANDROID client id  (must exist in the project)
```

A healthy run logs `preflight …` and then a Supabase session; a broken OAuth
client now logs `MISCONFIGURATION: … project … but … project …` and shows
"Google Sign-In isn't available in this build" instead of a fake cancellation.

### Deep-link / domain migration (`agora.in.net`)

Every occurrence of the two legacy `auth-agora` origins was replaced
repo-wide by `agora.in.net`, and the App Links filter was widened:

- one verified `https://agora.in.net` host, three path prefixes (`/post/`,
  `/auth-callback`, `/login-callback`) — previously **only** `/post/` was
  declared, so an `https://` auth redirect could never open the app;
- **two** custom schemes in the same filter: `agora://` (what the app emits —
  FCM payloads, notification intents, NavHost deep links, every link already
  sent to users) and `net.in.agora://` (the applicationId-derived form). A
  manifest that declares only `net.in.agora` while `MainActivity`,
  `PushNotificationService` and `MainScreen` still build `agora://` URIs
  silently orphans every existing notification;
- Kotlin reads the host from `DeepLinkRouter.WEB_HOST` (single constant);
- `backend/setup_agora_config.ps1` writes `site_url = https://agora.in.net` and
  `uri_allow_list = ['agora://**', 'net.in.agora://**', 'https://agora.in.net/**']`.

Operational follow-ups that live outside the repository:

- `https://agora.in.net/.well-known/assetlinks.json` must list `net.in.agora`
  with the Play App Signing SHA-256, or Android opens the browser.
- Keep the **previous** web origin in the Supabase redirect allow-list until
  already-emailed magic links have expired, otherwise those links 404. Only
  `agora.in.net` is referenced by code, the manifest and the setup script.

---

## 11. Shared `https://agora.in.net/post/{id}` links

### Two separate failures, only one of them in the code

| Symptom | Layer | Cause |
|---|---|---|
| The link opens Chrome instead of Agora | **Platform** | App Links verification. `autoVerify` only takes effect when `https://agora.in.net/.well-known/assetlinks.json` lists `net.in.agora` with the SHA-256 of the certificate that signed the *installed* APK. Nothing in this repo can fix that — see `docs/app-links/README.md` for the file, the three fingerprints to include and the `pm get-app-links` check. |
| The app opens but shows the feed | **Code** | The parser only understood `agora://post/{id}` and an exact-match `https://agora.in.net/…` host. `net.in.agora://` (declared in the manifest and allow-listed in Supabase) parsed to `null`; so did an uppercase host, a `www.` prefix, an `http://` downgrade and the `agora:///post/{id}` form Chrome produces when it normalises an unknown scheme. `parse()` returned null, `injectDeepLink` silently returned, and the user landed on the feed with no log line anywhere. |

### The parser now (`DeepLinkRouter`)

| Input | Result |
|---|---|
| `https://agora.in.net/post/{uuid}` | ✅ post |
| `https://AGORA.IN.NET/Post/{uuid}/?utm_source=…` | ✅ post (host + segment case-insensitive, trailing slash, tracking params ignored) |
| `https://www.agora.in.net/post/{uuid}` | ✅ post (parser only — the manifest declares the canonical host) |
| `http://agora.in.net/post/{uuid}` | ✅ post (defensive; the manifest only declares `https`) |
| `agora://post/{uuid}` · `agora:///post/{uuid}` | ✅ post |
| `net.in.agora://post/{uuid}` | ✅ post — **previously `null`** |
| `…?post_id={uuid}` | ✅ post (legacy share/query fallback) |
| Intent extras `post_id` / `comment_id` | ✅ post (FCM path, wins over `data`) |
| `https://agora.in.net/auth-callback?code=…` | ⛔ null — must reach `handleDeeplinks` untouched |
| `https://evil.example/post/{uuid}` · `https://agora.in.net.evil.example/…` | ⛔ null |
| `ftp:` · `intent:` · `mailto:` · relative URIs | ⛔ null |
| `…/post/{not-a-uuid}` | ⛔ null — `posts.id` is a `uuid` column, so navigating could only ever show "post not found" |

`parseUri` exists twice on purpose: an `android.net.Uri` overload for the app and
a `String` overload built on `java.net.URI`, so the whole decision table is
unit-tested on the JVM (`DeepLinkRouterTest`, 28 cases) with no Robolectric, plus
an on-device `DeepLinkIntentTest` (10 cases) that proves both overloads agree.

### Routing and diagnostics

- `MainActivity.injectDeepLink` now logs every launch under **`DeepLink`**:
  `adb logcat -s DeepLink` prints the action, the raw URI, the extras and the
  parsed result. **No `parsed` line means the intent never arrived** — i.e. the
  manifest filter did not match and the OS gave the URL to the browser. That
  single line separates the two failures above definitively.
- Debug builds also log Android's verification verdict
  (`app-links allowed=true hosts=[agora.in.net=verified]`), so an `assetlinks`
  problem is visible without `adb shell pm get-app-links`. The state comes from
  `DomainVerificationManager` (API 31+, via `getSystemService`) — there is no
  public `PackageManager.getDomainVerificationUserState`, so that call does not
  compile.
- Cold-start injection is gated on `savedInstanceState == null`: a rotation
  replays the original `ACTION_VIEW` intent, and re-submitting it could stack a
  second copy of the post screen on top of the restored one.
- `MainScreen` now navigates inside `try/catch` (a malformed route can no longer
  crash the app), logs the navigation, and deliberately omits `restoreState`
  (restoring a saved stack for the same destination could show a *different*
  post than the one just tapped).
- NavHost `navDeepLink` patterns gained the `net.in.agora://` variants, so the
  router and the NavHost can both handle exactly the same URL set.
