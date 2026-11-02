# Testing

All testing is done on a physical Android device connected over USB, driven
with `adb`. There is no emulator setup.

The reference device is a Samsung Galaxy Tab S9 (Android 16, 2560×1600,
landscape). Any device with Android 7.0 or newer works the same way.

## 1. Setup

1. On the device, turn on **Developer options** and **USB debugging**, then
   connect it and accept the debugging prompt.
2. Check the connection. If more than one device is listed, add
   `-s <serial>` to every `adb` command below.

   ```bash
   adb devices -l
   ```

3. Build and lint. Fix any lint error before testing.

   ```bash
   ./gradlew assembleRelease lintRelease
   ```

4. Install over the existing app. `-r` keeps the app's data, so saved
   actions and macros survive the update.

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

5. Turn on the Auto Clicker service **on the device** (Settings →
   Accessibility → Installed apps → Auto Clicker). Don't use
   `settings put secure enabled_accessibility_services`: it replaces the
   whole list and turns off any other accessibility services the owner uses.
6. Check that the service is running. After an update it reconnects on its
   own within a few seconds.

   ```bash
   adb shell dumpsys accessibility | grep com.mobile.autoclicker
   ```

## 2. Ground rules for a personal device

The test device is someone's real tablet or phone, with their own apps,
accounts and saved macros. Every test must leave it as it was.

- **Keep the owner's setup.** Before changing actions or settings, save them
  under Macros → **Save current setup** and load that macro again when you're
  done, then delete it. For a quick test, add your own actions and remove
  them afterwards with **−** (it removes the last action).
- **Only touch empty space.** Run taps over empty wallpaper on the home
  screen. Check a screenshot before every `input tap`, because a missed
  coordinate can open an app.
- **Stop before a run leaves the screen you planned for.** A swipe that opens
  another screen (for example the app drawer) puts the following taps on that
  new screen. Give the swipe a long **Delay after** (10000 ms) in its editor
  card so there is time to press stop.
- **Don't force-stop the app** (`am force-stop`, `am start -S`). Android turns
  the accessibility service off when an app is force-stopped.
- **Don't change device settings.** Leave dark mode, display size, developer
  options such as pointer location, and the app's window mode (for example
  Samsung pop-up view) as the owner set them. Test what the device already
  uses.
- **Find coordinates from screenshots,** not `uiautomator dump`. It starts its
  own accessibility connection while the app's service and any others are
  running.
- **Keep screenshots off the device.** Delete each one from `/sdcard` after
  pulling it.

## 3. Tools

### Screenshots

```bash
adb shell screencap -p /sdcard/ac_s.png
adb pull /sdcard/ac_s.png shot.png
adb shell rm /sdcard/ac_s.png
```

On Windows PowerShell 5, don't use `adb exec-out screencap -p > shot.png`.
PowerShell rewrites the binary output and the PNG comes out broken.

Screenshots are full resolution, and `adb shell input tap X Y` takes the same
pixel coordinates. If an image viewer scales the screenshot down, scale the
coordinates back up before tapping.

### Tapping and swiping as the tester

```bash
adb shell input tap X Y
adb shell input swipe X1 Y1 X2 Y2 300
adb shell input keyevent KEYCODE_HOME
```

### Checking that the app's gestures really happened

- **Visible effect:** an upward swipe on the home screen opens the app
  drawer.
- **Timing:** Android keeps the last ten input events.

  ```bash
  adb shell dumpsys input | grep -A 11 RecentQueue
  ```

  Each tap shows up as a DOWN/UP pair, and the pair's two `age` values differ
  by the tap duration (10 ms by default). The gap between pairs is the wait
  between actions. Some vendors, Samsung included, hide coordinates and
  actions here and show only `age`, which is still enough to check timing.
  Poll it every half second during a run to collect more than ten events.
- **Crashes:**

  ```bash
  adb logcat -d -b crash | grep -A 5 autoclicker
  ```

## 4. Checklist

Run the whole list before a release. After a small change, run the sections
it touches.

### Settings screen

- [ ] Status card reads "Service is off" (grey card, red icon) when the
      service is off, and switches to "Service is on" without leaving the app
      when the service turns on.
- [ ] Main button opens Accessibility settings when off, and shows the
      floating controls when on.
- [ ] Number fields save as you type. Interval below 10 ms, or a tap or swipe
      duration outside 1–60000 ms, shows an error and isn't saved.
- [ ] Random offset slider and its "px" label update together.
- [ ] Light and dark mode both look right; on Android 12+ colors follow the
      wallpaper.
- [ ] Wide screens (840 dp and up, such as a tablet in landscape at full
      screen) show two columns. Narrow windows, including Samsung pop-up view,
      show one column.

### Floating panel and markers

- [ ] Panel appears when the service connects. The bar drags the panel.
- [ ] Target button adds a numbered tap marker. Arrow button adds a swipe
      (start marker, end marker, arrow between them, all lined up).
- [ ] Markers drag, and the swipe arrow follows.
- [ ] **−** removes the last action. **✕** hides everything, and **Show
      floating controls** brings it back with the same actions.

### Editor card

- [ ] Tapping a marker (without dragging) opens the editor card and hides the
      panel and markers.
- [ ] The card is a floating card, not a separate window, including when the
      app is in Samsung pop-up view.
- [ ] Tapping a field opens the number keyboard below the card without
      covering it, and typing works.
- [ ] Save keeps the values (reopen to check). Blank fields fall back to the
      defaults shown in the helper text.
- [ ] Cancel and the Back key close without saving. Delete removes the action
      and renumbers the rest.

### Running

- [ ] Play turns into a red stop button with a cycle counter. **+**/**−**
      grey out and markers dim and let touches through.
- [ ] Taps happen at the set interval (check with `dumpsys input`).
- [ ] A swipe performs the gesture (app drawer opens).
- [ ] A per-action delay replaces the interval after that action.
- [ ] Start delay shows a countdown ("3s", "2s", ...) before the first action.
- [ ] Stop after (seconds) and repeat cycles both stop the run by themselves.
- [ ] Random interval spreads the waits across interval ± the random value.
- [ ] Stop works at any point, including during the countdown.

### Macros and updates

- [ ] Save current setup asks for a name and lists the macro with its action
      count and interval.
- [ ] Load restores the actions on screen and the settings fields.
- [ ] Delete asks first, then removes the macro.
- [ ] `adb install -r` over the previous version keeps actions, settings and
      macros.

### Finish

- [ ] Restore the owner's actions and settings (load the backup macro, then
      delete it).
- [ ] Return to the home screen, and leave the panel the way you found it.
- [ ] No new entries in the crash log.
