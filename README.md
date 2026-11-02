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
- Keyboard layout: with a keyboard and mouse plugged in (for example
  through a USB-C hub), lettered key points tap when you press their letter,
  WASD or the arrow keys drive a joystick finger, and the mouse turns the
  camera
- Macros: save the current actions and settings under a name, load or
  delete them later
- Keyboard profiles: save a keyboard layout per game, then load, rename,
  update or delete it
- Positions and settings are remembered between sessions
- Material 3 design with light and dark mode. On Android 12+ the app, panel
  and markers take their colors from the wallpaper (Material You), and the
  launcher icon supports themed icons on Android 13+
- Two-column settings screen on tablets and other wide screens

## Install

1. Download the APK from the
   [latest release](https://github.com/CaiCheng-Li/MobileMacro/releases/latest)
   and open it on the device, allowing installs from unknown sources if
   asked. Or install it over USB:

   ```bash
   adb install -r AutoClicker-v1.1.apk
   ```

   You can also build it yourself (see [Build from source](#build-from-source)).

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

### Keyboard layout

Plug a keyboard into the device (a USB-C hub works, so does Bluetooth).
Press the **keyboard** button on the floating panel to switch the panel to
the keyboard layout. The macro markers hide, and the panel buttons now edit
this layout:

- **+** adds a key point. Points take the next free letter: A, B, C and so
  on, skipping W, A, S and D while the joystick is shown. Drag them into
  place. Tap one to give it another key (press its key button, then any
  key) or to delete it.
- **Joystick** shows or hides the joystick, a big circle you drag into place
  (Android 8.0+).
- **−** removes the last key point.
- **Play** starts listening; **stop** ends it.

While listening:

- Pressing a point's key taps it. Holding the key holds the touch.
- Holding W, A, S, D or the arrow keys puts a finger down at the joystick's
  center and pushes it toward the circle's edge; two keys together push it
  diagonally. Letting go lifts the finger. Put the circle's center on the
  game's move stick: games with a floating stick center it where the finger
  lands. If a key point uses one of these keys, the key point wins.
- On Android 14+, moving the mouse turns the camera: each movement becomes a
  short drag from the **LOOK** point, which you drag onto the game's camera
  area. Turn it off with **Mouse turns the camera** in the app.
- Other keys, and combinations with Ctrl, Alt or Meta, type as usual.

Set the joystick's size and reach, and the mouse sensitivity, under
**Keyboard layout** in the app. Keep the reach inside the game's stick: a
floating stick follows a finger that goes past its edge. Press the keyboard
button again to go back to the macro.

### Keyboard profiles

A profile holds a whole keyboard layout: the key points, the joystick, the
LOOK point and their settings. Keep one per game under **Keyboard profiles**
in the app:

- **Save current layout** stores the layout under a name. Using a name that
  already exists asks before replacing that profile.
- **Load** puts that profile's layout on screen and stops listening.
- **Edit** (pencil) renames the profile. Tick **Replace with the current
  layout** to also update it to what is on screen now.
- **Delete** (bin) removes it after asking.

Macros and profiles are separate: loading a macro doesn't change the
keyboard layout, and loading a profile doesn't change the macro.

The camera can't turn while a key is held. While the app holds a finger down
through the accessibility service, Android hides mouse movement from it. A
real touch on the screen briefly lifts the held fingers; they go back down
at their starting point about a quarter of a second after the touch ends.

## How it works

The app is an accessibility service. It draws the panel, markers, swipe
arrows and editor card as accessibility overlay windows, and performs each
tap or swipe with `dispatchGesture`. It does not read screen content.
While the keyboard layout is listening, it also filters key events and, on
Android 14+, mouse events, and turns them into gestures.

| File | What it does |
| --- | --- |
| `ClickService.kt` | Accessibility service: floating panel, markers, editor card, running the macro, the keyboard layout |
| `Overlays.kt` | Drawing for markers, swipe arrows and the joystick; the editor card's root view |
| `Fingers.kt` | Touches held down for keys and the joystick, sent as continued gestures |
| `Settings.kt` | Stored settings, the action list, the keyboard layout, and saved macros and profiles (SharedPreferences, JSON) |
| `MainActivity.kt` | Settings screen, service status, macro and profile lists |
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
