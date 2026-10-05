# Merit1st (SC OS3) — Complete User & Administrator Guide

---

## Table of Contents

1. App Overview
2. Installation & Permissions
3. Main Interface — Overlay System
4. All Buttons — Complete Reference
5. Capture Modes
6. Camera Features — Zoom, Focus, Lens
7. Settings — Complete Guide
8. Email Delivery — Gmail Setup
9. Storage — Where Photos Are Saved
10. Volume Button Control
11. Foreground Service & Background Capture
12. Device Diagnostics
13. Accessing Private Storage via CMD
14. Troubleshooting
15. Technical Reference

---

## 1. App Overview

**Merit1st** (internal name: SC OS3) is a floating overlay camera application for Android. It renders a small, draggable camera preview on top of any other app, allowing you to capture photos discreetly without switching away from your current application.

| Property | Value |
|---|---|
| App name | Merit1st |
| Package | `com.scos3.camera` |
| Version | 0.1.0 (versionCode 1) |
| Min Android | 10 (API 29) |
| Target Android | 16 (API 36) |
| Camera | CameraX + Camera2 |
| Face detection | Google ML Kit |
| Email | Gmail SMTP (JavaMail) |

**Key features:**
- Floating camera preview overlay (draggable, resizable)
- Single photo capture with autofocus
- Burst capture (5 consecutive photos)
- Auto / interval capture (2s, 4s, 6s, 10s intervals)
- Face-detection auto capture
- Front/back camera switch
- Ultra-wide (0.5x) camera support where available
- Zoom slider
- Email delivery via Gmail SMTP
- Save to Gallery or private storage
- Volume button shortcuts (via accessibility service)
- Background capture with notification

---

## 2. Installation & Permissions

### Required Permissions

When you first open the app, it will request these permissions:

| Permission | Why | How to Grant |
|---|---|---|
| **Camera** | Takes photos | Tap "Allow camera" or allow in Android Settings > Apps > Merit1st > Permissions |
| **Notifications** | Shows capture status in notification bar | Allow when prompted (Android 13+) |
| **Display over other apps** | Shows the floating overlay on top of other apps | Tap "Open settings" in the overlay permission card, find Merit1st, and enable |

### If Camera Permission Does Not Work

If tapping "Allow camera" does not respond:
1. Go to Android **Settings** > **Apps** > **Merit1st** > **Permissions**
2. Tap **Camera** and select **Allow**
3. Force-close and reopen the app

### Via ADB (for developers/testers)

```bash
adb shell pm grant com.scos3.camera android.permission.CAMERA
adb shell pm grant com.scos3.camera android.permission.POST_NOTIFICATIONS
adb shell am start -n com.scos3.camera/.ui.camera.MainActivity
```

---

## 3. Main Interface — Overlay System

Merit1st uses two floating overlay windows that appear on top of your current app:

### Upper Overlay (top of screen)

```
 +--------------------------------------------------+
 |                                                  |
 |  [BLACK]                    [SETTING]            |
 |  [SWITCH]                   [HELP]               |
 |                                                  |
 |  +----------------------+                        |
 |  |                      |  <- Camera Preview     |
 |  |    Live Preview      |    (240x320dp)        |
 |  |    (draggable)       |                        |
 |  |                      |                        |
 |  +----------------------+                        |
 |  "Merit1st - drag"                               |
 |                                                  |
 |  <--SIZE slider-->          <--ZOOM slider-->    |
 |                                                  |
 |         +-------------+                          |
 |         |   Ready     |  <- Status chip          |
 |         +-------------+                          |
 +--------------------------------------------------+
```

### Lower Overlay (bottom of screen)

```
 +--------------------------------------------------+
 |                                                  |
 |  +--------+--------+--------+--------+           |
 |  | BURST  |CAPTURE |  AUTO  |  FACE  |  <- Mode |
 |  +--------+--------+--------+--------+    row    |
 |                                                  |
 |  [MINIMIZE]                    [EXIT]            |
 +--------------------------------------------------+
```

### Dragging the Preview

Touch and hold the **"Merit1st - drag"** label below the camera preview to reposition the entire floating panel anywhere on screen.

### Resizing the Preview

Use the **SIZE slider** (left side) to make the camera preview window larger or smaller. Range: 24% to 62% of screen width.

---

## 4. All Buttons — Complete Reference

### Mode Buttons (Bottom Row)

| Button | Action | Details |
|---|---|---|
| **CAPTURE** | Take one photo | Triggers autofocus at center, then captures. Shows "Saved: Merit1st_yyyyMMdd_HHmmssSSS.jpg". Produces a short vibration (65ms). |
| **BURST** | Take 5 consecutive photos | Captures 5 photos with 350ms delay between each. Button text changes to "STOP" while running. Progress shown as "Burst 1/5", "Burst 2/5", etc. Tap STOP to cancel. |
| **AUTO** | Start/stop interval capture | Starts taking photos at the configured interval (2/4/6/10/12/14 seconds). Runs in foreground service (works even when screen is off). Button changes to "Stop capture". Volume Up also toggles this. |
| **FACE** | Enable face-detection capture | When ON, automatically takes a photo whenever a face is detected in the preview. 3-second cooldown between captures. Button changes to "FACE ON". |

### Control Buttons (Top Area)

| Button | Action | Details |
|---|---|---|
| **BLACK** | Blackout mode | Hides the camera preview but keeps the camera running. Useful for discreet capture. Toggle — tap again to show preview. Button changes to "BLACK ON". |
| **SWITCH** | Switch camera | Toggles between back and front camera. |
| **SETTING** | Open settings | Opens the Settings screen. Overlay is temporarily hidden. |
| **HELP** | Show help | Displays a help dialog with button descriptions. Also has a "Device diagnostics" button. |

### Bottom Buttons

| Button | Action | Details |
|---|---|---|
| **MINIMIZE** | Minimize to background | Hides the overlay but keeps AUTO capture running in the background. A notification shows the active state. Tap the notification to return. |
| **EXIT** | Stop and close | Stops all capture activity, stops the foreground service, removes the overlay, and closes the app. |

### Sliders

| Slider | Location | Action |
|---|---|---|
| **SIZE** | Left side | Adjusts the camera preview window size. Default position: 22 (small). |
| **ZOOM** | Right side | Controls digital zoom from 0.5x (ultra-wide) to max zoom. Zoom text readout ("1.0x") is also tappable to toggle between wide and 1.0x. |

### Preview Tap

| Gesture | Action |
|---|---|
| **Tap the preview** | Triggers tap-to-focus at the tapped point. Uses auto-focus + auto-exposure. |

---

## 5. Capture Modes

### Single Capture (CAPTURE)

1. Tap **CAPTURE** or press **Volume Down**
2. Camera autofocuses at center of preview (up to 2 seconds)
3. Photo is captured as JPEG
4. Stored to Gallery or private storage (based on setting)
5. Email queued if enabled
6. Short vibration confirms capture
7. Status shows "Saved: [filename]"

### Burst Capture (BURST)

1. Tap **BURST** to start
2. 5 photos captured consecutively, 350ms apart
3. Progress: "Burst 1/5", "Burst 2/5", ...
4. Other buttons (CAPTURE, AUTO, SWITCH, SIZE, ZOOM) are disabled during burst
5. Tap **STOP** (the BURST button changes to STOP) to cancel mid-burst
6. Result: "Burst done - N saved" or "Burst cancelled"

### Auto / Interval Capture (AUTO)

1. Set the interval in Settings (2s, 4s, 6s, 10s, 12s, 14s) or use the inline interval selector (2s/4s/6s/10s)
2. Tap **AUTO** or press **Volume Up**
3. A foreground notification appears: "Auto capture active"
4. Photos are taken at the selected interval
5. Camera continues even when screen is off or app is minimized
6. Tap **MINIMIZE** to hide the overlay while auto continues
7. Tap the notification or reopen the app, then tap **Stop capture** to stop
8. Status: "Auto capture active - every Ns - M saved"

### Face Detection Capture (FACE)

1. Tap **FACE** to enable (button shows "FACE ON")
2. ML Kit face detection analyzes the preview
3. When a face is detected, a photo is automatically taken
4. 3-second cooldown between captures (prevents rapid-fire)
5. Tap **FACE** again to disable
6. Works with both front and back cameras


---

## 6. Camera Features — Zoom, Focus, Lens

### Zoom

- Use the **ZOOM slider** on the right side of the upper overlay
- Range depends on device (typically 0.5x to 10x)
- Tap the zoom readout text ("1.0x") to quickly toggle between ultra-wide (0.5x) and normal (1.0x)
- If your device has an ultra-wide camera, dragging below 1.0x activates it

### Ultra-Wide (0.5x) Support

- Automatically detected on devices with a dedicated ultra-wide camera
- Check **Settings** > Ultra-wide info to see if your device supports it
- Also check via **Diagnostics** for detailed camera hardware info

### Tap to Focus

- Tap anywhere on the camera preview to focus at that point
- Uses auto-focus + auto-exposure metering

### Front/Back Camera

- Tap **SWITCH** to toggle between back and front cameras
- The default camera can be set in **Settings** > Default camera
- Ultra-wide is only available on the back camera

---

## 7. Settings — Complete Guide

Access via **SETTING** button on the overlay.

### Camera Settings

| Setting | Options | Default | Description |
|---|---|---|---|
| Image resolution | AUTO + device-specific sizes | AUTO | AUTO selects the best resolution. You can also choose a specific resolution (e.g., "12 MP 4000x3000"). |
| Image quality | Low (70), Standard (85), High (95) | High (95) | Higher quality = larger file size. |
| Default camera | Back camera, Front camera | Back camera | Which camera opens on launch. |

### Capture Settings

| Setting | Options | Default | Description |
|---|---|---|---|
| Auto capture interval | 2s, 4s, 6s, 10s, 12s, 14s | 4s | Time between each auto capture photo. |

### Delivery Settings

| Setting | Options | Default | Description |
|---|---|---|---|
| Email sending | ON / OFF | **OFF** | When ON, every captured photo is compressed and queued for delivery via Gmail SMTP. |
| Save photos to Gallery | ON / OFF | **ON** | When ON, photos save to the Android Gallery (Pictures/Merit1st/). When OFF, photos save to app-private storage only. |

> **Important:** If both Email and Gallery are OFF, photos still save to app-private storage and remain available for email if you turn email back on later.

### Volume Button Control

| Setting | Description |
|---|---|
| Status | Shows whether the accessibility service is enabled |
| Open Accessibility Settings | Takes you to Android Accessibility settings to enable "Merit1st volume control" |

When enabled:
- **Volume Down** = Take one photo
- **Volume Up** = Toggle auto capture
- Volume buttons work normally in all other apps

### Gmail Configuration

| Field | Description |
|---|---|
| Sender email | Your Gmail address (e.g., you@gmail.com) |
| Sender app password | 16-character Gmail App Password (NOT your regular password) |
| Receiver email | Where to send photos (can be the same as sender) |
| Test email | Sends a test message to verify the setup |
| Remove stored credentials | Clears saved Gmail credentials |

### Other

| Button | Description |
|---|---|
| Open device diagnostics | Shows camera hardware information |
| Save settings | Saves all settings and closes |

---

## 8. Email Delivery — Gmail Setup

### How It Works

When email is ON, every photo you capture is:
1. Compressed to max 2048px, JPEG quality 80 (smaller file for email)
2. Added to a persistent queue (survives app restarts)
3. Sent via Gmail SMTP (smtp.gmail.com, port 465, SSL)
4. If sending fails, it retries with exponential backoff (15s, 30s, 60s, ... up to 5 minutes)
5. If network is unavailable, photos stay queued and retry when network returns

### Step-by-Step: How to Create a Gmail App Password

**IMPORTANT:** You CANNOT use your regular Gmail password. You must create a special 16-character "App Password" for Merit1st.

#### Prerequisites
- A Gmail account (you@gmail.com)
- **2-Step Verification must be enabled** on your Google account

#### Steps to Enable 2-Step Verification (if not already enabled):

1. Open a browser and go to: **https://myaccount.google.com/security**
2. Scroll to **"How you sign in to Google"**
3. Click **2-Step Verification**
4. Follow the prompts to enable it (requires a phone number)
5. Verify it is ON

#### Steps to Create the App Password:

1. Go to: **https://myaccount.google.com/apppasswords**
   - (If you cannot find it, search "App Passwords" in the Google Account search bar)
2. You may be asked to sign in again
3. At the bottom, under **"App name"**, type: `Merit1st`
4. Click **"Create"**
5. Google will show a **16-character password** like: `abcd efgh ijkl mnop`
6. **Copy this password** (remove the spaces — it becomes `abcdefghijklmnop`)
7. Click **Done**

> **Save this password.** You will only see it once. If you lose it, create a new one at the same page.

#### How to Enter in Merit1st:

1. Open Merit1st > tap **SETTING**
2. Under **"Gmail (email delivery)"**:
   - **Sender email:** Enter your full Gmail address (e.g., `you@gmail.com`)
   - **Sender app password:** Enter the 16-character app password (no spaces)
   - **Receiver email:** Enter where you want photos sent (can be the same email)
3. Tap **"Test email"** to verify it works
4. If "Test email sent" appears, the setup is correct
5. Turn **Email sending** ON
6. Tap **"Save settings"**

### Gmail App Password — Common Errors

| Error Message | Cause | Fix |
|---|---|---|
| "Gmail authentication failed" | Wrong password or 2-Step Verification not enabled | Re-create the App Password. Ensure 2-Step Verification is ON. |
| "Network unavailable" | No internet connection | Connect to Wi-Fi or mobile data. Photos stay queued. |
| "Configure sender, app password and receiver first" | Fields are empty | Fill in all three Gmail fields. |

### Email Security

- Credentials are stored encrypted on the device using Android Keystore (AES-256-GCM)
- They are never sent to any third-party server — only used with smtp.gmail.com
- Credentials are never written to logs, source code, or build files
- You can clear them anytime via **"Remove stored credentials"**

---

## 9. Storage — Where Photos Are Saved

### Gallery Mode (Default: ON)

| Property | Value |
|---|---|
| Storage type | Android MediaStore (Gallery-visible) |
| Folder | **Pictures/Merit1st/** |
| Full path | `/storage/emulated/0/Pictures/Merit1st/` |
| File naming | `Merit1st_yyyyMMdd_HHmmssSSS.jpg` |
| Visibility | Visible in Android Gallery, Google Photos, file managers |
| Example | `Merit1st_20260822_143025123.jpg` |

### Private Storage Mode (When Gallery is OFF)

| Property | Value |
|---|---|
| Storage type | App-private files directory |
| Folder | **private_captures/** |
| Full path | `/data/data/com.scos3.camera/files/private_captures/` |
| File naming | `Merit1st_yyyyMMdd_HHmmssSSS.jpg` |
| Visibility | NOT visible in Gallery. Only accessible via ADB or root. |
| Persistence | Survives app restarts. NOT deleted as cache. |

### Storage Decision

The app checks the "Save photos to Gallery" setting for every capture:
- **ON** -> Photos go to MediaStore (Gallery)
- **OFF** -> Photos go to app-private storage

Both paths receive the original JPEG byte-for-byte (no recompression).

### Email Compression (Separate from Storage)

When email is ON, the original JPEG is also:
- Compressed to max 2048px / quality 80
- Saved to: `/data/data/com.scos3.camera/files/email_captures/`
- This is a **copy** — the original photo is never modified
- The compressed copy is deleted after successful email delivery

---

## 10. Volume Button Control

### What It Does

When enabled, you can use your phone's physical volume buttons to control Merit1st without touching the overlay:

| Button | Action |
|---|---|
| **Volume Down** | Take one photo (same as CAPTURE button) |
| **Volume Up** | Toggle auto capture on/off (same as AUTO button) |

### How to Enable

1. Open Merit1st > tap **SETTING**
2. Under **"Volume button control"**, tap **"Open Accessibility Settings"**
3. Find **"Merit1st volume control"** in the accessibility list
4. Toggle it ON
5. Confirm the dialog (the service only intercepts volume keys when Merit1st overlay is active)

### Important Notes

- Volume buttons work **normally** in all other apps
- They only control Merit1st when the floating overlay is visible on screen
- The service does NOT read screen content, text, or any other app data
- It only intercepts volume key press events
- You can disable it anytime in Accessibility Settings

---

## 11. Foreground Service & Background Capture

### What Happens When You MINIMIZE

When AUTO capture is running and you tap MINIMIZE:
1. The overlay hides (no visible UI)
2. A foreground notification appears: "Merit1st - Auto capture active"
3. The camera stays open via a foreground service
4. Photos continue to be taken at the configured interval
5. A partial wake lock keeps the CPU running (capture works even when screen is off)
6. Tap the notification to return to the overlay

### What Happens When You EXIT

Tapping EXIT:
1. Stops all capture (single, burst, auto, face)
2. Stops the foreground service
3. Releases the wake lock
4. Removes the overlay
5. Closes the app

### Notification Controls

The notification shows:
- "Merit1st" title
- "Auto capture active" subtitle
- Current interval and photo count
- **Open** button (returns to overlay)
- **Stop** button (stops capture and service)

---

## 12. Device Diagnostics

Access via **SETTING** > **"Open device diagnostics"** or via **HELP** > **"Device diagnostics"**.

The diagnostics screen shows:
- Device manufacturer and model
- Android version and SDK level
- All Camera2 camera IDs (facing, logical/physical, capabilities)
- Back camera zoom range
- Ultra-wide camera detection (physical camera ID, FOV delta)
- Sub-unit zoom support (zoom below 1.0x)
- Supported JPEG resolutions (back and front, top 10)
- Overall verdict on ultra-wide support

Use the **Copy** button to copy the report to clipboard, or **Share** to send it.


---

## 13. Accessing Private Storage via CMD

When "Save photos to Gallery" is OFF, photos are saved to app-private storage. Here is how to access them.

### Via ADB Shell (No Root Required)

```bash
# List all saved photos
adb shell ls -la /sdcard/Android/data/com.scos3.camera/files/private_captures/

# Pull a specific photo to your computer
adb pull /sdcard/Android/data/com.scos3.camera/files/private_captures/Merit1st_20260822_143025123.jpg C:\Users\YourName\Pictures\

# Pull ALL photos at once
adb pull /sdcard/Android/data/com.scos3.camera/files/private_captures/ C:\Users\YourName\Merit1st_Backup\

# Check how many photos are stored
adb shell ls /sdcard/Android/data/com.scos3.camera/files/private_captures/ | wc -l
```

### Via ADB Shell (Alternative Path for Debug Builds)

```bash
# For debug builds, use run-as to access internal storage
adb shell run-as com.scos3.camera ls files/private_captures/

# Pull from internal storage
adb shell run-as com.scos3.camera cat files/private_captures/Merit1st_20260822_143025123.jpg > C:\Users\YourName\Pictures\photo.jpg
```

### Via File Manager (Android)

1. Open a file manager app (e.g., "Files by Google", "Solid Explorer")
2. Navigate to: `Android/data/com.scos3.camera/files/private_captures/`
3. Note: On Android 11+ (API 30+), direct access to `Android/data/` may be restricted. Use a file manager with SAF support or ADB.

### Viewing Gallery Photos (When Gallery is ON)

Photos are saved to: `/storage/emulated/0/Pictures/Merit1st/`

```bash
# Via ADB
adb shell ls /sdcard/Pictures/Merit1st/

# Pull from Gallery path
adb pull /sdcard/Pictures/Merit1st/ C:\Users\YourName\Merit1st_Gallery\

# Or simply open the Gallery app on your phone — photos appear in the "Merit1st" album
```

### Accessing Email Queue

```bash
# View pending email queue
adb shell cat /sdcard/Android/data/com.scos3.camera/files/email_queue/pending.json

# View compressed email copies
adb shell ls /sdcard/Android/data/com.scos3.camera/files/email_captures/

# Pull compressed email copies
adb pull /sdcard/Android/data/com.scos3.camera/files/email_captures/ C:\Users\YourName\Merit1st_Email\
```

### Accessing Shared Preferences (Settings)

```bash
# View app settings (debug builds only)
adb shell run-as com.scos3.camera cat shared_prefs/scos3_settings.xml

# View secure preferences (encrypted, not human-readable)
adb shell run-as com.scos3.camera cat shared_prefs/scos3_secure.xml
```

### Full Backup via ADB

```bash
# Create a backup of ALL app data
adb shell am backup -f /sdcard/scos3_backup.ab com.scos3.camera

# Pull the backup to computer
adb pull /sdcard/scos3_backup.ab C:\Users\YourName\Backup\
```

### Accessing ALL App Files via ADB

```bash
# List all files in the app's files directory
adb shell ls -R /sdcard/Android/data/com.scos3.camera/

# Full directory tree
adb shell find /sdcard/Android/data/com.scos3.camera/ -type f
```

---

## 14. Troubleshooting

### Camera Permission Not Working

**Symptom:** "Allow camera" button does not respond.
**Fix:**
1. Go to Settings > Apps > Merit1st > Permissions
2. Enable Camera permission manually
3. Force-stop and restart the app

### Overlay Not Appearing

**Symptom:** App opens but no floating overlay is visible.
**Fix:**
1. Go to Settings > Apps > Merit1st > Permissions
2. Enable "Display over other apps" / "Appear on top"
3. Restart the app

### Email Not Sending

**Symptom:** Photos captured but no email received.
**Check:**
1. Is "Email sending" toggled ON in Settings?
2. Are all three Gmail fields filled in (sender, app password, receiver)?
3. Is the App Password correct? (16 characters, no spaces)
4. Is 2-Step Verification enabled on the Gmail account?
5. Is the phone connected to the internet?
6. Tap "Test email" to diagnose

### Auto Capture Not Working

**Symptom:** Tap AUTO but nothing happens.
**Check:**
1. Ensure camera permission is granted
2. Ensure "Display over other apps" is enabled (needed for foreground service)
3. Ensure notification permission is granted (Android 13+)
4. Check that another AUTO/burst capture is not already running

### Volume Buttons Not Responding

**Symptom:** Pressing volume keys does not capture.
**Check:**
1. Enable "Merit1st volume control" in Android Accessibility Settings
2. Ensure the Merit1st overlay is visible on screen (volume keys only work when overlay is active)
3. Volume keys work normally in all other apps

### Black Screen / Camera Failed

**Symptom:** Camera preview shows black.
**Fix:**
1. Check if another app is using the camera (close other camera apps)
2. Check camera permission
3. Try SWITCH to toggle between front/back camera
4. Open Diagnostics to check camera hardware status

### App Crashes on Launch

**Fix:**
1. Clear app data: Settings > Apps > Merit1st > Storage > Clear data
2. Re-grant permissions
3. Reconfigure settings

### Photos Not Visible in Gallery

**Check:**
1. Is "Save photos to Gallery" toggled ON in Settings?
2. Open the Gallery app and look for the "Merit1st" album
3. On some devices, MediaStore may take a few seconds to refresh

---

## 15. Technical Reference

### App Configuration

| Item | Value |
|---|---|
| Package name | `com.scos3.camera` |
| Version name | `0.1.0` |
| Version code | `1` |
| Min SDK | 29 (Android 10) |
| Target SDK | 36 (Android 16) |
| Compile SDK | 36 |
| Java target | JVM 17 |
| Build type | Release (minified + R8) |

### Permissions (AndroidManifest.xml)

| Permission | Purpose |
|---|---|
| `CAMERA` | Camera hardware access |
| `INTERNET` | Gmail SMTP email delivery |
| `ACCESS_NETWORK_STATE` | Network state for email retry |
| `FOREGROUND_SERVICE` | Background auto capture |
| `FOREGROUND_SERVICE_CAMERA` | Camera while-in-use in foreground service |
| `POST_NOTIFICATIONS` | Capture status notifications |
| `VIBRATE` | Capture vibration feedback |
| `WAKE_LOCK` | Keep CPU alive during auto capture |
| `SYSTEM_ALERT_WINDOW` | Floating overlay windows |

### Default Settings

| Setting | Default Value |
|---|---|
| Resolution | AUTO |
| Quality | HIGH (95) |
| Default lens | BACK |
| Auto interval | 4 seconds |
| Email sending | **OFF** |
| Gallery save | **ON** |
| Burst count | 5 photos |
| Burst delay | 350ms |
| Haptic vibration | 65ms |
| Face cooldown | 3000ms |
| Focus timeout | 2000ms |
| Email compress max | 2048px / quality 80 |

### File Paths Summary

| Path | Description |
|---|---|
| `/storage/emulated/0/Pictures/Merit1st/` | Gallery photos |
| `files/private_captures/` | Private storage photos |
| `files/email_captures/` | Compressed email copies |
| `files/email_queue/pending.json` | Pending email queue |
| `shared_prefs/scos3_settings.xml` | App settings |
| `shared_prefs/scos3_secure.xml` | Encrypted Gmail credentials |

### Email Technical Details

| Property | Value |
|---|---|
| SMTP server | smtp.gmail.com |
| Port | 465 (SSL) |
| Auth method | Gmail App Password (16-char) |
| Compress max dimension | 2048px |
| Compress JPEG quality | 80 |
| Initial retry delay | 15 seconds |
| Max retry delay | 5 minutes |
| Retry backoff | Exponential (2x) |

### Key Constants

| Constant | Value | Location |
|---|---|---|
| `BURST_COUNT` | 5 | MainActivity.kt |
| `BURST_DELAY_MS` | 350ms | MainActivity.kt |
| `SHUTTER_TICK_MS` | 65ms | Haptics.kt |
| `FOCUS_TIMEOUT_MS` | 2000ms | CameraController.kt |
| `CAPTURE_COOLDOWN_MS` | 3000ms | FaceDetectionController.kt |
| `FRAME_THROTTLE_MS` | 250ms | FaceDetectionController.kt |
| `MIN_FACE_RATIO` | 0.1 (10%) | FaceDetectionController.kt |
| `MAX_DIMENSION` | 2048px | ImageCompressor.kt |
| `EMAIL_QUALITY` | 80 | ImageCompressor.kt |
| `INITIAL_RETRY_MS` | 15000ms | EmailManager.kt |
| `MAX_RETRY_MS` | 300000ms (5 min) | EmailManager.kt |

### Camera Architecture

| Component | Role |
|---|---|
| CameraManager | ProcessCameraProvider singleton |
| CameraController | CameraX facade: preview, capture, zoom, lens, focus |
| CameraCapabilities | Camera2 hardware characteristics, ultra-wide detection |
| FaceDetectionController | ML Kit face detection state machine |
| CaptureEngine | Single/burst/interval capture + storage + email |
| Haptics | Shutter vibration feedback |
| OverlayManager | Two-part floating overlay WindowManager |
| SettingsRepository | SharedPreferences persistence |
| SecurePrefs | Android Keystore encrypted credentials |
| EmailManager | Queue coordination, retry, network callback |
| GmailEmailRepository | Gmail SMTP implementation |
| ImageCompressor | JPEG compression for email |
| MediaStoreRepository | Gallery-visible storage |
| PrivateStorageRepository | App-private durable storage |
| CaptureForegroundService | Background camera service |
| VolumeKeyAccessibilityService | Volume key interception |
| VolumeKeyDispatcher | App-level volume key bridge |
