# Portal Hey Portal — hardware DSP wake-word for Meta Portal

**Hardware (DSP) wake-word detection on Meta Portal / Portal+ (Android 9 / API 28), with a
privileged root daemon and hand-off to Google Assistant.**

This app does **not** run speech recognition on the CPU. It enrolls a wake-word in the device's
**Knowles/Qualcomm audio DSP** through Meta's own `audiomanager` service, and when the DSP fires the
detection callback it launches Google Assistant via `android.intent.action.VOICE_COMMAND`.

Measured idle cost: **0.0 % of one CPU core** (the DSP does the listening), versus ~16.5 % for a
CPU-based offline recogniser such as [portal-okgoogle](https://github.com/knives69/portal-okgoogle).

---

## 1. What it does

```
        ┌──────────────────────────────────────────────┐
        │  Audio DSP (Knowles/Qualcomm)  — always on   │
        │  keyword models: fb-ww-1g/hp, fb-ww-1g/hfb   │
        └───────────────▲──────────────────────────────┘
                        │ detection callback (binder)
        ┌───────────────┴──────────────────────────────┐
        │  com.facebook.IAudioServiceManager (id 149)  │
        └───────────────▲──────────────────────────────┘
                        │ enroll + callback, requires PLATFORM_AUDIO
        ┌───────────────┴──────────────────────────────┐
        │  RootDaemon  (uid 0, `app_process`)          │
        │  TCP 127.0.0.1:45454  ← UI / automation      │
        └───────────────▲──────────────────────────────┘
                        │ STATUS / SET_KEYWORD / STOP …
        ┌───────────────┴──────────────────────────────┐
        │  MainActivity  — dashboard, polls every 2.5 s│
        └──────────────────────────────────────────────┘
```

On detection the daemon launches:

```java
Intent(android.intent.action.VOICE_COMMAND)   // opens Google Assistant
```

with a 2.5 s cooldown so a single utterance cannot fire twice.

## 2. Why root is required

The enrollment call is protected by a **signature** permission of Meta:

```
com.facebook.alohasdk.permission.PLATFORM_AUDIO
```

A sideloaded app **can never** be granted a signature permission. However `uid 0` is exempt from
`checkComponentPermission()`, so the daemon must run as **root**:

```
uid 2000 → ENROLL_ERROR: calling pid … uid 2000 does not have permission …PLATFORM_AUDIO
uid 0    → ENROLLED kw=HEY_PORTAL_FB enrollId=1
```

There is **no way around this** without a platform-signed APK. The daemon therefore runs as root and
should be treated as a privileged component (see §7).

## 3. Supported wake-words

The DSP firmware only ships three keyword models. Anything else is rejected with
`WakeWordManager: getParameterError:invalid wakeword <NAME>`.

| keyword string | model | spoken phrase |
|---|---|---|
| `HEY_PORTAL_FB` | `/vendor/firmware/fb-ww-1g/hp/kwsEngine.json` | “Hey Portal” |
| `HEY_FACEBOOK` | `/vendor/firmware/fb-ww-1g/hfb/kwsEngine.json` | “Hey Facebook” |
| `ALEXA` | (generic) | “Alexa” |

Only **one** keyword can be enrolled at a time — `WakeWordManager::startRecognition` rejects a list
with more than one entry with `Can't enroll more than 1 wakewords`. The daemon auto-falls back from
`HEY_PORTAL_FB` to `HEY_FACEBOOK` if the first enrollment fails.

## 4. Components

| file | role |
|---|---|
| `MainActivity.java` | dashboard: daemon state, enrollment, detection count, last trigger, keyword selector, START/STOP, “TEST ASSISTANT INTENT” |
| `DaemonClient.java` | TCP client for the loopback control protocol |
| `daemon/RootDaemon.java` | the privileged (uid 0) daemon: binds the audio service, enrolls, serves TCP 45454, launches Assistant |
| `binder/AudioServiceHelper.java` | reflective binder to `com.facebook.IAudioServiceManager` (AIDL `com.facebook.alohasdk.IAudioService…`), single-keyword overload, static stubs so GC cannot trigger `binderDied` |
| `BootReceiver.java` | receives `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (start the daemon externally; see §6) |

The app requests **no** `RECORD_AUDIO`: the audio never reaches the app, only the DSP callback.

## 5. Install and run

```sh
adb install -r heyportal.apk

# start the daemon (must be root!)
adb shell 'CLASSPATH=$(pm path dev.portal.heyportal | cut -d: -f2) \
           app_process /system/bin dev.portal.heyportal.daemon.RootDaemon'

# open the dashboard
adb shell am start -n dev.portal.heyportal/.MainActivity

# events
adb shell cat /sdcard/heyportal_events.log
```

Expected healthy state:

```
apply/events log:  [2026-11-27 22:37:24] ENROLLED kw=HEY_PORTAL_FB enrollId=1
logcat:            I HeyPortal_Binder : startRecognitionWithMetadata SUCCESS! enrollId=1
                   I HeyPortal_Daemon : DSP Enrollment SUCCESS! keyword=HEY_PORTAL_FB
dashboard:         ● ACTIVE · LISTENING (HEY_PORTAL_FB)
                   Privileged Daemon : UID 0 (PID …) · Online
                   Wake Enrollment   : Enrolled (ID #1 · HEY_PORTAL_FB)
```

## 6. Starting it at boot (root)

`BootReceiver` cannot start a root process by itself. On a rooted Portal the daemon is normally
started from the boot script that already runs as `uid 0` (see
[portal-root-kiosk-satellite](https://github.com/knives69/portal-root-kiosk-satellite)). Reference
block:

```sh
# ── demonio Hey Portal (uid 0) ────────────────────────────────────────────────
HP_PKG=dev.portal.heyportal
if pm path "$HP_PKG" >/dev/null 2>&1; then
    # kill a previous instance: otherwise an old APK version keeps the port
    for P in /proc/[0-9]*; do
        case "$(tr '\0' ' ' < "$P/cmdline" 2>/dev/null)" in
            *dev.portal.heyportal.daemon.RootDaemon*) kill "$(basename "$P")" 2>/dev/null ;;
        esac
    done
    sleep 1
    HP_APK=$(pm path "$HP_PKG" | sed 's/package://' | tr -d '\r' | head -1)
    setsid env CLASSPATH="$HP_APK" app_process /system/bin \
        dev.portal.heyportal.daemon.RootDaemon </dev/null >/dev/null 2>&1 &
    sleep 5
    # NOTE: the socket is loopback but appears in /proc/net/tcp6 (::ffff:127.0.0.1)
    echo "heyportal_daemon=tcp_$(grep -c ':B18E' /proc/net/tcp) tcp6_$(grep -c ':B18E' /proc/net/tcp6)"
fi
```

Two gotchas that cost us hours:

1. **`setsid`** — without it the daemon dies when the root boot script exits.
2. **`/proc/net/tcp6`** — the listener is `::ffff:127.0.0.1:45454`; checking only `/proc/net/tcp`
   reports “not listening” even when it is.

## 7. Control protocol (loopback only)

TCP `127.0.0.1:45454` (`0xB18E`), one line per request, JSON answers.

| command | response |
|---|---|
| `PING` | `PONG` |
| `STATUS` | `{"ok":true,"pid":…,"uid":…,"enrolled":…,"enrollId":…,"keyword":…,"detections":…,…}` |
| `RE_ENROLL` | re-enrolls the current keyword |
| `SET_KEYWORD:HEY_PORTAL_FB` | stops and enrolls the given keyword |
| `STOP` | stops DSP recognition |
| `TRIGGER` | launches Assistant once (used by “TEST ASSISTANT INTENT”) |
| `SET_AUTO_LAUNCH:true|false` | toggles the automatic `VOICE_COMMAND` on detection |

## 8. Security notes (read this)

* The daemon runs **as root** and lives for the whole boot.
* It binds **loopback only** (`127.0.0.1`), so it is not reachable from the LAN.
* **There is no authentication on the TCP protocol**: any local process that can connect can send
  `SET_KEYWORD`, `TRIGGER`, `STOP`. On a device where every app is trusted this is acceptable;
  otherwise add a shared secret or an unix socket with `SO_PEERCRED` uid verification.
* The app never captures audio; it only receives DSP detections.
* Removing the app or deleting the boot block removes the daemon; `kill <pid>` stops it until the
  next boot.

## 9. Build

```sh
./scripts/build.sh        # aapt2 + d8 + zipalign + apksigner (debug keystore)
```

Requires `android.jar` (API 28/29), `aapt2`, `d8`, `zipalign`, `apksigner`. The script auto-detects
paths under `~/android-sdk`/`deep_carve/bt`; edit the variables at the top if yours differ.
The APK is signed with `~/.android/debug.keystore` (created on first run).

## 10. Troubleshooting

| symptom | cause / fix |
|---|---|
| `does not have permission …PLATFORM_AUDIO` | the daemon is not uid 0 — start it as root |
| `invalid wakeword HEY_PORTAL` | wrong string, use `HEY_PORTAL_FB` (see §3) |
| `Can't enroll more than 1 wakewords` | more than one keyword passed; enroll exactly one |
| dashboard says *DAEMON OFFLINE* but the process exists | check `/proc/net/tcp6` (not `tcp`) for `:B18E`, and that the APK path the daemon uses is the current one |
| detection does nothing | check `SET_AUTO_LAUNCH:true` and that Google Assistant (GSA) is installed and set as the assist app |

Diagnostics:

```sh
adb shell 'grep -h ":B18E" /proc/net/tcp /proc/net/tcp6; tail -n 20 /sdcard/heyportal_events.log'
adb logcat -s HeyPortal_Binder HeyPortal_Daemon WakeWordManager
```

## 11. Verified environment

| item | value |
|---|---|
| device | Meta Portal+ 1st gen (`aloha`), Snapdragon 835, Android 9 / API 28 |
| root | obtained by the CVE-2026-43499 autoroot chain; daemon started from the boot script as uid 0 |
| enrollment | `ENROLLED kw=HEY_PORTAL_FB enrollId=1` (27-11-2026 22:37) |
| idle cost | 0.0 % of one core (daemon), vs ~16.5 % for a CPU-based wake-word service |
| dashboard | `● ACTIVE · LISTENING (HEY_PORTAL_FB)`, daemon `UID 0 · Online` |

## 12. License

MIT — see [LICENSE](LICENSE).

Not affiliated with Meta. “Portal”, “Hey Portal” and the DSP firmware are property of Meta
Platforms. This is an interoperability experiment on hardware you own.
