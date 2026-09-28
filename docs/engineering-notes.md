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
