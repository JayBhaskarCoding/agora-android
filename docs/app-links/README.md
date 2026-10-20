# App Links for `agora.in.net`

Android only opens the app for `https://agora.in.net/post/{postId}` when **all**
of the following are true. If any one is missing, Chrome keeps the URL and the
app never sees the intent — no crash, no logcat entry, nothing to debug.

| # | Requirement | Where |
|---|---|---|
| 1 | An exported activity declares `https` + `host="agora.in.net"` + `pathPrefix="/post/"` with `autoVerify="true"` | `app/src/main/AndroidManifest.xml` ✅ |
| 2 | `https://agora.in.net/.well-known/assetlinks.json` is served over HTTPS, `200`, `Content-Type: application/json`, **no redirects** | this folder — ⬜ not deployed yet |
| 3 | That file lists `net.in.agora` (both the applicationId and the Kotlin namespace since
the package migration — see engineering-notes §9) with the SHA-256 of the certificate that signed the **installed** APK | ⬜ |
| 4 | The user (or Play) has not disabled link handling for the app | Play Console / device settings |

## Deploy the file

Copy `assetlinks.json.template` to `assetlinks.json`, replace the three
placeholders, and serve it from the site's origin at the exact path
`/.well-known/assetlinks.json`.

Fingerprints to use — all three, so debug, internal-testing and Play builds work:

```bash
# 1. Play App Signing (the one store installs actually use)
#    Play Console → Release → Setup → App integrity → App signing key certificate
#    (this is NOT your upload key)

# 2. Upload key (AAB you upload) and/or locally signed APKs
keytool -list -v -keystore <your-upload-keystore.jks> -alias <alias> | grep SHA256

# 3. Debug keystore (local runs)
keytool -list -v -keystore ~/.android/debug.keystore \
        -alias androiddebugkey -storepass android | grep SHA256
```

Paste each as the `XX:XX:…` colon-separated form. Wrong fingerprint = silently
unverified; a missing Play App Signing fingerprint is the single most common
cause of "it works in debug but not from Play".

## Verify

```bash
# What Android thinks (also logged by the app in debug builds: adb logcat -s DeepLink)
curl -sI https://agora.in.net/.well-known/assetlinks.json
adb shell pm get-app-links net.in.agora     # expect "agora.in.net: verified"
adb shell pm set-app-links --package net.in.agora 2 agora.in.net   # force re-check
adb shell am start -a android.intent.action.VIEW -d "https://agora.in.net/post/0c298d8b-c2f0-4f38-8585-7199f41c7769"
```

## Do not add a second host casually

`www.agora.in.net` (or any other host) must **not** be added to the manifest
unless it serves its own `assetlinks.json` for the same package: Android
verification is all-or-nothing per app, so one unverifiable host downgrades
`agora.in.net` to unverified as well. The parser accepts `www.` defensively, but
the manifest deliberately declares only the canonical host.
