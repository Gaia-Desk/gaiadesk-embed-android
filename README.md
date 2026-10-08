# GaiaDesk embed for Android

A **"Get help" button for your Android app**. Your customer taps it and agrees; **your app's
screen** is shared with **your** support team, who see it in the GaiaDesk console's Support page and,
if you allow it, guide it: tap, scroll and type **inside your app only**.

- An AAR, `net.gaiadesk:embed-android`: a Kotlin API (no dependencies) and the prebuilt
  `libgaiadesk_embed.so` for arm64-v8a, armeabi-v7a and x86_64. Android 8.0+ (API 26).
- **Consent first**: the library refuses to start until your user has agreed. Use
  `requestConsent` (an AlertDialog with the right wording) or your own.
- **Only your app** by default: your own window, copied with PixelCopy — no permission, no system
  dialog, nothing outside your app. The whole screen only if you opt into `Capture.SCREEN`
  (MediaProjection: Android asks the user too, and shows its own casting indication).
- A visible **"Sharing with Acme · Stop"** indicator, drawn in your activity (no overlay
  permission), following your customer from activity to activity. The library sends frames only
  while it reports itself on screen with an activity of yours in front.
- **Mask** card numbers, balances, anything: painted black by the library *before* a frame is
  encoded (H.264 by the device's MediaCodec). Masks fail closed.
- **View-only by default.** Guided mode (opt-in) dispatches touch, wheel and key events to your own
  window, inside your process: never through the system's input stream, no accessibility service.
- Nothing is written to disk.

## Install

### From Maven Central

```kotlin
// settings.gradle.kts: mavenCentral() in dependencyResolutionManagement.repositories (the default)
// app/build.gradle.kts
dependencies {
    implementation("net.gaiadesk:embed-android:0.1.0")
}
```

The AAR carries `libgaiadesk_embed.so` for arm64-v8a, armeabi-v7a and x86_64 and has no
dependencies besides the Android SDK. `minSdk` 26.

### Without a Maven repository (the AAR from the GitHub release)

Every [release](https://github.com/Gaia-Desk/gaiadesk-embed-android/releases) carries
`embed-android-<version>.aar` (and `SHA256SUMS`). Put it in your app module's `libs/` and:

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(files("libs/embed-android-0.1.0.aar"))
}
```

That is all: the AAR needs nothing else on the classpath. Its consumer ProGuard rules come with it.
(An app module can take a local `.aar` this way; an Android *library* module cannot re-publish one,
so a library of yours should take the Maven coordinate instead.)

### A local build

`scripts/fetch-binary.sh 0.1.0` puts a release's `.so` files in `embed/src/main/jniLibs` (or a
GaiaDesk build of the library puts its own there), then:

- `./gradlew :embed:assembleRelease` → `embed/build/outputs/aar/embed-release.aar`;
- `./gradlew :embed:publishToMavenLocal` → `~/.m2` as `net.gaiadesk:embed-android:<version>`
  (add `mavenLocal()` to your repositories). The React Native and Flutter wrappers can use either.

## Quick start

Your backend creates a support session with a secret API key (scope `support`) and hands the app
its `embed_token`:

```sh
curl -s -X POST https://api.gaiadesk.net/v1/support/sessions \
  -H "Authorization: Bearer $GAIADESK_API_KEY" -H "Content-Type: application/json" \
  -d '{"mode": "view", "customer": {"name": "Ada", "plan": "pro"}}'
# → {"id": "ss_…", "join_code": "123456789", "embed_token": "gdemb_…", …}
```

```kotlin
import net.gaiadesk.embed.GaiaDeskEmbed
import net.gaiadesk.embed.GaiaDeskEmbedException

fun getHelp(activity: Activity, token: String) {
    GaiaDeskEmbed.requestConsent(activity, "Acme") { consent ->
        if (consent == null) return@requestConsent       // the user said no
        try {
            help = GaiaDeskEmbed.start(
                activity,
                GaiaDeskEmbed.Configuration(token, "Acme", maskedViews = listOf(cardView)),
                consent,
            ) { e ->
                when (e) {
                    is GaiaDeskEmbed.Event.Code -> showCode(e.code)          // read it to the agent
                    is GaiaDeskEmbed.Event.Ended -> Log.i("help", "ended: ${e.reason}")
                    else -> {}
                }
            }
        } catch (e: GaiaDeskEmbedException) {
            Log.w("help", "could not start: ${e.code} ${e.message}")
        }
    }
}
```

### Configuration

| Field | |
|---|---|
| `embedToken` | `gdemb_…`, from your backend |
| `company` | shown on the consent dialog and the indicator |
| `server` | default `https://gaiadesk.net` |
| `guided` | `false` (default). `true` allows guided input in a `cobrowse` session |
| `fps` | 1–30 (default 15) |
| `maskedViews` | views painted out wherever they are, measured with every frame |
| `maskedRects` | regions painted out from the first frame: window pixels (`maskedRectOf(view)`) |
| `capture` | `Capture.APP` (default) or `Capture.SCREEN` (opt-in, see below) |

### Consent

`start` takes a `Consent`: your user's YES, bound to what they were asked — who sees, what is
shared, whether the agent may guide. `requestConsent` shows the dialog and gives you one (or null);
with your own dialog, call `recordConsent(company, guided, capture)` when the user agrees, saying
exactly that in it. The library refuses a session that differs from the consent (another company,
another capture, guided when the user was asked about view-only), and each consent starts one
session, within 10 minutes.

### Masking

List your sensitive views in `maskedViews` (or `setMaskedViews` later): they are measured on the
main thread with every captured frame and painted out of it, so a mask moves with its view.
Password fields (an `EditText` whose inputType is a password variation) are masked without being
listed, and typing never reaches them.

**Jetpack Compose:** Compose text fields are not Views, and this library has no Compose
dependency, so a Compose password field is NOT found by itself: give its rect
(`boundsInWindow()` from `onGloballyPositioned`) to `maskedRects` / `setMaskedRects`. In a Compose
screen the focused View is the whole ComposeView, so while any mask is set guided typing and keys
are refused there (fail closed: the library cannot tell which field has focus).

**Wrappers whose whole UI is one View** (Flutter, React Native, Compose-heavy apps) tell the SDK
where keyboard focus is instead: `help.setSecretFocus(true)` when focus enters a secret field,
`setSecretFocus(false)` when it leaves. While true, guided typing and keys are refused (`masked`);
once called, this replaces the "focused View under a mask" rule. A native password `EditText` is
always refused. Masks fail
closed: if your window cannot be located, if a frame's shape does not match the screen it claims
(a rotation in flight), and for 300 ms after the masks or your window move, the whole picture is
painted out.

A window with `FLAG_SECURE` (one your app protects from screenshots) is never captured: the agent
sees black and is told the screen is protected.

### Guided mode

Only when your app passes `guided: true` **and** your backend created the session as `cobrowse`.
The agent's clicks become taps, scrolling becomes a mouse-wheel scroll, typing becomes key events
(text no key map can type goes into the focused `EditText`), and Enter, Tab, Backspace, Delete,
Escape, arrows, Home and End are keys — all dispatched to the resumed activity's own window. A
request outside your window, on a masked region, on the indicator, or while none of your
activities is in front with focus (a dialog, the notification shade or another app over it) is
refused; typing and keys never go to a password field or a masked field, and Tab never moves focus
into one. Your customer can pause it from the indicator, and only they can resume it; you can pause
too (`setPaused(true)`) and resume your own pause.

The indicator check is cooperative: it runs in your process. What the library enforces whatever
your app does is consent bound to the session, masks painted before encoding, and the guided-input
rules.

### Sharing the whole screen (opt-in)

`capture = Capture.SCREEN` shares the device screen through MediaProjection: Android shows its own
consent after yours, and a foreground service runs while it is shared. Declare in your manifest:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

Without them, starting fails with `capture_failed`. Masks still apply to your own window's regions;
guided input still lands only in your app.

### Events

`State`, `Code(code, sessionId, mode)`, `Joined(agent)`, `Left`, `Control(allowed)`,
`Action(action, applied, reason)`, `Error(code, message)`, `Ended(reason)` (`stopped`,
`app_stopped`, `disconnected`, `expired`, `error` — always last), on the main thread. One session at
a time (a second `start` throws `busy`).

### Limits

The window capture copies your activity's window: dialogs and popups shown in windows of their own
are not in the picture (the customer still sees them), and nothing is sent while such a window has
focus. In `Capture.SCREEN` mode a rotation pauses the picture until the orientation returns. `stop()` returns at once; the session winds
down in the background.

## Examples

`examples/views` (Android Views) and `examples/compose` (Jetpack Compose): "Acme Bank" with a
masked card, a Buy button and Get help. Their intent extras (`token`, `server`, `autostart`,
`guided`, `cmd=stop|pause|resume`) drive automated tests; events are logged under `GaiaDeskDemo`.

```sh
./gradlew :examples:compose:installDebug
adb shell am start -n net.gaiadesk.examples.compose/.MainActivity --es token gdemb_…
```

## Releasing (maintainers)

```sh
# 1. In GaiaDesk: scripts/build-embed-android.sh (or the farm's embed-android.yml)
#    → embed/out/android/jniLibs.zip (+ .sha256)
# 2. Here: checks the .so files, runs the JVM tests, publishes into embed/build/staging-repo,
#    gathers dist/ (AAR, sources, javadoc, POM, jniLibs.zip, the Maven bundle, SHA256SUMS):
scripts/release.sh 0.1.0 <gaiadesk checkout>/embed/out/android/jniLibs.zip
# 3. It prints the rest: tag, `gh release create … dist/*`, and the Maven Central upload
#    (`./gradlew :embed:publishAllPublicationsToMavenCentralRepository` with a Central Portal
#    token and a signing key in ORG_GRADLE_PROJECT_* env vars; see embed/build.gradle.kts).
```

## Licence

The Kotlin sources here are MIT (`LICENSE`). The prebuilt `libgaiadesk_embed.so` is licensed under
the GaiaDesk Native Binary Licence (`LICENSE-BINARY`): free to use in your apps, inside them,
unmodified.
