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
   going black; `adb shell run-as net.agora.app ls -l cache/media3_video_cache`
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

**`applicationId = "net.agora.app"` and `namespace = "net.agora.app"` — one identity,
and it has to be a legal Kotlin package.**

> ⚠️ The identity **cannot** be `net.in.agora`, the domain-derived spelling. `in` is a
> *hard keyword* in Kotlin, so `package net.in.agora` fails to parse:
>
> ```
> AgoraApplication.kt:1:13 Syntax error:
> Package name must be a '.'-separated identifier list.
> ```
>
> Column 13 is the `i` of `in`, and every `import net.in.agora.…` fails the same way.
> Java accepts the identical name (it is not a Java keyword), which is why an
> applicationId of `net.in.agora` would be perfectly valid — the restriction applies
> only to the Kotlin-visible package. Since the namespace must be a real package and
> the app should have **one** identity, both are now `net.agora.app`.
>
> **This is not catchable by source inspection or grammar parsers.** tree-sitter
> parsed all 98 files as valid — `in` is lexed as an ordinary `identifier`; keyword
> rejection is a compiler rule. `PackageDeclarationContractTest` encodes it so the
> mistake fails in `./gradlew test` instead of at the first line of the first file.

What `net.agora.app` is and what it is not:

| | value | why |
|---|---|---|
| public origin | `https://agora.in.net` | the web contract; independent of the app id |
| app identity | `net.agora.app` | `namespace` **and** `applicationId`; inverts the domain and drops the un-representable `in` segment |
| custom scheme | `net.agora.app://` | derived from the app identity, mirrors it exactly |
| retired | `com.example.agora`, `net.in.agora`, `auth-agora.info`, `auth-agora.online` | must not reappear |

Consequences that follow from having one identity:

- sources live under `app/src/{main,test,androidTest}/java/net/agora/app/**` and read
  `package net.agora.app…`;
- `R` and `BuildConfig` are generated into `net.agora.app`, so files in
  `net.agora.app.ui` import `net.agora.app.R` explicitly (sibling package, not parent);
- manifest components stay **relative** (`.MainActivity`) and resolve against the
  namespace;
- **R8 keep rules match the namespace**, which is now the same string as the app id —
  `proguard-rules.pro` uses `net.agora.app.**`. A stale prefix does not fail the build;
  it silently drops the `$$serializer` classes and throws `SerializationException` in
  release only.

> ⚠️ **This changes the Play identity again.** `net.agora.app` is a *new* app to Play
> and Android: nothing installed under `com.example.agora` or `net.in.agora` can be
> upgraded in place. Do this before the first store upload and it costs nothing;
> after it, everything below must be recreated:
>
> | external identity | action |
> |---|---|
> | `app/google-services.json` | add an **Android app for `net.agora.app`** in the Firebase console (project 1025610780303) and download the new file — the plugin fails the build with *"No matching client found for package name 'net.agora.app'"* until then. **Never hand-edit this file.** |
> | Google OAuth **Android** client | recreate with package name `net.agora.app` + the SHA-1 of every signing certificate (§10) |
> | `/.well-known/assetlinks.json` | list `net.agora.app`, not the old ids (`docs/app-links/`) |
> | Supabase redirect allow-list | `net.agora.app://**` (the setup script already writes it) |

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
`serializer()`, `@SerialName` fields), `net.agora.app.model.**`, supabase-kt, Ktor,
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
the Google Services plugin resolves either id. The app now ships as `net.agora.app`, and
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
   now carries a real Firebase Android app for the installed applicationId
   (`mobilesdk_app_id 1:1025610780303:android:00130b70a449f1bb83aafa`), but its
   `"oauth_client"` array is still **empty**. That array is populated by the
   Firebase tooling from the project's OAuth clients, so an empty array means
   project `1025610780303` holds neither the Web client the app sends nor an
   Android client for the installed applicationId + the signing SHA-1. Both halves of the
   handshake are missing, from the same cause.
3. **The Android OAuth client was registered for the old package.** OAuth
   Android clients are keyed on `applicationId` + signing SHA-1. `applicationId`
   changed twice, ending at `net.agora.app` (§9). OAuth clients key on the
   applicationId only, so every client created for `com.example.agora` or
   `net.in.agora` stopped matching the moment the id changed — the Kotlin
   namespace is irrelevant to Google, but the app id is the whole key.

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
   package name `net.agora.app`, plus the SHA-1 of **every** certificate you
   install with — debug keystore, upload key and **Play App Signing key**
   (Play Console → App integrity).
3. Create (or open) an OAuth client of type **Web application**. Use its client
   id as `GOOGLE_WEB_CLIENT_ID`.
4. Supabase dashboard → Authentication → Providers → Google: *Authorized Client
   IDs* must contain that same Web client id (it is the `aud` Supabase verifies).
5. Wait a few minutes — OAuth client changes are not instant, and a
   `net.agora.app` client takes up to a few hours to propagate.

### Verify

```bash
# 1. what the app thinks it is
adb logcat -c && adb shell am start -n net.agora.app/net.agora.app.MainActivity
#                                  ^app id and namespace are the same (see §9)
adb logcat -s GoogleAuth
# -> preflight package=net.agora.app sha1=… serverClientId=…(project …) firebaseProject=1025610780303

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

Every *generated* link now uses `agora.in.net`; the retired `auth-agora` origins
are no longer emitted anywhere. The App Links filter was widened:

- one verified `https://agora.in.net` host, four path prefixes (`/post/`,
  `/p/` — see below — `/auth-callback`, `/login-callback`) — previously **only**
  `/post/` was declared, so an `https://` auth redirect could never open the app;
- **two** custom schemes in the same filter: `agora://` (what the app emits —
  FCM payloads, notification intents, NavHost deep links, every link already
  sent to users) and `net.agora.app://` (the applicationId-derived form). A
  manifest that declares only `net.agora.app` while `MainActivity`,
  `PushNotificationService` and `MainScreen` still build `agora://` URIs
  silently orphans every existing notification;
- Kotlin reads the host from `DeepLinkRouter.WEB_HOST` (single constant);
- `backend/setup_agora_config.ps1` writes `site_url = https://agora.in.net` and
  `uri_allow_list = ['agora://**', 'net.agora.app://**', 'https://agora.in.net/**']`.

**`/p/{id}` — short alias.** Added to the verified filter (and to `DeepLinkRouter`
and the NavHost patterns) because a post link is pasted into chats, SMS and QR
codes, where the four characters of `/post/` are pure overhead. It is web-only:

| shape | accepted |
|---|---|
| `https://agora.in.net/p/{uuid}` | ✅ |
| `https://auth-agora.info/p/{uuid}` | ⛔ null — retired, see below |
| `agora://p/{uuid}` · `net.agora.app://p/{uuid}` | ⛔ — `p` would become the authority; the custom-scheme grammar stays `post`-only |

**Retired origins — rejected, not routed.** `auth-agora.info` was accepted for one
release as a courtesy to links already in circulation. It is now removed from the
parser and from the manifest:

- `DeepLinkRouter.WEB_HOSTS` is `{agora.in.net, www.agora.in.net}` — nothing else;
- no legacy filter exists in `AndroidManifest.xml`, so only one verified host is
  declared (which also keeps Android 8–11 domain verification safe: one
  unverifiable host in an `autoVerify` filter un-verifies every host in it);
- a link to a retired origin is the **server's** problem, not the app's: the domain
  must 301 to `agora.in.net`. That is one redirect for every platform at once, and
  it does not require shipping a build;
- `auth-agora.online` was never public and stays rejected.

Operational follow-ups that live outside the repository:

- `https://agora.in.net/.well-known/assetlinks.json` must list `net.agora.app`
  with the Play App Signing SHA-256, or Android opens the browser.
- Keep the **previous** web origin in the Supabase redirect allow-list until
  already-emailed magic links have expired, otherwise those links 404. Only
  `agora.in.net` is referenced by code, the manifest and the setup script.
- The retired `auth-agora.info` domain must keep serving a 301 redirect to
  `agora.in.net` for as long as those links are in circulation; the app-side
  filter is the safety net, not a substitute.

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
| `https://agora.in.net/p/{uuid}` | ✅ post — short alias, same UUID validation |
| `https://auth-agora.info/post/{uuid}` · `/p/{uuid}` | ⛔ null — retired origin, 301'd at the domain |
| `https://auth-agora.online/…` | ⛔ null — never public |
| `https://www.agora.in.net/post/{uuid}` | ✅ post (parser only — the manifest declares the canonical host) |
| `http://agora.in.net/post/{uuid}` | ✅ post (defensive; the manifest only declares `https`) |
| `agora://post/{uuid}` · `agora:///post/{uuid}` | ✅ post |
| `net.agora.app://post/{uuid}` | ✅ post — **previously `null`** |
| `…?post_id={uuid}` | ✅ post (legacy share/query fallback) |
| Intent extras `post_id` / `comment_id` | ✅ post (FCM path, wins over `data`) |
| `https://agora.in.net/auth-callback?code=…` | ⛔ null — must reach `handleDeeplinks` untouched |
| `https://evil.example/post/{uuid}` · `https://agora.in.net.evil.example/…` | ⛔ null |
| `ftp:` · `intent:` · `mailto:` · relative URIs | ⛔ null |
| `…/post/{not-a-uuid}` | ⛔ null — `posts.id` is a `uuid` column, so navigating could only ever show "post not found" |

`parseUri` exists twice on purpose: an `android.net.Uri` overload for the app and
a `String` overload built on `java.net.URI`, so the whole decision table is
unit-tested on the JVM (`DeepLinkRouterTest`, 35 cases) with no Robolectric, plus
an on-device `DeepLinkIntentTest` (12 cases) that proves both overloads agree —
including the `/p/` alias, the legacy host and both rejection cases.

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
- NavHost `navDeepLink` patterns gained the `net.agora.app://` variants, so the
  router and the NavHost can both handle exactly the same URL set.

---

## 12. Inline video escaping the post card (shared-post page)

**Symptom.** Open a post from a shared link (`SinglePostScreen`) and the video
preview is not contained by the media plate: the picture runs past the card's
rounded edge and covers the area below it — the "Comments" divider, the options
row, sometimes the neighbouring content. Images in the same carousel are fine.

### Root cause — two independent decisions that are individually reasonable

1. **`PlayerView` is created with `RESIZE_MODE_ZOOM`** (`FeedVideoPlayer`).
   That is the right call visually: it center-crops the video so it fills the
   360 dp plate exactly like a `ContentScale.Crop` photo, with no pillarbox
   bars. But "zoom" is implemented by making the **view larger than its cell**,
   not by scaling inside it. From media3 1.11.1
   `AspectRatioFrameLayout.onMeasure`:

   ```java
   case RESIZE_MODE_ZOOM:
     if (aspectDeformation > 0) width  = (int) (height * videoAspectRatio);
     else                       height = (int) (width  / videoAspectRatio);
   ...
   super.onMeasure(MeasureSpec.makeMeasureSpec(width,  EXACTLY),
                   MeasureSpec.makeMeasureSpec(height, EXACTLY));
   ```

   In the 360 dp square plate the arithmetic is:

   | source | measured frame | overhang |
   | --- | --- | --- |
   | 9:16 portrait (1080x1920) | 360 x 640 dp | **+280 dp vertical** |
   | 3:4 portrait (720x960) | 360 x 480 dp | +120 dp vertical |
   | 1:1 square | 360 x 360 dp | none |
   | 16:9 landscape | 640 x 360 dp | +280 dp horizontal |

   `exo_player_view.xml` gives that frame `layout_gravity="center"`, so the
   overhang is split evenly on two edges.

2. **The surface is a `SurfaceView`** (media3's default `surface_type`).
   A `SurfaceView` is not drawn by the view hierarchy: it is a separate
   `SurfaceControl` layer composited by SurfaceFlinger and merely *positioned*
   from its view's frame. Consequently **nothing in the view tree can clip it** —
   not `clipChildren`, not `clipToOutline` on an ancestor, and not Compose's
   `Modifier.clip()`, which is a `graphicsLayer` clip that never reaches an
   interop view's own surface. `PostMediaCarousel`'s
   `.clip(RoundedCornerShape(22.dp))` therefore clips the *background* and the
   *border*, and the layer above the plate, but not the video layer.

Net effect: 640 dp tall video, 360 dp tall plate, no clip → 280 dp of picture
outside the card.

### Why it showed up on the shared-post page

Nothing in the code path differs between the two screens — `PostCard` is the
same composable in `FeedScreen` and `SinglePostScreen`, and both call the same
`PostMediaCarousel` → `FeedVideoPlayer`. The defect exists in the feed too.
What differs is how much attention the overhang attracts:

- On the detail page the card is the whole screen: a uniform background above
  the plate and the `Comments` header immediately below it, so 140 dp of video
  landing there is unmistakable.
- In the feed the same overhang falls on the next card's media plate, which is
  dark and busy, and reads like a pager/shadow artifact.
- The direction depends on the video: portrait sources overhang vertically,
  landscape sources horizontally. A feed tested with landscape clips never
  produces the "leaking out of the bottom" shape at all.

The fullscreen viewer is unaffected because `ExpandedVideoScreen` keeps the
default `RESIZE_MODE_FIT` in a full-screen dialog where there is no plate to
escape.

### Fix

| option | verdict |
| --- | --- |
| `RESIZE_MODE_FIT` | ✘ letterbox bars — images crop, videos would not; visual regression |
| `RESIZE_MODE_FILL` | ✘ stretches the picture (changes aspect ratio) |
| Custom `FrameLayout` that measures the child with `EXACTLY` | ✘ fights media3 internals, breaks on video-size changes |
| SurfaceView + `setEnableSurfaceClipping` / API-dependent surface clipping | ✘ platform-dependent, not honored by all OEM compositors, no rounded outline |
| **`surface_type="texture_view"` + self-clip outline** | **✔ chosen** |

`res/layout/view_inline_video_player.xml` inflates a `PlayerView` with
`app:surface_type="texture_view"`. A `TextureView` is ordinary view content: it
is drawn into the parent's canvas, so `ClipChildren` and the outline clip both
apply. `FeedVideoPlayer` then sets `clipToOutline = true` with a
`ViewOutlineProvider` that mirrors the plate's 22 dp radius — the same
`PlateCornerRadius` constant `PostMediaCarousel` uses, so the two clips cannot
drift apart. `invalidateOutline()` is called from a layout listener and from
`AndroidView.update` because the outline is derived from the view's size.

Everything else is deliberately untouched: pool ownership, mute state,
`ON_PAUSE` handling, the centered play button and
`onVideoClick → fullscreen` all behave exactly as before.

**Trade-off.** `TextureView` costs an extra GPU copy per frame and slightly more
power than `SurfaceView`. That is the right trade for one small, silent,
non-autoplaying preview cell that must be clipped to a rounded shape; the
fullscreen player keeps `SurfaceView` because it is full-bleed and needs no
clip.

**Rule going forward.** `PlayerView` never clips itself. Any inline (non
full-bleed) player must be inflated with `surface_type="texture_view"` and must
set `clipToOutline` to the container's shape — or it must not use
`RESIZE_MODE_ZOOM`.

### Files

- `app/src/main/res/layout/view_inline_video_player.xml` — new; `PlayerView`
  with `surface_type="texture_view"`, `use_controller="false"`, black
  background, rationale in comments.
- `app/src/main/java/com/example/agora/ui/FeedVideoPlayer.kt` — inflates that
  layout, keeps `RESIZE_MODE_ZOOM`, adds `clipToOutline` +
  `roundedOutline(cornerRadius)`, new optional `cornerRadius: Dp = 22.dp`
  parameter.
- `app/src/main/java/com/example/agora/ui/PostMediaCarousel.kt` — `PlateCornerRadius`
  constant shared by the plate shape and the player.
- `app/src/main/java/com/example/agora/ui/PostVideoPlayer.kt` — unused, now
  documented so it cannot be wired up with the same bug.

### Verify by hand

1. Shared link → post with a **portrait** (9:16) video: picture must stop at the
   360 dp plate's rounded edge, on all four sides, and must not paint over
   `Comments`.
2. Same post in the feed: identical containment.
3. Landscape (16:9) video: no horizontal bleed past the plate.
4. Play/pause, mute toggle, rotation during playback, scroll the post away and
   back (pool reuse) — no black flash, no second surface, no stale frame.
5. Tap the preview: fullscreen player opens with controls, `fit`, unaffected.

---

## 13. Media pipeline: thumbnails, compression, deletion, cache ceilings

### The three symptoms and what actually caused them

| Symptom | Root cause |
|---|---|
| Cache hit 85 MB after three short videos | Two independent amplifiers. `VideoPreloader` read the first 3 MB of each of the next two videos on **every scroll position** — pure download with no user intent. Worse, `FeedPlayerPool.acquire()` was called from composition and immediately `prepare()`d a `ProgressiveMediaSource`, so every video cell that became *visible* pulled its MP4 container plus a decoder frame. Both wrote into `SimpleCache`, which was allowed to grow to **200 MB**, so nothing ever evicted. |
| Deleting a post left assets in Cloudinary | `FeedViewModel.deletePost` was `supabase.from("posts").delete { … }` and nothing else. The row was the only place the media URLs existed; once it was gone, the assets were unreachable *by design*. |
| Full-size media uploaded / downloaded | `compressImage` re-encoded at 80 % but never **downscaled** (a 12 MP photo stayed 12 MP), and every viewer rendered the original URL. No derived renditions existed anywhere. |

### 1. Thumbnail-first feed

`CloudinaryUrlHelper` turns one raw upload URL into the four renditions the UI
needs, by rewriting the segment after `/upload/`:

| Purpose | Transform | Served as |
|---|---|---|
| feed image | `f_auto,q_auto:eco,w_600` | ~30 KB WebP |
| full-screen image | `f_auto,q_auto:good,w_1440` | sharp at 5× zoom |
| video poster | `so_auto,f_auto,q_auto:eco,w_600` | ~20–40 KB **still** |
| video stream | `f_auto,q_auto:good,vc_auto,w_720,br_2m` | 720p H.264, ~2 Mbps |

Two properties are load-bearing:

- **The video poster is an image request, not a video request.** Cloudinary
  derives a frame server-side; the device receives a WebP. That is what makes
  "poster-only feed" cost ~30 KB instead of megabytes.
- **Transforms are idempotent and replace, never stack.** Applying
  `feedImageUrl` twice yields the same URL, and upgrading to the 1440 px variant
  replaces `w_600` rather than producing `w_600,w_1440`.

`FeedVideoPlayer` is now a `SubcomposeAsyncImage` of the poster plus a play
button. It contains **no player, no `PlayerView`, no ExoPlayer** — tapping calls
`onVideoClick`, which opens `ExpandedVideoScreen`; that screen builds its own
player against the compressed stream URL and releases it on dispose. A player
therefore exists only while someone is watching.

**Removed because they were the cache bug:** `VideoPreloader`, `FeedPlayerPool`,
the scroll-triggered prefetch in `FeedScreen`, the now-unused inline `PlayerView`
layout (`view_inline_video_player.xml`) and its contract test, plus two more dead
files (`PostVideoPlayer`, `CatboxClient`).

### 2. Compression before upload

`MediaCompressor` is the single entry point for both posts and avatars:

- **images** decode with `inSampleSize` (never a full-size bitmap), scale so the
  longest edge is ≤ **1440 px**, and re-encode to WebP at **80 %**;
- **videos** go through Media3 `Transformer` → H.264/AAC, height ≤ **720**;
- both fall back to the original bytes on failure — compression is an
  optimisation, never a reason a post fails.

**Temp files are deleted in a `finally`**, not on the success path. The old code
deleted after a successful export and again in the `catch`, which left the file
behind whenever the coroutine was cancelled — precisely when a user backs out of
a long transcode. `TempFileSweeper.sweepAsync()` runs at startup and removes
`upload_*` / `compressed_*` files older than an hour, covering the one case a
`finally` cannot: the process being killed mid-export.

### 3. Deleting the Cloudinary asset (server-side signing)

Deletion is the one media operation that needs the account **API secret**, so it
cannot happen on the device. It runs in the Supabase Edge Function
`backend/supabase/functions/delete-cloudinary-asset`:

1. The function verifies the caller's JWT (`auth.getUser`).
2. It loads the post and checks `posts.user_id = auth.uid()`.
3. It re-derives the **allowed** `public_id`s from that row's `media_urls`
   **and** `image_urls` — the list sent by the app is a hint, never a grant. Any
   id outside the post is rejected with `403 asset_not_owned_by_post`, so a
   logged-in attacker cannot destroy someone else's asset by guessing a
   `public_id`. Both columns are read because `image_urls` predates the media
   pipeline and is still dual-written; reading only `media_urls` would give an
   empty allowed set for legacy posts and reject their own assets.
4. Membership is the security boundary, not the client's `resource_type`: the
   server uses **its own** parse of the type, so a client-side parsing slip logs
   a failure instead of blocking the delete.
5. Only then does it sign and call
   `POST https://api.cloudinary.com/v1_1/<cloud>/<resource_type>/destroy` with
   `public_id`, `timestamp`, `invalidate=true`, `api_key` and a SHA-1 signature
   over the sorted parameters (excluding `api_key`/`file`), with the secret
   appended.
6. `ok` and `not found` both count as success (`not found` means the desired end
   state already holds). A partial run returns **207** with `destroyed` /
   `failed` arrays.

`public_id` is still derived by `CloudinaryUrlHelper.publicId`: it strips the
transform segment, the `v1234/` version and the extension, and keeps folder
prefixes. It is careful about lookalikes — `…/upload/my_photo.jpg` is a
`public_id`, not a transformation. `resource_type` is read from the path
(`/image/`, `/video/`, `/raw/`), never guessed from the extension. The function
duplicates both parsers deliberately: the server must not trust a client's idea
of which assets exist.

**Ordering — and why it is not "row first".** The client:

1. **captures** the post's media URLs and the `{public_id, resource_type}` pairs
   while the post is still in memory;
2. **invokes the function** while the row still exists, because step 2/3 above
   authorise against that row;
3. **deletes the row**;
4. **evicts local caches** (`MediaCacheCleaner`) and refreshes the feed.

Deleting the row first would leave the function with nothing to authorise
against: it can only answer `409 post_not_found`, and "trust the client" is the
vulnerability the function exists to prevent. The user-visible behaviour is
still the requested one — the asset call is awaited but its **failure never
blocks** the delete; a failure is logged under `CloudinaryDelete` for
reconciliation. If the remote call fails, the row is still removed and the asset
becomes an orphan, exactly as before, but the app has done everything it can.

`MediaCacheCleaner.evict()` drops the raw URL plus all four renditions
(`feedImageUrl`, `fullImageUrl`, `videoPosterUrl`, `optimizedVideoUrl`) from
Coil's disk and memory caches and from Media3's `SimpleCache`, keyed by the URL
string. Without it, a deleted post's photo can still render from cache and keeps
occupying the 60 MB budget.

Avatar replacement does the same for Supabase Storage: the previous object is
removed after the new URL is committed (best-effort, logged as `AvatarCleanup`).

### 🔑 Deploying the function (and where the secret lives now)

```bash
supabase secrets set \
  CLOUDINARY_CLOUD_NAME=<cloud name> \
  CLOUDINARY_API_KEY=<api key> \
  CLOUDINARY_API_SECRET=<api secret>
supabase functions deploy delete-cloudinary-asset
```

`SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are injected by the platform.
Invoke it to smoke-test:

```bash
supabase functions invoke delete-cloudinary-asset \
  --body '{"post_id":"<uuid>"}'
```

If the call fails, the status code says which link is broken:

| Response | Meaning |
|---|---|
| `401 missing_authorization` / `invalid_jwt` | the request had no usable user token |
| `403 not_post_owner` | valid token, post belongs to someone else |
| `403 asset_not_owned_by_post` | a `public_id` is not in this post's media columns |
| `409 post_not_found` | called after the row was deleted — ordering bug |
| `500 cloudinary_not_configured` | one of the three secrets is missing or misnamed |
| `200` / `207` | secrets work (`207` = some assets failed) |

**No Cloudinary key belongs in the app.** There is nothing to put in
`local.properties`: `CLOUDINARY_API_KEY` / `CLOUDINARY_API_SECRET` are no longer
BuildConfig fields, so they cannot be extracted from an APK. The previous
on-device signer (`CloudinaryClient.deleteFromCloudinary`) and its
`local.properties` keys were removed in this change.

### 4. Cache ceilings

| Cache | Ceiling | Where |
|---|---|---|
| Coil image disk cache | **25 MB** | `AgoraApplication.newImageLoader()` |
| Media3 `SimpleCache` | **35 MB** | `VideoCache.MAX_CACHE_SIZE_BYTES` |
| total | **~60 MB** | worst case, any device |

Coil's default is 2 % of free space (up to 250 MB), which on a 128 GB phone is
by itself larger than the whole cache budget — the explicit `DiskCache` is what
makes the ceiling real rather than incidental.

### ✅ Security: the secret is no longer in the APK

This section replaces the Task 10 warning. On-device signing put
`CLOUDINARY_API_SECRET` in `BuildConfig`, where a single `unzip` of the APK
recovers it, and that key can destroy **every** asset in the account. The
credential and the signing now live only in the `delete-cloudinary-asset` Edge
Function (see §3 above). The app ships no Cloudinary key at all, and the function
cannot be abused by a logged-in user to delete another user's media because the
allowed `public_id` set is re-derived from their own post row.

Residual risk is limited to the function itself: it runs with the service-role
key, so its code is the trust boundary. Keep the ownership check in place — do
not "simplify" it to trusting the client's `assets` array.

### Verify by hand

1. Cold start, scroll a feed with three videos: `adb shell du -sh cache` stays in
   the low-MB range; `adb logcat -s CloudinaryDelete` is silent (nothing to
   destroy).
2. Kill the network and scroll: posters fail into the black plate, the play
   button remains, nothing crashes.
3. Tap a video: fullscreen starts quickly (720p rendition) and `dumpsys media.player`
   shows one player; back out → it is released.
4. Post a photo > 1440 px: the uploaded `secure_url` reports `w_1440` and the
   size is a fraction of the original.
5. Delete that post: `CloudinaryDelete: post=… destroyed=… failed=0` in logcat,
   the asset is gone from the Cloudinary media library, and
   `adb logcat -s CloudinaryDelete` shows no `403`/`409`. A `409 post_not_found`
   means the function was called after the row was deleted — the ordering in §3
   is broken.
6. `adb shell ls cache/` after a cancelled upload shows no `compressed_*` files.

## 14. Task 11 audit: shared post links, cold and warm

Re-verified rather than rewritten — the deep-link path already did the right
thing, so no code changed. What was checked, in order:

**Manifest** (`app/src/main/AndroidManifest.xml`). `MainActivity` is
`exported=true`, `launchMode=singleTop`, and carries three filters:

| filter | scheme | host | paths | autoVerify |
|---|---|---|---|---|
| 0 | — (launcher) | — | — | — |
| 1 | `agora`, `net.agora.app` | — | — | — |
| 2 | `https` | `agora.in.net` | `/post/`, `/p/`, `/auth-callback`, `/login-callback` | `true` |

Only one host is in the verified filter, which matters: App Links verification is
all-or-nothing per filter, so adding `www.agora.in.net` or a retired host there
would silently de-verify the whole thing. `/post/` is a `pathPrefix`, so
`https://agora.in.net/post/<uuid>` is delivered to `MainActivity` directly —
there is no `pathPattern` mismatch to worry about, and the id itself is parsed by
the app, not by the manifest.

**Parsing** (`navigation/DeepLinkRouter.kt`). `parse()` accepts
`https://agora.in.net/post/{id}`, `/p/{id}`, `agora://post/{id}`,
`net.agora.app://post/{id}`, `agora:///post/{id}` (empty authority, which some
share targets produce) and the `?post_id=` query fallback. The id must pass
`isValidPostId` (UUID), so a malformed link is ignored instead of navigating to a
blank screen. `https` and `http` share the same branch, and the host comparison
is case-insensitive.

**Delivery** (`MainActivity` → `MainScreen`). `injectDeepLink` normalises the
result to `agora://post/{id}?commentId=…`, logs under `DeepLink`, and submits to
`DeepLinkRouter`. `MainScreen` observes it in a `LaunchedEffect` and navigates
with `popUpTo(startDestination) { saveState = true }` + `launchSingleTop` and
**no** `restoreState`, so a link always lands on that post rather than on the
last screen the user had open. If the post is already on screen it does not
navigate again. Then the router's slot is `consume()`d, which is what stops a
rotation from replaying the same navigation.

- **Cold start:** the intent is read only when `savedInstanceState == null`, so a
  configuration change re-creates the activity without re-submitting the link.
- **Warm start:** `onNewIntent` re-runs `injectDeepLink`; `singleTop` +
  `launchSingleTop` mean no second `MainActivity` is created.

**Checked and intentionally left alone:** the `agora` scheme with no host
(`agora:/post/{id}`), which `parse` also handles through the empty-authority
branch, and `net.agora.app://` — kept for compatibility with links already in
the wild from the identity migration, even though `https://agora.in.net` is the
canonical form.

## 15. Launcher icon, themed icon and notification icon

The launcher icon was still the **stock Android template**: a `#3DDC84` grid
background and the green robot foreground. The brand mark already existed in the
repo — as `agora_splash.xml` (vector) and `ic_logo.png` (finished artwork) — but
nothing connected it to `mipmap-anydpi-v26/`.

### What "the icon format" has to cover

Five separate outputs, and they are not interchangeable:

| Asset | Consumed by | Form |
|---|---|---|
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | launchers, API 26+ | `<adaptive-icon>` |
| `drawable/ic_launcher_background.xml` | the adaptive icon's back layer | vector |
| `drawable/ic_launcher_foreground.xml` | the adaptive icon's front layer | vector |
| `drawable/ic_launcher_monochrome.xml` | Android 13 themed icons | vector |
| `mipmap-*/ic_launcher{,_round}.webp` | launchers, API ≤ 25 | raster, 5 densities |

plus `drawable/ic_notification.xml` for the status bar.

### Geometry and colour, measured not guessed

Both were taken off `ic_logo.png` rather than eyeballed:

- **Gradient** `#5A57DC → #3228AD`, vertical (left and right edges sample the
  same, so it is not diagonal).
- **Glyph width = 63.7 % of the tile**. Only the central **72dp** of an adaptive
  layer is visible, so `0.637 × 72 ≈ 46dp` — that is where the foreground's
  46dp comes from, and it is why the launcher icon and the splash tile read as
  the same mark instead of one looking like a zoomed crop.
- **Corner radius ≈ 22 %** of the tile width, applied to the legacy rasters only
  (the adaptive background must stay full-bleed; the launcher applies its own
  mask, and a pre-rounded square leaves gaps behind a circle mask).

The background gradient ramps across `y = 18..90`, not `0..108`: ramping the
full 108dp canvas would put only the middle third of the ramp inside the visible
72dp window and wash the colour out.

### Two bugs fixed on the way

**The themed icon was a WebP.** `<monochrome>` pointed at
`@mipmap/ic_launcher_foreground`. Android draws themed icons from the layer's
**alpha channel**, so a raster has no transparent interior — the counter became
part of the silhouette and the icon rendered as a solid blob. It is now a
vector whose counter is a **hole** via `fillType="evenOdd"`. Even-odd is not a
style choice here: the background is a gradient, so a painted counter would need
its own copy of the gradient and would drift out of alignment at every density.
A hole shows whatever is behind it, at any size.

**The notification icon was the adaptive icon.** `setSmallIcon(R.mipmap.ic_launcher)`
is the classic mistake: the tray draws a small icon as a tinted alpha mask, and
an adaptive icon has no such mask, so it becomes a white square. Worse, when a
push carries a top-level `notification` block and the app is backgrounded, **FCM
renders the tray entry itself** — `PushNotificationService` never runs, and
`setSmallIcon` is irrelevant. Both paths are now covered:

- `setSmallIcon(R.drawable.ic_notification)` for the in-app path;
- `com.google.firebase.messaging.default_notification_icon` +
  `default_notification_color` in the manifest for the FCM path.

### The final assets, and the one correction they needed

The authoritative vectors are:

| File | Source |
|---|---|
| `drawable/ic_launcher_background.xml` | upstream artwork, **verbatim** |
| `drawable/ic_launcher_foreground.xml` | upstream artwork, **verbatim** |
| `drawable/ic_launcher_monochrome.xml` | upstream pathData, with two required changes |

They use a `512x512` viewport and `#4A49DF → #322EBA`, replacing the earlier
hand-fitted `#5A57DC → #3228AD` gradient — the measured palette was slightly off,
and the vector source is now the source of truth rather than `ic_logo.png`.

**The monochrome layer needed fixing, not formatting.** As supplied it drew the
white mark over an **opaque black 512x512 backdrop**, with the counter and the
accent painted opaque black. Themed icons are tinted by the system from the
layer's **alpha channel only**:

- the backdrop is opaque, so alpha is 255 across the whole square;
- black is still opaque, so the counter is not a hole;
- therefore the system fills the entire square with the tint and the "a"
  disappears. Measured: **0.551** of the layer was opaque.

Two changes, both dictated by the format:

1. **the backdrop path is removed** — nothing may be drawn where nothing shows;
2. **the counter and accent become holes** via `fillType="evenOdd"`. This is safe
   by measurement, not assumption: the counter is **100 %** inside the ring and
   the accent **100 %** inside the arc, so each is subtracted from its own parent
   and nothing else.

Everything else is byte-for-byte the supplied pathData. The only other edit is
that appended subpaths start with an **absolute `M`**: a relative `m` is relative
to `(0,0)` only as a path's *first* command, and would otherwise be offset by the
parent's end point — which put the counter at `(398,373)` instead of `(250,187)`.

After the correction the monochrome silhouette agrees with the supplied
foreground glyph to **0.08 %** of pixels, with identical bounding boxes.

Bleed is safe: the glyph reaches **0.825** of the visible-window radius, inside
the circle-mask radius (1.0) and the 66dp safe circle, so no launcher clips it.

### Guarded by a test

`app/src/test/java/net/agora/app/LauncherIconContractTest.kt` asserts every rule
above — monochrome is a vector, it uses even-odd with a single path, no source
file passes `R.mipmap.ic_launcher` to a notification builder, the FCM meta-data
exists, and all five legacy densities are present. Every failure mode here is
silent: nothing crashes, nothing fails to compile, the icon is just wrong on a
device. That is exactly what a contract test is for.

### Verify by hand

1. Long-press the app icon → **Themed icons** on Android 13+: the "a" silhouette
   appears as a cut-out, not a filled blob.
2. Check the launcher on a circle-mask launcher (Pixel): no corner gaps.
3. `adb shell cmd notification post -S bigtext tag "hi"` while the app is
   **backgrounded**, triggered from a real push: the status bar shows the "a",
   not a white square.
4. API 25 emulator or lower: the rounded raster icon is used.

---

## 16. Task 12 — the split identity, and the reset flow that was half-missing

### The symptom

Two histories had been merged: `master` (`003eba8`) still said
`com.example.agora` everywhere, while the current line (`net.agora.app`) had
been repackaged and had since diverged feature-by-feature. A merge that only
looks at *files* reports a conflict; a merge that looks at *behaviour* reports
something worse — **both sides had lost something the other still had.**

The audit (§16.1) found three separate defects, only one of which looked like a
"merge conflict":

1. The Kotlin tree itself was already unified — there is **no**
   `com/example/agora` directory to move. Nothing to relocate; plenty to prove.
2. The password-reset state machine existed in `master` and **not** on the
   current line: no mode, no routing guards, no resend cooldown, no busy flag,
   no `signOut()` after a recovery update. The current line had a *flattened*
   flow that still compiled — which is why no parser, linter or build ever
   flagged it.
3. `LoginScreen` had lost the **"Forgot password?" entry point** entirely. The
   whole API was present and unreachable.

Meanwhile `master` was *behind* on the UI: its extracted `PasswordReset*Step`
composables predate the light-mode contrast work, so copying them verbatim
would have reverted a fix from the current line.

### 16.1 Evidence, not assumption

`git log --all --name-only` showed the six reset files exist **only** in
`003eba8`, which `git merge-base --is-ancestor` proved is **not** an ancestor of
`HEAD`. `master` is a parentless shallow root — a *sibling* history, not an
earlier state of this one. So:

| Fact | Value |
|---|---|
| commits reachable from HEAD | 24 |
| `003eba8` ancestors of HEAD | none |
| files under `com/example/agora` at HEAD | 0 |
| files under `net/agora/app` at HEAD | 99 |
| `master` tree | 92 files, 100 % `com.example.agora` |
| `PasswordResetScreen.kt` vs `master` | identical modulo the `package` line |

A grep for the nine `master`-only `AuthViewModel` members at HEAD returned
**0 hits** — including `startPasswordReset`, which `AccountDetailsScreen.kt:858`
was calling. The flow *looked* present because `PasswordResetStep` and
`PasswordResetScreen` existed; the *policy* around them did not.

### 16.2 Decision: split the identity, do not unify it

`namespace` and `applicationId` are deliberately **different strings**:

```
namespace     = "net.agora.app"   // Kotlin package, R, BuildConfig, manifest class base
applicationId = "net.in.agora"    // device identity: Play, Firebase, OAuth, App Links
```

Both directions are forced, and both have already broken this repository once:

* **`applicationId` cannot be `net.agora.app`.** `app/google-services.json`
  declares exactly two clients (`com.example.agora`, `net.in.agora`); the
  google-services plugin aborts the build — *"No matching client found for
  package name"* — when `applicationId` matches neither. The Google OAuth
  Android client is registered for `net.in.agora` + the release SHA-1, so
  sign-in would return `[28444]` / status `16` / `DEVELOPER_ERROR`. And
  `https://agora.in.net/.well-known/assetlinks.json` names `net.in.agora`, so
  App Links would stop verifying.
* **`namespace` cannot be `net.in.agora`.** `in` is a Kotlin *hard keyword*:
  `package net.in.agora` is a syntax error (`Package name must be a
  '.'-separated identifier list`), as is every `import net.in.agora.…`. Java
  accepts it; Kotlin does not.

AGP supports this natively — `namespace` only feeds `R`/`BuildConfig` and the
manifest's relative class names, while `applicationId` is what lands in the APK
manifest, on the device and in Play. The previous contract test asserted the two
were *equal*; that test is now
`namespaceIsTheLegalPackageAndApplicationIdIsTheShippedIdentity` and pins each
value separately, plus a new
`googleServicesJsonDeclaresTheApplicationId` that fails with the real cause
instead of letting the Gradle plugin fail with a cryptic one.

`docs/app-links/assetlinks.json.template` still listed `net.agora.app` — a file
that would have verified **nothing**, because Android compares `package_name`
against the installed package id. Fixed to `net.in.agora`, with a section in
`docs/app-links/README.md` explaining why the `adb` commands use the
applicationId and never the namespace.

### 16.3 Deep links: three custom schemes, one host

`DeepLinkRouter.CUSTOM_SCHEMES` and the manifest now agree on:

| Scheme | Why it exists |
|---|---|
| `agora` | what the app itself emits (FCM, notifications, NavHost, every link sent) |
| `net.in.agora` | the shipped applicationId — `intent://` URIs and package-derived links |
| `net.agora.app` | the Kotlin namespace — links minted while the two identities were unified |

`https` + `agora.in.net` remains the only web origin, and the `autoVerify`
filter still declares only the canonical host: one unverifiable host downgrades
verification for *all* of them.

`net.in.agora://post/{id}` and its empty-authority form
`net.in.agora:///post/{id}` are now covered by tests in both
`DeepLinkRouterTest` (JVM) and `DeepLinkIntentTest` (instrumentation), and
`backend/setup_agora_config.ps1` adds `net.in.agora://**` to the Supabase
redirect allow-list — a scheme the manifest accepts but Supabase never
redirects to is only half-wired.

### 16.4 Reconciliation policy for the reset flow

Ported **from `master`, selectively**:

* `PasswordResetRouting` + its 6 unit tests (free of Android types).
* `rememberOtpResendSeconds` — shared by the registration OTP card and the
  reset OTP step, counting down to an *absolute monotonic deadline*, so
  rotation and backgrounding cannot restart the cooldown.
* The guarded state machine in `AuthViewModel`: `PasswordResetMode`
  (`CHANGE`/`RECOVERY`), `startChangePassword` / `startPasswordRecovery` /
  `editRecoveryEmail`, `PasswordResetRouting` guards on all three mutators,
  `PasswordPolicy.isValid` (replacing a bare `length < 6`), the resend
  deadline, the busy flag, and the `signOut()` that ends a *recovery* session
  after the password changes.

Kept **from the current line**:

* Every Google Sign-In diagnostic (`logGoogleSignInDiagnostics`,
  `BuildConfig.GOOGLE_WEB_CLIENT_ID`, the `--rim-rgb` / error classification) —
  untouched.
* The light-mode field colours (`agoraAuthFieldColors`, `authSubtitleColor`).
* The shared components, media pipeline, Edge Function deletion and icons.

The three reset steps were extracted into `PasswordResetEmailStep.kt`,
`PasswordResetOtpStep.kt` and `PasswordResetNewPasswordStep.kt` **from the
current line's inline bodies**, not from `master`'s stale composables — the
structure of `master` with the styling of `HEAD`. The screen keeps the
`AnimatedContent` `when` and the snackbar rules; the steps are presentational
and take callbacks.

Safety check on the restored `signOut()`: the abandoned-account wipe in this
ViewModel is gated on `sessionCreatedInThisRun`, which every recovery path sets
before minting a session — so a post-change `signOut()` can never trigger the
destructive branch on a real account.

### 16.5 What still cannot be proven here

Tree-sitter parses all 103 Kotlin files with zero `ERROR`/`missing` nodes; all
244 intra-`net.agora.app` imports resolve to a real declaration; the only
duplicate top-level names are same-file overloads (`AgoraPrimaryButton` ×2,
`AgoraTheme` ×2). The seven package-contract assertions were re-implemented and
run outside Gradle, all green.

None of that is a build. `./gradlew :app:assembleDebug` is the only proof that
matters, and this environment has no JDK, no Android SDK and no network access
to Google's Maven. Treat the static evidence as *necessary*, never *sufficient*.

### 16.6 First compiler contact: `cache.keys()` was never a function call

The first real `javac`/`kotlinc` output on this repository was one line:

```
e: MediaCacheCleaner.kt:77:34 Cannot infer type for type parameter 'T'. Specify it explicitly.
```

Column 34 is the `keys` of `cache.keys()`. `androidx.media3.datasource.cache.Cache`
is a **Java** interface, and its method is

```java
Set<String> getKeys();
```

Kotlin exposes any `getX()` method as a synthetic property, so the value is
`cache.keys` — a property, not a function. `cache.keys()` therefore had no
callable to bind and no type argument to infer. Nothing about this is caught by
a grammar parser: tree-sitter sees a perfectly shaped call expression, which is
why the file passed 103-file parsing, import resolution and duplicate-symbol
checks and still failed the only test that matters.

The fix calls the method by the name that actually exists and pins the platform
type on the way in:

```kotlin
val cachedKeys: Set<String> = cache.getKeys()
val toRemove: List<String> = cachedKeys.filter { cachedKey ->
    urls.any { url -> cachedKey == url || cachedKey.startsWith(url) }
}
```

Two lessons worth keeping:

* A Java `Set<String>` arrives as the platform type `(Mutable)Set<String!>!`.
  Binding it to an explicit `Set<String>` removes any inference from the
  expression, so a future API change fails at the assignment instead of inside a
  lambda.
* `it` shadowing (an `it` inside a nested `any` inside a `filter`) makes these
  errors read badly and can hide which receiver failed. Naming every lambda
  parameter is not style here; it is diagnostics.

Gradle has now run somewhere — the user's machine — and this is the only
diagnostic it produced for this file. The rest of the static evidence still
stands, but the compiler remains the only judge.

### 16.7 Second compiler contact: the Supabase *JS* spelling, and a misplaced opt-in

```
e: AccountDetailsScreen.kt:127:52 Unresolved reference 'remove'.
```

Column 52 is `remove` in `supabaseClient.storage.from("avatars").remove(path)`.
That is the **Supabase JavaScript** SDK's method name
(`storage.from(bucket).remove([...])`). supabase-kt's `BucketApi` (3.7.0) has no
`remove` at all — it exposes

```kotlin
suspend fun delete(paths: Collection<String>)
suspend fun delete(vararg paths: String)
```

so the correct call is `.delete(path)`. This file is the only storage *deletion*
in the app, which is why it is the only place the mistake appears.

The class of defect matters more than the instance: this repository contains
code translated from Supabase JS snippets, and JS→Kotlin renames (`remove` →
`delete`, `update` semantics, snake_case payload keys) produce errors that no
grammar parser and no "does the import resolve" check can see. Every
supabase-kt call in the tree was therefore checked against the 3.7.0 sources:

| Plugin | Calls used | Verified against |
|---|---|---|
| `auth` | `currentUserOrNull`, `updateUser`, `signInWith`, `verifyEmailOtp`, `signOut`, `sessionStatus`, `reauthenticate`, `resetPasswordForEmail` | `Auth.kt` @ 3.7.0 |
| `storage` | `from`, `upload`, `publicUrl`, **`delete`** | `Storage.kt`, `BucketApi.kt` @ 3.7.0 |
| `postgrest` | `rpc`, `from(...).select/update/delete/insert` | `Postgrest.kt` @ 3.7.0 |
| `realtime` | `connect`, `channel`, `removeChannel` | `Realtime.kt` @ 3.7.0 |
| `functions` | `invoke` (an `operator fun`) | `Functions.kt` @ 3.7.0 |

The same treatment was applied to the two media caches, since they are the other
place where a wrong name hides behind a plausible-looking call:
`coil.imageLoader` (`inline val Context.imageLoader` in coil-singleton),
`DiskCache.remove(String)`, `MemoryCache.remove(MemoryCache.Key)`,
`SimpleCache.removeResource(String)`, `Cache.getKeys()`.

#### The latent error in the same file

Line 110 carried `@OptIn(ExperimentalMaterial3Api::class)` immediately above the
KDoc of the private helper `deleteReplacedAvatar` — an annotation stranded one
declaration early. Kotlin attaches annotations to whatever declaration follows,
so it compiled, but it was spent on a suspend helper that uses no Compose API,
while `AccountDetailsScreen` — which calls `rememberDatePickerState`, `DatePicker`,
`ExposedDropdownMenuBox` and `menuAnchor(ExposedDropdownMenuAnchorType…)` —
had **no** opt-in at all.

That is invisible to a grep for the annotation (the file contained exactly one,
as it should) and invisible to a parser (the file is well-formed). It shows up
only when the compiler reaches the experimental call sites. Every other file
using those APIs (`FeedScreen`, `SinglePostScreen`, `ReactorsBottomSheet`,
`ProfileSetupScreen`, `OnboardingFlowScreen`, `CommentsBottomSheet`) already had
the annotation on the composable, which is what confirmed the intended
convention. The annotation now sits on `AccountDetailsScreen`, and a tree-wide
scan for *any* annotation separated from its declaration by a KDoc block returns
zero hits.

Two rules from this:

* Never leave an annotation adjacent to a doc comment — it looks attached to the
  documented declaration but binds to the next one.
* A compile error is a better oracle than any static gate built here. Both of
  these defects passed 105-file parsing, 244-import resolution, duplicate-symbol
  detection and every contract test.

### 16.8 Third compiler contact: a non-exhaustive `when` I introduced myself

```
e: PasswordResetScreen.kt:225:33 'when' expression must be exhaustive.
   Add the 'null' branch or an 'else' branch.
```

This one is a **regression from the §16.4 extraction**, not an inherited defect,
and it is worth writing down precisely because of that.

`passwordResetStep` is a `StateFlow<PasswordResetStep?>`, so
`AnimatedContent(targetState = step)` hands the content lambda a
`PasswordResetStep?`. The pre-refactor file ended its `when` with

```kotlin
PasswordResetStep.NEW_PASSWORD -> { … }

else -> {}
```

The extraction script sliced the `when` body between the `{` after
`when (currentStep)` and its matching `}`, then rebuilt the block from the three
enum branches. The `else -> {}` was not one of the three branches, so it was
dropped — leaving a `when` that handled EMAIL, OTP and NEW_PASSWORD and nothing
else. Kotlin 2 requires `when` statements over enum, sealed and **nullable**
subjects to be exhaustive, so the missing `null` became an error.

Restored, but as a precise arm rather than the original blanket `else`:

```kotlin
null -> Unit   // AnimatedContent still renders the outgoing frame after
               // cancelPasswordReset()/submitNewPassword() set the step to null
```

`else ->` would also compile, and that is exactly the problem: if a fourth step
is ever added to `PasswordResetStep`, `else` silently renders an empty card,
while `null ->` makes the compiler stop the build. After a day of finding
silently-wrong `when` usage, the variant that fails loudly wins.

**The generalisable lesson:** a mechanical body extraction is only as safe as the
accounting for everything between the anchors. The three enum branches were
counted; the fallback arm was not part of the count. Any future extraction of a
`when` must assert that the number of `when_entry` nodes before and after matches.

#### A checker for this class, and the two bugs that nearly made it useless

`when` exhaustiveness is statically checkable without a compiler, so a scanner
was written: collect enum/sealed types, resolve each `when` subject to a type
(including `AnimatedContent(targetState = …) { param -> … }` lambda parameters
and `by …collectAsState()` delegates), then flag subjects that are nullable or
enum-typed without full coverage.

It reported **0 findings on the current tree — and 0 on the deliberately broken
file.** Two bugs, both worth remembering:

1. **The subject could not be read.** `AnimatedContent`'s node `start_byte` does
   not begin at the `when` keyword, so an anchored `re.match` never matched. The
   fix is to read the grammar's `when_subject` child instead of slicing the text.
2. **Byte offsets vs `str` slicing.** tree-sitter reports **byte** offsets. The
   script decoded the file to `str` and then sliced with those offsets, which
   after the first emoji or em-dash shifts every subsequent slice by one
   character per multi-byte code point. `PasswordResetStep.EMAIL` was being read
   as `swordResetStep.EMAIL`. Every `txt(node, src)` in these scripts must take
   `bytes`. This repository is full of `✦`, `—` and `…`, so the drift starts
   almost immediately.

Only after both fixes did the checker report exactly one finding — line 225 of
the pre-fix file, the compiler's own line — and zero on the fixed tree. A
detector that has never been shown to fire on a known-bad input is not evidence.

Both false positives it produced before the type-aware rewrite are instructive
too: `when (handle)` in `AgoraImageCropper` (subject is `Int`) and
`when (step)` in `OnboardingFlowScreen` (`mutableIntStateOf`, also `Int`).
Kotlin only demands exhaustiveness for enum, sealed, `Boolean` and nullable
subjects — **not** for `Int` — so name-based matching alone would have produced
noise forever.

---

## 17. Identity unification: `net.agora.app` for namespace **and** applicationId

### What changed and why

`applicationId` is `net.agora.app` again. `namespace` was already
`net.agora.app`. One identity, everywhere.

This **restores the decision recorded in §9**, which had been documented as
"one identity, and it has to be a legal Kotlin package". During the §16
reconciliation the two were split — `namespace = net.agora.app`,
`applicationId = net.in.agora` — to match the older registration that
`google-services.json` and the OAuth client already carried. That split was
internally consistent but expensive: every identity was a second value to keep
in sync across Gradle, Firebase, Google Cloud OAuth, Digital Asset Links, the
Supabase redirect allow-list, ad/QR links minted from the package name, and the
contract tests. It bought compatibility with one external registration and
charged for it in drift surface.

The constraint that decides the spelling has not changed and cannot be changed:

> `net.in.agora` can **never** be the namespace. `in` is a hard keyword in
> Kotlin, so `package net.in.agora` is a syntax error, as is every
> `import net.in.agora.…`. Java accepts it; Kotlin does not.

Since the namespace is immovable at `net.agora.app`, unifying the applicationId
onto the same string is the only self-consistent option.

### What was verified rather than changed

The source tree was **already** 100 % `net/agora/app`

| Path | Files |
|---|---|
| `app/src/main/java/net/agora/app/` | present, all sources |
| `app/src/test/java/net/agora/app/` | present, all tests |
| `app/src/androidTest/java/net/agora/app/` | present |
| any `net/in/agora/` directory | **0** |

So step 2 of the migration was an audit, not a move: there were no package
headers, imports, or paths to relocate. Likewise every `Context`/authority
reference already derives from `BuildConfig`/`applicationId` or the namespace;
no hardcoded package string needed rewriting outside the files listed below.

### The one deviation, stated explicitly

`net.in.agora` survives as an **accepted inbound deep-link scheme**, and only
that.

Every build up to this unification shipped with `applicationId = net.in.agora`,
so notifications and share links those builds already delivered carry
`net.in.agora://…`. Dropping the scheme would turn every one of those taps into
a dead end on the feed with no error. The app therefore:

* **accepts** `net.in.agora://` (manifest `<data>` + `DeepLinkRouter.CUSTOM_SCHEMES`),
* **never emits** it — every generated link uses `agora://` or
  `https://agora.in.net/…`,
* declares it in the Supabase redirect allow-list so already-sent emails still
  resolve.

It is not an identity, it does not appear in Gradle, Firebase, OAuth or
`assetlinks.json`, and removing it is a two-line change (the manifest `<data>`
and the `CUSTOM_SCHEMES` entry) once no such link can reach a user. Tests pin
both directions: `applicationIdSchemeResolvesThePostId` for the canonical form
and `legacyApplicationIdSchemeStillResolvesThePostId` for compatibility.

### Three external preconditions this repository cannot satisfy

The code is consistent; the **outside world** has to follow. Until it does, the
build and two runtime features fail — loudly, which is the point:

| # | Precondition | Symptom if unmet |
|---|---|---|
| 1 | `app/google-services.json` needs a client for `net.agora.app` | `processDebugGoogleServices` aborts: *"No matching client found for package name 'net.agora.app'"*. The file currently declares only `com.example.agora` and `net.in.agora`. |
| 2 | Google Cloud OAuth **Android** client for `net.agora.app` + signing SHA-1 | Credential Manager returns `[28444]` / status `16` / `DEVELOPER_ERROR` after the account picker. |
| 3 | `https://agora.in.net/.well-known/assetlinks.json` republished with `package_name: net.agora.app` | Every `https://agora.in.net/post/…` App Link silently opens the browser instead of the app. |

`google-services.json` must be **downloaded from the Firebase console**, never
hand-edited: the plugin verifies the file and the console is the source of truth
for the API keys. `PackageDeclarationContractTest.googleServicesJsonDeclaresTheApplicationId`
therefore **fails until step 1 is done**. That failure is deliberate — it is the
accurate state of the repository, and it names the exact fix. Deleting or
skipping that test would hide a precondition that otherwise only surfaces as a
cryptic Gradle error.

### Consequence that is not a bug

Changing `applicationId` makes this a **different app** to Play and to Android.
A build already installed as `net.in.agora` cannot be upgraded in place: it
installs side by side, keeps its own data, and a store listing under the old id
does not transfer. If the intent is to migrate an existing install base rather
than publish a new listing, the applicationId must keep matching the store
record — which is exactly what the previous split was buying.

---

## 18. Six regressions, one pass

Six unrelated-looking reports. Only one of them was a missing feature; the rest
were places where a decision had been made twice — in the policy *and* in the
screen, in the shell *and* in the ViewModel — and the two copies had drifted.
Each section below names the root cause rather than the diff.

### 18.1 Change Password was not the recovery flow

**Reported:** *Change Password jumps straight to the new-password step.*

**Root cause:** `PasswordResetRouting.startStep(CHANGE)` returned
`NEW_PASSWORD`, and `canOpenNewPasswordStep` carried a `mode == CHANGE` escape
hatch. The reasoning was "the user is already signed in, so they are
authenticated" — which conflates *an account is open on this device* with *the
person holding it is the owner*. A signed-in session is exactly what an
unattended phone has.

**Fix:** the graph has no such edge any more. Both modes run
`EMAIL -> OTP -> NEW_PASSWORD`:

| Guard | Rule |
|---|---|
| `startStep(mode)` | `EMAIL`, for both modes, as an exhaustive `when` with no default |
| `canOpenOtpStep(mode, codeRequested)` | `codeRequested` |
| `canOpenNewPasswordStep(mode, verified)` | `verified` — the `mode` parameter is kept only to keep call sites symmetric |

`requestPasswordResetOtp` additionally enforces step 1 for **both** modes: the
typed address must equal the signed-in account (case-insensitively) *before any
mail is sent*. That check used to live inside the coroutine, after the busy flag
was set and after the `profiles` lookup — a mismatch sent no mail, but it also
meant the only thing standing between a stranger and a reset mail for a
**different** mailbox was a later branch. It now returns before the coroutine,
so there is no path on which a code is issued for an address the device does not
own.

Two smaller corrections fell out of this:

* the OTP is requested and verified against the **canonical** session address
  (`activeEmail ?: cleanEmail`) — Supabase mints the code against the account's
  own email, and mail domains do not treat `Jay@x.com` and `jay@x.com` as two
  mailboxes;
* `editRecoveryEmail()` no longer refuses outside RECOVERY, and it routes back
  through `startStep(mode)` rather than naming `EMAIL` itself.

`PasswordResetRoutingTest` is the executable form of the invariant: seven tests,
all iterating `PasswordResetMode.entries`, so a third mode has to state its rule
before it can compile past the exhaustive `when`.

### 18.2 The wordmark was declared in three places

**Reported:** *the reset screens show an outdated plain purple "AGORA".*

**Root cause:** `PasswordResetScreen` drew its own header — `Text("Agora", 36.sp,
FontWeight.Black)` in `primary`, or hard-coded `#FF4338CA` in light mode. Login
and Register had moved to `rememberAgoraWordmark()` + `AuthWordmarkSizes`; the
reset screen had not, because it never shared that code.

**Fix:** it now renders `rememberAgoraWordmark("agora")` at
`AuthWordmarkSizes.Login` with `letterSpacing = (-1.2).sp` — the same three
expressions as `LoginScreen`, not a lookalike. `AuthWordmarkSizes` is the only
place brand typography is declared.

The three step composables (`PasswordResetEmailStep`, `…OtpStep`,
`…NewPasswordStep`) never had headers of their own; they render inside the host
card. There was one rogue header, not four.

### 18.3 Back from Change Password landed on the Feed

**Reported:** *closing the flow returns to Home instead of Account Details.*

**Root cause:** `MainActivity` composed the flow as a **replacement**:

```kotlin
if (passwordResetStep != null) PasswordResetScreen(...) else when (sessionStatus) { ... }
```

`MainScreen` therefore left the composition, and `rememberNavController()`
inside it was destroyed with it. Closing the flow rebuilt the NavHost at
`startDestination = "feed"`. The `account_details` back stack was never
preserved — it was *deleted*, so no amount of "returning" could find it.

**Fix:** the flow is a **modal on top**, not a replacement. The `when` stays
composed inside a `Box` and the overlay is the last child:

```kotlin
Box(Modifier.fillMaxSize()) {
    when (sessionStatus) { ... }            // MainScreen + NavHost, never torn down
    if (passwordResetStep != null) PasswordResetScreen(...)
}
```

`PasswordResetScreen` also gains a `BackHandler`, because a modal that leaves
Back to the tree underneath lets the *composed* NavHost pop Account Details →
Feed behind the overlay. Back is deliberately a **cancel**, not a step-back: the
OTP step has already emailed a code, and re-entering step 1 with a live code is
an ambiguous state. The arrow and the system gesture now share one `cancelFlow`
lambda so they cannot diverge.

Completion is unaffected: `submitNewPassword` signs out only for RECOVERY (a
recovery session is temporary). A CHANGE keeps the session and simply closes the
overlay, leaving the user on the Account Details screen they started from.

### 18.4 A two-state switch cannot express three states

**Reported:** *delete "Manual override"; give me System / Light / Dark.*

**Root cause:** the control was a `Switch` bound to `themePreference != SYSTEM`,
with a supporting line that read `"Manual override"` whenever the preference was
anything else. "Follow the device" was not an option — it was the *absence* of a
choice, described in implementation vocabulary: the user read the source code's
word for "you have not picked one yet".

**Fix:** `ui/components/ThemeModeSelector.kt`, three pills (`selectable`, so
TalkBack announces the selection and the group behaves as radios), backed by the
existing `ThemePreferenceStore` (`SYSTEM` / `LIGHT` / `DARK`) and the existing
`ThemeViewModel.setThemePreference`. Recomposition only — no activity restart,
which is also why the `SYSTEM` branch reads `isSystemInDarkTheme()` reactively.

The now-dead `ThemeViewModel.setDarkMode(Boolean)` is removed: a setter that can
only write LIGHT or DARK cannot express the third state, and keeping it invites
the same bug back. `"Manual override"` no longer appears anywhere in the
repository, comments included.

### 18.5 The splash: the app icon, turning exactly once

**Reported:** *restore the logo + gold circle animating in and rotating once.*

**What the history actually contains:** every revision of
`res/drawable/agora_splash.xml` is **static**. `git show a8d6228` (the only
revision with any `<target>` at all) animates a `scaleX` on the tile — no
rotation, and no gold: `grep -riE "gold|#ffd700|#d4af37|#f5c542|#e8b923"` over
`res/` returns nothing in any revision. There was no animation to bring back, so
this is new work, and saying "restored" would have been false.

**What was authored, and then corrected.** The first attempt added a 280° gold
arc around the badge and rotated *that*, leaving the logo static. That was a
misreading of the request and is now removed — a ring spinning beside a still
logo reads as a progress spinner attached to the icon, which is the opposite of
what a splash entrance should say. The palette is pinned by a test so no
decoration can come back quietly.

The splash is now the app icon and nothing else:

* the badge and the mark are the launcher's own `pathData`, carried over
  byte-for-byte (the mark's two shapes are the launcher's white shapes with the
  launcher's accent subpaths appended, exactly as `ic_launcher_monochrome.xml`
  does it, so the counters are holes rather than painted accents);
* both are inside **one** `logo` group pivoted at `(144, 144)` — the exact centre
  of the 288×288 viewport — so the entire icon turns 0 → 360 exactly once while
  it grows 0.3 → 1.0; rotation and scale share one `<set>` and one duration, so
  the entrance reads as a single motion rather than a spin plus a pop;
* the mark is inset to **69%** of the badge, leaving ~15% clear padding on all
  four sides — large enough that the badge does not look empty, small enough
  that no stroke approaches the rim.

The inset is measured, not eyeballed. The mark's own coordinate space runs out
to ~392 units, and at the previous scale it covered **84%** of the badge: its
lower-right arc strokes sat ~8% from the edge. The numbers that fix it:

| Quantity | Value |
|---|---|
| mark bounding box (own units) | `(107.15, 104.70) – (392.59, 391.07)` |
| mark centre → `pivotX` / `pivotY` | `(249.87, 247.885)` |
| badge centre | `(143.445, 144.011)` |
| centre offset → `translateX` / `translateY` | `(-106.425, -103.874)` |
| `scaleX` / `scaleY` | `0.355` → mark is 69.0% wide, 67.8% tall |
| resulting padding | 15.5% L/R, 16.1% T/B |

Putting the pivot on the mark's own centre is what makes `translate` the only
positioning term: changing `scale` re-centres the mark about that point instead
of shoving it sideways, so the two constants stayed correct when the mark was
enlarged from 62% to 69% — only the one number changed.

Four details that are easy to get wrong, and are therefore written down:

1. `VectorDrawable` applies a group transform as
   `translate(pivot) → scale → rotate → translate(-pivot) + translateXY`, so
   scale and translate alone place a group; no matrix is needed or used.
2. `VectorDrawable` has **no group `alpha`**, so the entrance is a scale — an
   alpha animator on a `<group>` fails silently. The platform's own exit
   transition supplies the fade.
3. the animator must **not** set `android:repeatCount`; omitting it means
   play-once. `valueType="floatType"` is required or the platform can treat
   0 → 360 as an integer step and drop the intermediate frames.
4. a small `valueFrom` is safe on old devices **because the compat path never
   animates**. `androidx.core:core-splashscreen`'s pre-Android-12 implementation
   contains no `startAnimation` / `Animatable` / `AnimatedVector` handling at
   all (checked in the source, not assumed), so those devices draw the
   drawable's *declared* attributes: full size, unrotated. The 0.3 start exists
   only on API 31+, which is exactly where the platform plays it.

`windowSplashScreenAnimationDuration` (`800`) matches the animator's duration.
One consequence worth stating plainly: the AVD starts when the splash appears
and the platform removes the view when `setKeepOnScreenCondition` releases it,
so on a very fast session resolve the turn is cut short. Making the full turn
guaranteed would take an `setOnExitAnimationListener` in `MainActivity`, which
is a deliberate non-goal of this change — the splash XML is self-contained.

### 18.6 A token refresh is not a sign-in

**Reported:** *minimising and reopening loses the inner screen.*

**Root cause:** the `sessionStatus` collector ran the cold-start profile check on
every `SessionStatus.Authenticated` emit. Supabase re-emits that on every token
renewal — returning from the launcher, unlocking the phone, an hour passing. The
check raises `isCheckingProfileCompleteness`, the shell renders a spinner
*instead of* `MainScreen`, and that unmounts the NavHost exactly as in §18.3.

The internal guard was not enough: the flag was set **inside** the launched
coroutine, so two emits arriving back-to-back could both pass the guard and check
twice — one of which wipes an account whose profile is a legitimate in-progress
placeholder.

**Fix:** `verifyProfileCompleteness` takes the claim **synchronously**, before
any suspension point, and only `signOut()` releases it. A refresh now reaches
the guard and returns. The distinction is named in `utils/SessionContinuity.kt`
so it can be pointed at (and tested) instead of living in prose:

| `wasAuthenticated` | `isAuthenticated` | Meaning | May reset |
|---|---|---|---|
| `true` | `true` | token refresh | **no** |
| `false` | `false` | still signed out | no |
| `false` | `true` | sign-in | yes |
| `true` | `false` | sign-out | yes |

The same rule now gates the logout theme reset in `MainActivity`, so a refresh
can no longer drop the user's LIGHT/DARK choice either.

`SessionContinuityTest` covers all four rows. The shell's existing guards are
kept and were re-verified rather than rewritten: `launchMode="singleTop"`,
the `isTaskRoot` ghost-instance guard in `onCreate`, the `savedInstanceState ==
null` gate on deep-link injection, and `handleIntent` returning early for an
`ACTION_MAIN` intent (nothing to parse, nothing submitted). `onNewIntent` reuses
the same Activity and therefore the same `AuthViewModel`.

### 18.7 What was verified, and what could not be

Static gates only — this environment has no JDK, no Android SDK, and no device,
so nothing here is a substitute for `assembleDebug`:

| Gate | Result |
|---|---|
| tree-sitter Kotlin parse, `app/src/**/*.kt` | 108 files, 0 `ERROR`/missing nodes |
| import resolution | 2098 imports, 0 unresolved |
| duplicate top-level declaration per package | 0 |
| `when` exhaustiveness (enum + nullable subject) | 0 violations |
| stranded-annotation scan | 0 |
| XML well-formedness, `res/**/*.xml` + manifest | 17 files, 0 failures |
| `<target>` names / animator references in the splash | resolve |
| raw `.svg` under `res/` | none |

The `when` checker was itself validated against a deliberately broken fixture
(missing enum arm; nullable subject with no `null ->` and no `else`) before its
"0" was believed.

Still unproven here, and the honest list: that the project **compiles** (unused
imports, getter-vs-property, `@Composable` context restrictions and the
`material-icons-extended` names `DarkMode` / `LightMode` / `SettingsBrightness`
are all compiler facts), that the splash actually looks right on a device, and
that the three external preconditions in §17 are met.

---

## 19. Back navigation tearing: two defaults nobody chose

**Reported:** *pressing Back from a sub-screen (Account Details) makes the page
tear apart, flash, or snap.*

The graph was fine. Every one of the seven `onBack` callbacks already called
`popBackStack()`, the tab switches already used the canonical
`popUpTo(start) { saveState = true } + launchSingleTop + restoreState` triple,
and nothing anywhere called `setGraph`. The defect was **the absence of a
decision**: the NavHost declared no transitions, so the library supplied its own.

### 19.1 What the default actually is

`NavHost`'s transition parameters default to `DefaultNavTransitions`, and reading
that source settles it — these are the shipped defaults, not "no animation":

| slot | default |
|---|---|
| `enterTransition` | `fadeIn(tween(700))` |
| `exitTransition` | `fadeOut(tween(700))` |
| `popEnterTransition` | **the same fade as `enter`** |
| `popExitTransition` | **the same fade as `exit`** |
| `sizeTransform` | `null` |

Two consequences, and together they are the whole bug:

1. **A cross-fade renders both screens at once.** For 700 ms the outgoing and
   incoming full-screen destinations are both on screen at partial alpha. On this
   app's screens — opaque gradient canvases stacked over one another — that is a
   literal double exposure. It does not read as a transition; it reads as the
   page coming apart.
2. **Back is not the reverse of forward.** `popEnter`/`popExit` default to the
   *forward* transitions, so popping had no direction at all. A Back press and a
   Forward press produced the identical animation.

That is exactly the "tears apart / flashes / snaps" report, and it needed no
broken code to happen — only a missing one.

### 19.2 The fix: direction, and no alpha on the slide

The policy lives in `navigation/NavMotion.kt` — pure Kotlin, no Compose, no
Android, unit tested — and `navigation/NavTransitions.kt` turns it into
`EnterTransition`/`ExitTransition` values. Two motions:

**Hierarchical** (push/pop between levels). The incoming screen slides a full
width; the outgoing one drifts a quarter width the other way and **does not
fade**. That second part is the actual fix: because both surfaces are opaque and
the slide keeps them adjacent, the viewport is fully covered at every frame, so
there is never a background flash and never a blend.

```
  push:   [ old ⇒ -25% ]  ←  [ new        ]
  pop:    [ old        ]  →  [ new -25% ⇒ ]
```

The coverage holds at every fraction `f`: when pushing, the incoming screen's
left edge is at `(1-f)·W` while the outgoing screen still reaches `W - f·W/4`, and
`W - f·W/4 ≥ (1-f)·W` for all `f ∈ [0,1]`. Popping is the mirror image. There is
no value of `f` at which a gap opens.

**Peer** (feed ↔ own profile, i.e. the two bottom-pill tabs). These are siblings,
so a horizontal slide would claim a hierarchy that does not exist. They use a
fade *through*: the outgoing tab is fully gone (90 ms) on exactly the frame the
incoming one starts to appear (+90 ms delay, 210 ms fade). The two are never both
readable. (At that single seam the Scaffold's `canvasTop` shows through, and
`ProfileScreen` has no background of its own — it already sits on `canvasTop` —
so the seam is invisible by construction.)

Classification keys off the destination **and** its arguments: the profile route
is declared `profile?userId={userId}`, so "my profile" (a tab) and "someone's
profile" (a pushed detail) share a route string and are distinguished only by
whether `userId` is present. Unknown routes fall back to the slide, because a
slide is always readable while a fade on a push silently loses depth.

### 19.3 The other things that moved on the wrong clock

Three smaller contributors, all the same class of problem — a second animation
running to a different schedule than the content:

* **The nav pill** animated on a ~500 ms spring while the content faded for
  700 ms, so the chrome arrived well after the screen settled. It now shares
  `NavMotion.DURATION_MS` and `FastOutSlowInEasing` with the content.
* **The post-detail frost** was `if (isPostDetailOpen) 16f else 0f` inside a
  `graphicsLayer` — dropping 16 dp → 0 in a single frame on dismiss, a visible
  snap on the way out of a post. That case is now eased. The **drag** path is
  untouched: `drawerFraction` is still read inside the draw lambda and combined
  with `maxOf`, so the drawer frost still tracks the finger frame-for-frame, and
  the animated value is read inside that lambda (draw phase) so it never enters
  recomposition.
* **Back with the drawer open** had no handler in this screen. A `BackHandler`
  now closes the sheet instead of popping the graph behind it.

### 19.4 What the audit found already correct (and left alone)

| Checked | Result |
|---|---|
| every `onBack` callback | 7/7 call `popBackStack()` |
| graph root replacement | no `setGraph()` anywhere in `app/src/main/java` |
| tab switches | both already `popUpTo(start){saveState} + launchSingleTop + restoreState` |
| feed scroll position | `rememberLazyListState()` — saveable, survives the pop |
| profile tab selection | `rememberSaveable` — survives the pop |
| `refreshProfile()` on route change | keeps the previous value on failure; never nulls, so no flash |

One gap **was** real: most pushes had no `launchSingleTop`, so a double-tap on
"Account Details" or on an avatar pushed two identical destinations — the first
Back then landed on a pixel-identical copy, which reads as "Back did nothing,
then jumped two screens". All 13 `navigate()` calls are now guarded.

### 19.5 What is deliberately unchanged

* **Predictive back.** `enableOnBackInvokedCallback="true"` is already set, and
  navigation-compose drives that gesture with its own `predictivePop*`
  transitions (spring fade-in / `scaleOut(0.7)`). Those parameters were left at
  their defaults: this change is about the button, arrow and 3-button paths, and
  passing a different signature into `NavHost` without a compiler here is a risk
  with no upside. Noted as a follow-up, not silently assumed.
* **Post detail is a `dialog()` destination**, not a NavHost composable, so it
  does not participate in these transitions at all — it is a full-screen dialog
  window (`usePlatformDefaultWidth = false`). Its back path is the dialog dismiss
  plus the now-eased frost behind it.

### 19.6 Verification

No JDK, SDK or device in this environment, so again: static gates only.

| Gate | Result |
|---|---|
| tree-sitter Kotlin parse, `app/src/**/*.kt` | 112 files, 0 `ERROR`/missing |
| import resolution | 2122 imports, 0 unresolved |
| duplicate top-level declarations per package | 0 |
| `when` exhaustiveness (enum + nullable) | 0 violations |
| unused imports in the touched files | 0 |
| `NavMotionTest` (policy) | 13 cases |
| `NavigationContractTest` (wiring) | 6 guards, simulated against the source |

`NavigationContractTest` earns its place: writing it caught two real problems
before it ever ran — two navigations in the deletion chain that genuinely lacked
`launchSingleTop`, and a regex of mine that stopped *at* `restoreState` and so
could never have asserted `restoreState = true`. Both are fixed.

Still unproven here, stated plainly: that it **compiles** (the `slideInHorizontally`
/ `fadeIn` overloads and `NavHost`'s four transition parameters are compiler
facts), and that the motion feels right on a device — 320 ms with
`FastOutSlowInEasing` is a considered starting point, not a measurement.

---

## 20. Back navigation tearing, part two: the two paths §19 did not cover

§19 fixed the paths it looked at. It did not look at all of them — and it said so,
listing predictive back and the Change Password flow as "deliberately unchanged"
rather than pretending they were fine. Both were the bug. This section closes them.

The lesson worth keeping: **"Back" is not one code path.** In this app it is three
— a committed pop through the `NavHost`, a *gesture* that previews that pop before
committing, and a modal that owns Back entirely. Fixing the first one fixes the
path the tests can reach and leaves the other two untouched, which is exactly how a
user can report "it still tears" after a change that genuinely did work.

### 20.1 Path two: swipe-back shrank the page

`AndroidManifest.xml` sets `enableOnBackInvokedCallback="true"` (§9), so on
Android 13+ a back *gesture* does not run `popEnterTransition` / `popExitTransition`.
Read from `NavHost.kt`, the transition spec branches before it consults them:

```kotlin
if (inPredictiveBack) {
    destination.createPredictivePopExitTransition(this, swipeEdge)
        ?: predictivePopExitTransition.invoke(this, swipeEdge)
} else if (composeNavigator.isPop.value) {
    ... popExitTransition ...
}
```

and the gesture drives it by *seeking*, not animating:

```kotlin
LaunchedEffect(progress) {
    transitionState.seekTo(progress, previousEntry)
}
```

Those two slots have their own defaults, and they are not the pop. From
`DefaultNavTransitions.android.kt`:

| slot | default |
|---|---|
| `predictivePopEnterTransition` | `fadeIn(spring(dampingRatio = 1f, stiffness = 1600f))` |
| `predictivePopExitTransition` | `scaleOut(targetScale = 0.7f)` |

So a swipe shrank the entire page to 70 % about its centre — window background
showing at all four edges — and on release **snapped it back to full size and slid
it away**. Two unrelated motions stitched at the commit frame. That is a better
description of "tearing apart" than anything the button path ever did, because a
scale-down physically detaches the page from the window's edges.

Both slots now delegate to the same functions as the pop:

```kotlin
predictivePopEnterTransition = { NavTransitions.enter(initialState, targetState, isPop = true) },
predictivePopExitTransition = { NavTransitions.exit(initialState, targetState, isPop = true) },
```

Now the preview *is* the pop: the finger reveals exactly what release finishes,
because both are the same function of the same pair. `kindOf` is symmetric (PEER
requires **both** ends to be tabs), so reversing the pair for a gesture yields the
same kind the push used.

`swipeEdge` is deliberately ignored. The app only ever pushes from the right, so a
popped screen always leaves to the right no matter which edge the gesture began on
— which is also what `popExitTransition` does, since it has no notion of edges.
Honouring `swipeEdge` here would make the preview disagree with the commit, which
is the defect, not the fix.

### 20.2 Path three: Change Password had no transition at all

`PasswordResetScreen` is not a `NavHost` destination, and cannot be: the same
screen serves "Change Password" from Account Details *and* "Reset Password" from
Login, and the second case runs when no nav graph exists. It is composed above
whichever tree is current, in `MainActivity`:

```kotlin
if (passwordResetStep != null) { PasswordResetScreen(...) }   // before
```

A bare conditional. `PasswordResetScreen`'s root is `VibrantGlassBackground`,
which is `fillMaxSize()` and opaque, so:
opening it mounted a full-screen surface between two frames with no motion, and
Back — via `BackHandler(enabled = true) { cancelFlow() }` — deleted that surface in
a single step, uncovering Account Details instantly. §18.3 deliberately made this a
modal rather than a tree replacement, which fixed the *back-stack* problem and left
the *motion* problem: a modal that appears and disappears between frames.

It is now wrapped in `AnimatedVisibility` with a dedicated pair:

```kotlin
AnimatedVisibility(
    visible = passwordResetStep != null,
    enter = NavTransitions.overlayEnter(),
    exit = NavTransitions.overlayExit()
) { PasswordResetScreen(...) }
```

`overlayEnter` / `overlayExit` are the same move as a hierarchical push and its
mirror, on the same `NavMotion.DURATION_MS` and `FastOutSlowInEasing`. One motion
vocabulary: leaving Change Password looks like leaving Account Details, because it
is the same animation. Deliberately **no fade** — for the same reason the graph
slides have none (§19.2): the surface is opaque, so fading it would alpha-blend it
against the screen underneath and reintroduce the double exposure this whole arc
exists to remove.

### 20.3 The trap: animating the exit would have emptied the card first

The naive version of 20.2 is worse than the bug. `cancelPasswordReset()` sets
`_passwordResetStep.value = null`, and the step card is an `AnimatedContent` whose
`when` has an explicit branch:

```kotlin
null -> Unit        // deliberate: nothing to draw
```

`AnimatedVisibility` keeps its content composed while it animates out — but that
content now recomposes with a **null** step and paints an empty glass panel. The
result would be: form vanishes on frame 1, empty panel slides away over the next
320 ms. A flash, in a change meant to remove one.

So the screen remembers the last step it actually showed, and the card reads that
rather than the live value:

```kotlin
var lastVisibleStep by remember { mutableStateOf<PasswordResetStep?>(null) }
LaunchedEffect(step) { if (step != null) lastVisibleStep = step }
val displayStep: PasswordResetStep? = step ?: lastVisibleStep
...
AnimatedContent(targetState = displayStep, ...)
```

The `null -> Unit` branch stays — `displayStep` is still nullable, and the branch
is what makes a fourth enum entry a compile error instead of a blank card. It is
now simply unreachable during an exit.

### 20.4 Re-audited, still correct, still untouched

Re-checked rather than assumed, and left alone:

- all **7** `onBack` callbacks call `popBackStack()`; none call `navigate(`;
- `setGraph(` appears nowhere in `app/src/main`;
- both bottom-pill switches (feed, profile) use `popUpTo(startDestination) { saveState = true }`
  + `launchSingleTop = true` + `restoreState = true` — §19.4 already verified the
  scroll/selection survive;
- all **13** `navController.navigate()` calls carry `launchSingleTop`.

Back while the exit animation runs is harmless by construction: the modal's
`BackHandler` is still registered for those 320 ms and `cancelPasswordReset()` is
idempotent, so a second press cannot reach the `NavHost` underneath and pop
Account Details behind a panel that is still visible.

### 20.5 Verification

| Gate | Result |
|---|---|
| tree-sitter Kotlin parse, `app/src/{main,test,androidTest}/**/*.kt` | 112 files, 0 `ERROR`/missing |
| import resolution | 2124 imports, 0 unresolved |
| duplicate top-level declarations per package | 0 |
| `when` exhaustiveness (enum + nullable) | 4 enums, 3 whens, 0 violations |
| unused imports in the 5 touched files | 0 |
| XML well-formedness | 17 files, 0 failures |
| `NavMotionTest` (policy) | 13 cases |
| `NavigationContractTest` (wiring) | 9 guards, all 30 simulated assertions pass |

The gate script itself was wrong twice before it was trusted, and both were caught
by disbelieving a red result rather than the code: a regex name-extractor read
`fun Modifier.glassmorphic` as a declaration of `Modifier` (so 22 real-looking
"unresolved imports"), and it treated Kotlin **overloads** in one file as duplicate
declarations. Rewritten against the AST (`property_declaration` carries its name on
a `variable_declaration` child, not a `name` field). A gate that lies in either
direction is worse than no gate.

Still unproven here, stated plainly: that this **compiles** — the `predictivePop*`
parameters take `AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> …`,
a signature read from `androidx-main` and *not* from the 2.10.0 tag, because the
GitHub API exposes no `androidx-navigation` tags (same caveat as §19.6) — and that
the motion feels right on a device. 320 ms `FastOutSlowInEasing` remains a
considered starting point, not a measurement.

---

## 21. Report Post: three copies, two of which would have been forgotten

The report flow existed as **three** hand-copied `AlertDialog`s — in `FeedScreen`,
`ProfileScreen` and `SinglePostScreen` — byte-identical except for how each
submitted (a `Toast`, a snackbar, and a delegated `onReportPost` callback). Adding
a conditional text field, a validation rule and a legal notice to that shape means
three places to get each of them right, and the copies had already drifted from
the spec in the one field the backend actually reads.

### 21.1 What was wrong before any new requirement

`"Hate Speech"` was the stored value in all three copies. The specified label is
`"Hate speech"`. That is not a cosmetic variant — `reason` is a free-text column
in `reports`, so `'Hate Speech'` and `'Hate speech'` are two different values and
any `WHERE reason = …` grouping silently splits. The correction is a data change,
so it is pinned by a test rather than left to review.

### 21.2 One dialog, one definition of the five reasons

`ui/components/ReportPostDialog.kt` now owns the UI; `report/ReportReasons.kt`
owns the policy. The three screens each dropped ~50 lines of duplicated dialog
and kept only their submit side effect:

```kotlin
postToReport?.let { targetReport ->
    ReportPostDialog(
        onDismiss = { postToReport = null },
        onSubmit = { reason -> viewModel.reportPost(targetReport.id, reason); … }
    )
}
```

The option rows iterate `ReportReasons.ALL`, so the displayed list and the
allow-list in `payloadFor` cannot disagree — there is exactly one definition of the
five reasons in the app, and `ReportFlowContractTest` fails if a screen re-types a
label.

State moved **into** the dialog. It was previously hoisted on the screen
(`var reportReason by remember { mutableStateOf("Spam") }`), which meant the
selection survived dismissal and the next open silently restored it — including,
after this change, "Other" with an empty field, i.e. a dialog that opens in an
unsubmittable state. Inside the composable, dismissal leaves composition and the
next open starts at "Spam" with an empty field, for free.

### 21.3 The wire format

`reports` is `(id, post_id, reporter_id, reason, created_at)`; `reason` is one
free-text column, so the choice and the explanation have to share it:

| selection | `reason` stored |
|---|---|
| Spam / Harassment / Hate speech / Misinformation | verbatim, e.g. `"Spam"` |
| Other + `"the caption is a scam"` | `"Other: the caption is a scam"` |
| Other + `"   "` | *nothing — not submittable* |

Verbatim storage keeps the four predefined reasons groupable; the `"Other: "`
prefix keeps *the fact that Other was chosen* queryable while preserving the
user's own words. Storing the bare explanation would lose the distinction;
storing only `"Other"` would throw the explanation away.

`payloadFor` returns `String?`, and the dialog binds **both** the Submit button's
`enabled` and the value it sends to that single call. "Cannot submit" and "has a
payload" are therefore one decision rather than two that can drift apart.

Only the edges are trimmed. `"  line one\nline two  "` becomes
`"Other: line one\nline two"` — interior whitespace is what the user wrote.
Whitespace-only input is rejected outright: `"Other:    "` would be a reason
nobody can read.

### 21.4 The conditional field, and the trap in "just hide it"

Selecting "Other" expands a multi-line `OutlinedTextField` directly below the
options (`AnimatedVisibility` + `expandVertically()`/`fadeIn()`, reversed on
exit) so the list never jumps under the finger between tap and release.

Switching back to a predefined reason **clears** the explanation as well as
hiding the field. Hiding alone would leave the text alive in state and invisible
on screen — a value the user believes they discarded, which reappears if they
select "Other" again, and which would be submitted if it were ever not
overwritten. Clearing on the way out keeps "what you see is what will be sent".
`payloadFor` also ignores the explanation for the four predefined reasons, so even
a leaked value cannot be appended to `"Spam"`.

The dialog body scrolls. Without it, revealing the field plus the notice on a
short device pushes the Submit button out of the dialog — at exactly the moment
the user needs it.

### 21.5 The notice

> Note: If you submit a report, our moderation team may contact you via the email
> address registered to your Agora account if additional details are needed.

It lives in `ReportReasons.CONTACT_DISCLAIMER` rather than inline in the
composable, because the wording is a requirement: a typo in a legal-adjacent
notice should fail a test, not ship. Rendered as a quiet inset panel (12 sp,
`textTertiary`, hairline border, 16 dp info glyph) inside the dialog's text slot
— which places it above the action buttons — and deliberately styled unlike the
option labels, so it does not read as a sixth choice.

### 21.6 What was deliberately not done

- **The three screens' submit side effects are untouched** — same `Toast`, same
  snackbar, same delegated callback. Only the dialog was replaced.
- **`FeedViewModel.reportPost` is unchanged.** The reason arrives already
  formatted; the ViewModel persists it verbatim. `ReportFlowContractTest` asserts
  the `post_id`/`reporter_id`/`reason` insert and the `reports` table, so a future
  "helpful" re-format there fails loudly.
- **Two pre-existing unused imports** (`Icons.Filled.Close` in `FeedScreen`,
  `Close`/`Warning` in `ProfileScreen`) were left alone as out of scope. The one
  my change actually orphaned (`AgoraPrimaryButton` in `ProfileScreen`) was
  removed.

### 21.7 Verification

| Gate | Result |
|---|---|
| tree-sitter Kotlin parse, `app/src/{main,test,androidTest}/**/*.kt` | 116 files, 0 `ERROR`/missing |
| import resolution | 2178 imports, 0 unresolved |
| duplicate top-level declarations per package | 0 |
| `when` exhaustiveness | 4 enums, 3 whens, 0 violations |
| unused imports, 7 touched files | 0 (excluding `by`-delegation `getValue`/`setValue`, which are operator imports) |
| `ReportReasonsTest` (policy) | 18 cases |
| `ReportFlowContractTest` (wiring) | 10 guards, 38 structural assertions simulated against the source |

One assertion had to be corrected during verification, and it is worth recording:
`ReportFlowContractTest` initially failed because `ReportReasons.kt` contains the
literal `"Hate Speech"` — inside its own KDoc, explaining the bug. The assertion
was wrong, not the code: it now scans with comments stripped (`codeOnly`), so
prose may name the mistake while the *value* cannot become it.

Still unproven here, stated plainly: that this **compiles** (`minLines`,
`OutlinedTextFieldDefaults.colors`, the `+` composition of enter transitions and
local functions capturing `by`-delegated state are compiler facts, and there is no
JDK or SDK in this environment), and how the expand animation feels on a device —
`expandVertically()` + `fadeIn()` on default specs is a considered starting point,
not a measurement.
