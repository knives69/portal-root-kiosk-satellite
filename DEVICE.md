# Reference device — Meta Portal+ (`aloha`)

State of the physical unit this fork is developed and verified against. No serial number,
network address, account or user identifier is recorded here on purpose.

| item | value |
|---|---|
| model | Portal+ |
| device codename | `aloha` |
| Android | 9 |
| build id | **`PKQ1.191202.001`** |
| build number (`ro.build.id`) | **`1041515800015050`** |
| display id | `PKQ1.191202.001.1041515800015050` |
| fingerprint | `Facebook/aloha_prod/aloha:9/PKQ1.191202.001/1041515800015050:user/prod-keys` |
| security patch | 2019-08-01 |
| build type | user / prod-keys |
| kernel | `4.4.153+ #1 SMP PREEMPT Tue Oct 14 11:36:39 PDT 2025` |
| ABI | arm64-v8a |
| SELinux | enforcing |

The exploit target directory for this unit is
[`exploit/targets/portal-aloha-1041515800015050`](exploit/targets/portal-aloha-1041515800015050).

## What this branch adds on top of upstream `main`

Upstream is archived at `2b7fbbe` (*Add archive notice*). This branch is exactly upstream
`main` plus four commits — nothing else:

| commit | subject |
|---|---|
| 1 | `local: PORTAL_NO_REBOOT (3 guards …) y timeouts post-aterrizaje` |
| 2 | `local: autoroot helper — relay al daemon por adbd (SELinux untrusted_app->shell)` |
| 3 | `local: autoroot ejecuta apply.sh con root antes de cerrar el daemon` |
| 4 | `autoroot: cap de presencia por arranque con hijo de credencial installd` |

Commit 4 is the on-device presence FPS cap: a dedicated child in the boot window
(`ll_presence_child`) that clones the installer daemon's credentials (`uid 0` +
`u:r:installd:s0`) and writes the seven `prefs_db` rows of
`/data/user/0/com.facebook.portal.aiservice`. From the `shell` domain that write is denied by
SELinux even at uid 0; `installd` is the identity the host run already used.

Config snapshots from the verified unit live in [`device/`](device/): `apply.sh` (the boot
actions script, now versioned), `env-odd.sh` / `env-even.sh` (the two staged attempt
parities) and `autoroot.conf`.

## Deployed artifacts (verified 2026-11-24)

| artifact | on device | sha256 | size |
|---|---|---|---|
| `preload-portal.so` | `/data/local/tmp/preload-portal.so` | `7af8c04c13e2ce04…` | 340,760 B |
| autoroot helper APK | installed package `dev.portal.autoroothelper` | `4ffd566fff21b7ea…` | 37,207 B |
| root daemon | `/data/local/tmp/portal-root-daemon` (created by the payload) | `fd916ad164752529…` | 14,840 B |
| `apply.sh` | `/data/local/tmp/portal-autoroot/apply.sh` | `0458e2a4fea6836b…` | 7,260 B |
| `run.sh` | `/data/local/tmp/portal-autoroot/run.sh` | `e4a7c8419c3dca4c…` | 2,593 B |
| `env-odd.sh` | `/data/local/tmp/portal-autoroot/env-odd.sh` | `feb955314796c68b…` | 1,519 B |
| `env-even.sh` | `/data/local/tmp/portal-autoroot/env-even.sh` | `15568c52c4fcae47…` | 1,519 B |
| `autoroot.conf` | `/data/local/tmp/portal-autoroot/autoroot.conf` | `bb3adf642bf96242…` | 202 B |

Build the `.so` from this tree (NDK 28.2.13676358, `ANDROID_SDK_ROOT` pointing at the SDK):

```sh
cd exploit
make PORTAL_TARGET=portal-aloha-1041515800015050
sha256sum build/preload-portal-aloha-1041515800015050.so   # 7af8c04c…
```

## Boot flow (no host)

1. The helper is started at boot. It waits for `boot_completed`, flushes the Wi-Fi/adb
   settle window and discovers the per-boot `KASLR` base + `memstart` on device.
2. `run.sh <kaslr> <memstart> <attempt>` sources `env-odd.sh` (odd attempt) or
   `env-even.sh` (even attempt) and `exec`s `/system/bin/true` with `LD_PRELOAD`.
3. The payload lands, spawns the action child (shell context, runs the daemon and
   `apply.sh`) and, when `GHOSTLOCK_PRESENCE_CAP_STAGE` is set, the presence child.
4. The presence child is patched with `installd`'s credential while the write primitive is
   still armed, then released after the CFI cleanup. It writes the cap and logs
   `PORTAL_PRESENCE_FPS_BOOT … ok=1`.
5. The daemon runs `apply.sh` (KSM off, `core_ctl min_cpus = 0`, `cpubw min_freq`, log tags,
   probe) and hands off to Shizuku, then `state: done_boot=<id>`.

If the daemon is up but `shizuku_server` is not, the boot needs the known self-heal
(`libshizuku.so --apk=…` through the daemon socket); see `docs` in the companion dossier.

## Measured behaviour

| state | `aiservice` CPU | analysis frames | AiDirector loop |
|---|---|---|---|
| cap written late in the boot (inert) | 183–193 % | 9 / 45 s | 0 or intermittent |
| cap read at process start (next boot) | **109 % → 75 %** | 9 / 45 s | **0 / 10 s** |

`Device 1` stays open (client `com.facebook.portal.aiservice`), `Device 0` is closed when
nothing requests it, and no kernel panic was observed across the two verification boots.

## Restore on a fresh unit of the same build

```sh
ADB="adb -s <transport>"     # e.g. 192.0.2.10:5555 — not recorded here

$ADB install -r -d build/portal-autoroot-helper.apk        # keeps files/adbkey

$ADB shell 'mkdir -p /data/local/tmp/portal-autoroot'
for f in env-odd.sh env-even.sh autoroot.conf run.sh apply.sh; do
  $ADB push device/$f /data/local/tmp/portal-autoroot/$f
done
$ADB shell 'chmod 0755 /data/local/tmp/portal-autoroot/run.sh /data/local/tmp/portal-autoroot/apply.sh'
$ADB shell 'chmod 0644 /data/local/tmp/portal-autoroot/env-*.sh /data/local/tmp/portal-autoroot/autoroot.conf'

$ADB push exploit/build/preload-portal-aloha-1041515800015050.so /data/local/tmp/preload-portal.so
$ADB shell 'chmod 0755 /data/local/tmp/preload-portal.so'
$ADB shell 'rm -f /data/local/tmp/portal-autoroot.disable /data/local/tmp/portal-autoroot/state'
$ADB reboot
```

`portal-root-daemon` is never pushed by hand: the payload creates it inside the privileged
window (`PORTAL_CUSTOM_PAYLOAD_STARTED … dst=/data/local/tmp/portal-root-daemon`).

## Rollback

```sh
# back to the pre-cap binaries (kept on the unit during the test)
$ADB shell 'cp /data/local/tmp/preload-portal.so.frozen-2b1be550 /data/local/tmp/preload-portal.so'
$ADB shell 'cp /data/local/tmp/portal-autoroot/env-odd.sh.frozen  /data/local/tmp/portal-autoroot/env-odd.sh'
$ADB shell 'cp /data/local/tmp/portal-autoroot/env-even.sh.frozen /data/local/tmp/portal-autoroot/env-even.sh'
$ADB reboot
```

The cap rows already written stay in `prefs_db` until the native restore action
(`GHOSTLOCK_RESTORE_PRESENCE_FPS`) runs in a privileged window.
