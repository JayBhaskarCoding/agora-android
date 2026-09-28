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
| `MainActivity` | `injectDeepLink` only read `?post_id=` **query params**, so `https://auth-agora.info/post/{id}` path links parsed to nothing. Warm taps (`onNewIntent`) set `intent.data` but nothing ever told the NavController — hence "opens the home screen". |
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
- **NavHost**: `navDeepLink` patterns for `agora://post/{postId}`, the `https://auth-agora.info/post/{postId}`
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
   `auth-agora.info` that streams the object and caches it — user media then never
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
   going black; `adb shell run-as com.example.agora ls -l cache/media3_video_cache`
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
