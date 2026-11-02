# MobileMacro

An Android auto clicker that runs tap and swipe macros on any screen. The
app is called **Auto Clicker** on the device. Works on Android 7.0 (API 24)
and newer. No root needed.

## Features

- Floating control panel over any app: start/stop, add tap, add swipe,
  remove last action, hide
- Any number of taps and swipes, run in order (1, 2, 3, ...). Swipes show a
  start marker, an end marker and an arrow between them
- Per-action delay and duration: tap any marker to override the defaults
- Timing: interval between actions, random interval (± ms), tap duration,
  swipe duration
- Run control: start delay countdown, stop after a time limit, repeat cycles
- Random position offset so touches don't land on the same pixel
- Macros: save the current actions and settings under a name, load or
  delete them later
- Positions and settings are remembered between sessions
- Material 3 design with light and dark mode. On Android 12+ the app, panel
  and markers take their colors from the wallpaper (Material You), and the
  launcher icon supports themed icons on Android 13+
- Two-column settings screen on tablets and other wide screens

## Install

1. Build the APK (see [Build from source](#build-from-source)) and copy it to
   the device, or install it over USB:

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

2. Open **Auto Clicker** and tap **Open Accessibility settings**.
3. Find **Auto Clicker** in the list (sometimes under "Installed apps" or
   "Downloaded apps") and turn it on.

**Android 13+:** if the switch is greyed out with "Restricted setting", go to
Settings → Apps → Auto Clicker → ⋮ (top right) → **Allow restricted
settings**, then try again. This happens for APKs installed from a file
manager or browser, not for `adb install`.

## Use

Floating panel, top to bottom:

- **Bar** drag to move the panel
- **Play / stop** (round button) start or stop the macro. While running, the
  number under it shows the countdown or the current cycle
- **Target** add a tap, then drag it into place
- **Dot and arrow** add a swipe, then drag its start and end markers
- **−** remove the last action
- **✕** hide the panel (bring it back from the app's **Show floating controls** button)

Tap a marker (without dragging) to open its editor card, where you can set
that action's own delay and duration or delete it. Leave a field blank to
use the app's default.

The app's settings save as you type and apply the next time you press play.
Each wait is the interval, or the action's own delay, plus or minus a random
amount up to the random interval. Waits never go below 10 ms.

Actions can't be moved, added or edited while running; stop first.

## How it works

The app is an accessibility service. It draws the panel, markers, swipe
arrows and editor card as accessibility overlay windows, and performs each
tap or swipe with `dispatchGesture`. It does not read screen content.

| File | What it does |
| --- | --- |
| `ClickService.kt` | Accessibility service: floating panel, markers, editor card, running the macro |
| `Overlays.kt` | Drawing for markers and swipe arrows; the editor card's root view |
| `Settings.kt` | Stored settings, the action list and saved macros (SharedPreferences, JSON) |
| `MainActivity.kt` | Settings screen, service status and macro list |
| `App.kt` | Turns on Material You colors |

## Build from source

Needs JDK 17+ and the Android SDK (platform 35). `local.properties` must
point `sdk.dir` at the SDK.

```bash
./gradlew assembleRelease lintRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. It is
signed with the debug key, so it installs directly but isn't meant for the
Play Store.

On some Windows machines Gradle fails with "Unable to establish loopback
connection". Giving Java a short folder for its local sockets fixes it
(PowerShell):

```powershell
New-Item -ItemType Directory -Force C:\Users\Public\uds
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\Users\Public\uds"
```

## Testing

Testing is done on a physical device over USB. See [TESTING.md](TESTING.md)
for the setup, the safety rules for testing on a personal device, and the
full checklist.
