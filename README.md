# RokidR08Wake

***English** · [日本語](README.ja.md)*

A tiny native daemon (running as the `shell` uid) that **wakes Rokid Glasses from sleep with a single tap** of the R08 smart ring. It also suppresses the **Apple Music auto-play glitch** on a paired iPhone.

Ring navigation itself is handled by [Anezium/R08-Access-Bridge](https://github.com/Anezium/R08-Access-Bridge) (ring → glasses direct BLE). This tool only fills the gap it **lacks** — "single-tap wake from a dark screen" — and coexists with R08-Access-Bridge.

> Verified on real hardware: Rokid Glasses (`ro.product.model=RG-glasses`, YodaOS-Sprite / Android 12 line, arm64) + R08 ring ("R08_4B00"), 2026-06-30.

---

## Usage

Prerequisite: R08-Access-Bridge is installed on the glasses and the ring already works as a navigation controller (this tool only adds wake). Pick A or B for your case.

### A. Production: survives reboot (recommended)

Use the self-arm app `armapp/` (`com.hacha.rokidr08wake`), which re-arms the glasses on its own. **No companion, no permanent USB, no Wi-Fi needed.**

**One-time setup (once over USB, never again)**

1. Build the daemon: `./build.sh` → `./r08waked` (the app bundles it under `assets`).
2. Keep adb listening on loopback across reboots:
   ```sh
   adb -s <glasses> shell setprop persist.adb.tcp.port 5555
   ```
3. Build `armapp` and install it on the glasses (bundle a trusted adb key and `r08waked` under `assets`; build steps and key extraction are in [Persistence details](#persistence-reboot-support-armapp)). A prebuilt APK is `RokidR08Wake-selfarm-debug.apk`.

**Daily use**

- **After a reboot, just open "R08 Wake" once on the glasses.** The app connects via adb to loopback 127.0.0.1:5555 → gets a `shell` uid shell → deploys and `setsid`-launches `r08waked` → wake is back.
- Once running, it survives adb disconnect and autonomous suspend. You don't need to touch the app again until the next reboot.
- (Fully zero-touch automation via a BootReceiver is **not possible** — this ROM blocks third-party background launch. Opening the app once is the minimal manual step.)

### B. Run it right now, by hand (dev / quick try)

When you don't need reboot survival and just want it running:

```sh
./build.sh          # cross-compile for aarch64 → ./r08waked
./arm-r08wake.sh    # find RG-glasses via adb → push → setsid launch → log & liveness check
```

Launched with `setsid`, so it **survives adb disconnect and autonomous suspend**, but is **gone after a reboot** (then open the app in A, or re-run these two commands).

### Verify

```sh
GL=$(adb devices | awk 'NR>1&&$2=="device"{print $1}' | while read s; do \
  [ "$(adb -s $s shell getprop ro.product.model|tr -d '\r')" = RG-glasses ] && echo $s; done)
adb -s "$GL" shell input keyevent 223          # screen OFF
# tap the ring once → screen turns on / paired device does NOT start playing music
adb -s "$GL" shell cat /data/local/tmp/r08waked.log   # "tap while off -> WAKEUP (media suppressed)"
```

After a long idle the ring-side BLE may have dropped; the first tap can be consumed by reconnection and the screen lights up on the second (the daemon reopens automatically).

### Stop

```sh
adb -s "$GL" shell pkill r08waked
```

---

## How it works (the gist)

- A **single ring tap = the media key `KEY_PLAYPAUSE`**, delivered at the kernel level to `/dev/input/event3` (`"R08_4B00 Consumer Control"`) inside the glasses. **This BLE HID is a kernel wake source**, so a tap wakes the CPU even when the AP is in deep suspend. That means no keepalive, no wakelock — easy on the battery.
- But **while the screen is OFF the Android framework swallows media keys**, so the tap never reaches an app or the Access Bridge and can't wake the device. So a **native daemon `r08waked`** (shell uid) reads the evdev node directly — below the framework — to catch the tap, and fires `input keyevent 224` (`KEYCODE_WAKEUP`) to turn the panel on.
- Left alone, the media key gets forwarded to the paired device and **Apple Music runs wild**, so the daemon **exclusively grabs the device with `EVIOCGRAB` only while the screen is OFF** to block it (and releases it while the screen is ON, so it doesn't interfere with the Access Bridge's normal navigation).

```
   [R08 ring] --BLE HID--> [Rokid Glasses (Android)]
        |                        |
        |  KEY_PLAYPAUSE         +-- /dev/input/event3  (R08_4B00 Consumer Control)
        |  (1 tap)               |        |
        |                        |        +--> r08waked (this tool, shell uid)
        |                        |        +--> Android input framework
        |                        |                 |
        |                        |                 +-- screen ON:  Access Bridge navigates via onKeyEvent
        |                        |                 +-- screen OFF: → media session → (A2DP/AVRCP) → paired device
   [paired iPhone] <------BT-----+                                             ↑ Apple Music runs wild here
```

Everything below is the "why." If you just want to use it, you're done.

---

## How it works (in detail)

### Background: the problem to solve

Rokid Glasses enter a low-power state when the screen turns off (on this ROM, sensor delivery also stops a while after screen-off, as measured). You can use the R08 ring as a "navigation controller for the glasses" via R08-Access-Bridge, but **once the screen goes dark, a ring tap can't bring it back** (Access Bridge has no wake capability).

Goal: **wake the glasses with a single tap, without taking the ring out of your pocket** — and do it without draining electricity.

### 1. How the R08 tap reaches the glasses

On connect, R08-Access-Bridge sets the ring to **`appType 1` (Stable mode)**. `appType` is the R08's (QRing-compatible) **output mode**, and it persists on the ring. In `appType 1` the ring acts as a **HID Consumer Control**, emitting:

| Action | Event | Note |
|---|---|---|
| Single tap | `KEY_PLAYPAUSE` (HID usage `0x0CD`) | what this tool uses as the wake trigger |
| Swipe fwd/back | `KEY_NEXTSONG` / `KEY_PREVIOUSSONG` | next/prev track keys |

Raw log confirmed on device with `getevent -lt` (one tap):

```
/dev/input/event3: EV_MSC  MSC_SCAN       000c00cd
/dev/input/event3: EV_KEY  KEY_PLAYPAUSE  DOWN
/dev/input/event3: EV_SYN  SYN_REPORT     00000000
/dev/input/event3: EV_MSC  MSC_SCAN       000c00cd
/dev/input/event3: EV_KEY  KEY_PLAYPAUSE  UP
/dev/input/event3: EV_SYN  SYN_REPORT     00000000
```

The device name under `/dev/input` is `"R08_4B00 Consumer Control"`. The index (`event3`) can change on reconnect, so this tool **resolves by name**.

### 2. Why a tap doesn't wake while the screen is OFF

When non-interactive (screen OFF), Android's `PhoneWindowManager.interceptKeyBeforeQueueing` **drops "non-wake keys" before dispatch**. Media keys (`KEY_PLAYPAUSE` etc.) are not wake keys; they only get routed to the media session and **never reach** an app's or Accessibility's `onKeyEvent`.

So R08-Access-Bridge's Accessibility Service can't receive the tap while the screen is OFF, and can't wake. You have to catch the tap **below the framework layer**.

### 3. The key finding: the BLE HID is a kernel wake source

I hypothesized "maybe it isn't reaching us because of the framework gate, but it *is* arriving at the kernel's `/dev/input`?" — and tested it:

- `adb shell getevent -lt` (screen OFF), tap → the event **does arrive** at `/dev/input/event3` (below the framework gate).
- Further, **even after disconnecting adb and leaving it for minutes in autonomous deep suspend, a single tap woke the CPU and the device** (logged with the tap timestamp; screen lighting up confirmed visually).

So the R08 HID is a kernel wake source via the BT controller: **even with the AP fully suspended while idle, it wakes only on tap**. As a result:

- No keepalive pulse (briefly turning the screen on every 60 s) and no permanent `PARTIAL_WAKE_LOCK` are **needed**.
- A blocking read on `getevent` (or, as below, a direct read) is near 0 CPU while idle.
- = very battery-friendly.

### 4. Why `getevent | while read` fails and a native binary is needed

I first tried a shell `getevent | while read ...; do ... done`, but it **didn't work**. The cause: **`getevent`'s stdout is block-buffered when piped/redirected to a file**:

- Direct to a TTY (`adb shell getevent ...`) it is line-buffered = you see it live.
- Piped/redirected, it is fully buffered = nothing flows to the `while` until dozens of taps have accumulated (on device, 5 and even 60 taps didn't come through).

You can't force a flush from the shell (Android has no `stdbuf` etc.). So I made a **native binary that reads evdev directly with `read(struct input_event)`**. `read()` is a syscall, so buffering and pipes are irrelevant. An `input_event` is 24 bytes on 64-bit (`timeval` 16 + type 2 + code 2 + value 4).

### 5. How the wake is fired

On detecting a tap, `system("input keyevent 224")` (`KEYCODE_WAKEUP`) lights up the panel. Both `input` and reading `/dev/input` **require the `shell` uid**, which a plain app can't do on its own (that's why this is a native daemon running as `shell`, not an Accessibility app like R08-Access-Bridge).

### 6. The paired iPhone's Apple Music glitch and `EVIOCGRAB` suppression

With the glasses BT-connected to an iPhone etc., a symptom appeared: **Apple Music plays every time a tap wakes the device**. Cause:

- The glasses act as an A2DP/AVRCP **media controller** to the iPhone.
- While the screen is OFF, `KEY_PLAYPAUSE` (which nothing on the app side consumes) is **forwarded to the iPhone as an AVRCP PLAY** via the glasses' media session → Apple Music plays.

The fix is **`ioctl(fd, EVIOCGRAB, 1)`**: the calling fd **exclusively grabs** the device, and no other reader (including Android's EventHub/InputReader) receives events. So while grabbed, media keys reach the framework = media session = AVRCP **not at all**. Confirmed on device: `EVIOCGRAB ret=0` (succeeds as shell uid) and a tap does **not** play Apple Music.

But a grab "monopolizes all events from that device," so **grabbing permanently also kills R08-Access-Bridge's normal navigation (taps while the screen is ON)**. So I made it conditional: **grab only while the screen is OFF, release while ON** (state machine below).

### 7. Double-tap leak and "hold the grab until the screen is confirmed on"

The first version "released the grab **immediately** after a tap woke the device." But with a double tap:

1. First tap → `keyevent 224` → release immediately.
2. But the screen hasn't finished lighting up — now it's **ungrabbed while still screen-OFF**.
3. Subsequent taps aren't grabbed, leak to the media path, and Apple Music plays.

Fix: **release the grab only when `screen_on()` confirms the screen actually lit up after `keyevent 224`**. If it didn't light up, keep the grab = guaranteeing "however many taps while the screen is OFF, always blocked." This eliminated the double-tap leak (confirmed on device).

### The daemon's behavior (state machine)

```
                    ┌──────────────────────── OPEN (screen ON, grab released) ─────────────────────┐
                    │  drain(own fd) → poll(2s)                                                     │
   start/reconnect  │  poll timeout AND screen_on()==false ─▶ grab + drain ─▶ GRABBED              │
   (initial grab    │  (taps are handled by Access Bridge)                                          │
    from screen)    └────────────────────────────────▲──────────────────────────────────────────────┘
                                                      │ release once screen_on() is confirmed
                    ┌───────────────────── GRABBED (screen OFF, exclusively held) ─────────────────┐
                    │  blocking read (may suspend)                                                  │
                    │  KEY_PLAYPAUSE down ─▶ keyevent 224                                           │
                    │     └ wait_screen_on(1500ms): lit → release (to OPEN) / not lit → keep         │
                    │  (grabbed, so nothing leaks to the iPhone)                                     │
                    └───────────────────────────────────────────────────────────────────────────────┘

   read errors (ring BLE dropped) ─▶ close → sleep 1s → reopen (re-decide grab from screen state)
```

Points:
- **While GRABBED it's a blocking read**, so idle power is near zero. The wake comes from the BT IRQ waking the AP.
- **Only while OPEN does it poll `dumpsys power` at ~2s intervals** (only when active = screen ON; the OFF→ON transition is handled by `wait_screen_on` after wake).
- Release happens only "after confirming the screen lit up" = **there is no moment where it becomes ungrabbed while the screen is OFF** = no leak.

### Code layout (`r08waked.c`)

| Function | Role |
|---|---|
| `open_ring()` | Scans `/dev/input/event*` and opens the device whose name contains `R08` + `Consumer` with `O_RDWR` (falls back to `O_RDONLY`). Index-independent |
| `screen_on()` | Reads `mWakefulness=Awake` from `dumpsys power` to decide screen ON/OFF |
| `wait_screen_on(ms)` | After `keyevent 224`, polls every 150ms until the screen actually lights up (up to `ms`) |
| `set_grab(fd,on)` | `ioctl(fd, EVIOCGRAB, …)` |
| `drain(fd)` | Sets `O_NONBLOCK` and discards buffered events (leftovers around grab, the 2nd hit of a double tap, etc.) |
| `main()` | The state machine above. Reopens on device disconnect |

Constants: `POLL_MS=2000` (screen poll interval while OPEN), `WAKE_WAIT_MS=1500` (upper bound for the light-up wait after wake).

### What the build does (`build.sh`)

Cross-compiles for `aarch64-linux-android` using the NDK under `$ANDROID_SDK_ROOT/ndk`, producing `./r08waked`. It auto-detects the NDK clang (picking the lowest API-level `aarch64-linux-android*-clang` for compatibility). The output is a bionic dynamically-linked arm64 PIE (`interpreter /system/bin/linker64`).

---

## Persistence (reboot support): armapp

A `setsid` launch survives adb disconnect and autonomous suspend, but is **gone after a reboot** (non-root Android can't auto-start a `shell` uid process at boot). `armapp/` (`com.hacha.rokidr08wake`) rebuilds it **from the glasses alone, with no companion and no permanent USB**. For usage, see [A. Production](#a-production-survives-reboot-recommended) above. This section covers the mechanism and build details.

Mechanism (verified on device 2026-06-30):
- Set **`persist.adb.tcp.port=5555`** once from the shell → **after a reboot, adbd keeps listening on `*:5555` (including loopback 127.0.0.1:5555)** (settable from the shell, survives reboot, confirmed on device).
- The app **embeds a trusted adb key** (reusing the companion's already-paired "R08 Companion" key from `adb_keys` = kadb's `adbkey.pem`) and **connects via kadb to 127.0.0.1:5555 over loopback** → adbd hands it a `shell` uid shell → there it deploys `r08waked` and `setsid`-launches it. No Wi-Fi, no external host.
- Arms automatically on app launch (`MainActivity.onCreate`), or via the button.

### Full automation (BootReceiver) is blocked by the ROM (not possible)

Receiving `RECEIVE_BOOT_COMPLETED` is denied to third parties by this Rokid ROM with **`Background execution Third-Party APP not allowed`** (not liftable via `deviceidle whitelist` / `appops RUN_ANY_IN_BACKGROUND` — confirmed on device). So **zero-touch auto-arm is not possible**; "open the app once" is required (an acceptable one-step manual action).

### One-time setup (once over USB, never again)
1. `adb -s <glasses> shell setprop persist.adb.tcp.port 5555` (survives reboot / re-set if it's gone)
2. Bundle a trusted adb key into the app (`armapp/app/src/main/assets/adbkey.pem` — the companion's `files/kadb/adbkey.pem` extracted via `run-as`)
3. Build `armapp`, install it on the glasses, and open it once

Build (`armapp/`): kadb is Java 21 bytecode, so **JDK 21** is required. The repo has no unix `gradlew`, so invoke the wrapper jar directly (if your machine's `~/.gradle/gradle.properties` points at JDK 17, override with `-Dorg.gradle.java.home`):

```sh
cd RokidApps/RokidR08Wake/armapp
JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JDK21 (bundled with Android Studio)
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
"$JBR/bin/java" -Dorg.gradle.java.home="$JBR" \
  -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain \
  :app:assembleDebug --no-daemon --console=plain
# -> app/build/outputs/apk/debug/app-debug.apk
```

Bundled artifacts (gitignored; regenerate on another machine): `app/src/main/assets/r08waked` (output of `../build.sh`) and `app/src/main/assets/adbkey.pem` (the trusted adb key). APK: `RokidR08Wake/RokidR08Wake-selfarm-debug.apk`.

### Security trade-offs (accepted)
- adbd permanently listens on `*:5555` on the LAN (with key authentication).
- The app embeds an adb private key.
- Both assume a personal device / home Wi-Fi.

> The old approach (modify the companion to launch r08waked when the bridge arms) was **dropped** due to unstable ROM self-heal and the companion dependency. The fork (`R08-Access-Bridge/`) is unnecessary if you use Anezium's unmodified version for navigation. Navigation (ring BLE connection) is still provided by R08-Access-Bridge = this wake tool coexists on top of it.

---

## Known limitations

- **The OFF-transition gap**: from just after the screen auto-dims until about `POLL_MS` (2s), the grab isn't established yet, and a tap in that instant could be forwarded to media. But the screen dims because the user stopped interacting = they aren't tapping in that window, so there's essentially no real harm.
- **During screen-ON navigation**: on this hardware, music didn't play during navigation while the screen was ON (Accessibility appears to consume it). If some setup does play, you'd need to extend to **always-grab + re-inject non-media keys into a virtual "R08"-named device via uinput** (so media keys never flow to the framework at all). Currently it's "suppress only while screen OFF."
- **Swipes are media keys too**: next/prev are also media keys, so they're likewise blocked by the grab while the screen is OFF (only tap is used for wake).

---

## Related

- [Anezium/R08-Access-Bridge](https://github.com/Anezium/R08-Access-Bridge) — OSS that turns the R08 ring into glasses navigation. The ring connects **directly over BLE to the glasses** (not via a phone). This tool complements its missing wake.
- `appType` is an output mode that persists on the R08 ring side. Changing it via `AppType probe` etc. alters tap/double-tap behavior, so if things get weird, re-select **Ring modes → Stable mode** in R08-Access-Bridge (re-send `appType 1`).
