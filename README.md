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

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.gaiadesk:embed-android:0.1.0")
}
```

To build the AAR yourself: `scripts/fetch-binary.sh 0.1.0` puts a release's `.so` files in
`embed/src/main/jniLibs`, then `./gradlew :embed:assembleRelease`
(`./gradlew :embed:publishToMavenLocal` for a local Maven copy).

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
    GaiaDeskEmbed.requestConsent(activity, "Acme") { agreed ->
        if (!agreed) return@requestConsent
        try {
            help = GaiaDeskEmbed.start(
                activity,
                GaiaDeskEmbed.Configuration(token, "Acme", maskedRects = listOfNotNull(GaiaDeskEmbed.maskedRectOf(cardView))),
                consentGranted = agreed,
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
| `maskedRects` | regions painted out from the first frame: window pixels (`maskedRectOf(view)`) |
| `capture` | `Capture.APP` (default) or `Capture.SCREEN` (opt-in, see below) |

### Masking

Pass the regions you know at start (`maskedRectOf(view)`; in Compose, `boundsInWindow()` from
`onGloballyPositioned`): an agent can join straight away, and only start-time masks are guaranteed
to cover the first frame. Later, `setMaskedViews` / `setMaskedRects` replace the set; call them
again when your layout changes. If your window cannot be located, the whole picture is painted out.

### Guided mode

Only when your app passes `guided: true` **and** your backend created the session as `cobrowse`.
The agent's clicks become taps, scrolling becomes a mouse-wheel scroll, typing becomes key events
(text no key map can type goes into the focused `EditText`), and Enter, Tab, Backspace, Delete,
Escape, arrows, Home and End are keys — all dispatched to the resumed activity's own window. A
request outside your window, on a masked region, on the indicator, or while none of your
activities is in front is refused; your customer can pause it from the indicator (or you call
`setPaused(true)`).

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
are not in the picture (the customer still sees them). `stop()` returns at once; the session winds
down in the background.

## Examples

`examples/views` (Android Views) and `examples/compose` (Jetpack Compose): "Acme Bank" with a
masked card, a Buy button and Get help. Their intent extras (`token`, `server`, `autostart`,
`guided`, `cmd=stop|pause|resume`) drive automated tests; events are logged under `GaiaDeskDemo`.

```sh
./gradlew :examples:compose:installDebug
adb shell am start -n net.gaiadesk.examples.compose/.MainActivity --es token gdemb_…
```

## Licence

The Kotlin sources here are MIT (`LICENSE`). The prebuilt `libgaiadesk_embed.so` is licensed under
the GaiaDesk Native Binary Licence (`LICENSE-BINARY`): free to use in your apps, inside them,
unmodified.
