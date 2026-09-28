# Android local configuration

The Android client gets its Supabase endpoint and anon key from generated `BuildConfig` fields.
The source values are read from the root `local.properties`, which is intentionally ignored by
Git, or from CI Gradle properties with the same names.

1. Copy `local.properties.example` to `local.properties`.
2. Set `SUPABASE_URL` and `SUPABASE_ANON_KEY` for the intended Supabase environment.
3. Build normally with `./gradlew :app:assembleDebug`.

The Supabase anon key is a client identifier and will be present in the APK; it is not a
server secret. Protect data with RLS and never put a service-role key in the Android app.

For CI, supply `-PSUPABASE_URL=... -PSUPABASE_ANON_KEY=...` instead of creating
`local.properties`.
