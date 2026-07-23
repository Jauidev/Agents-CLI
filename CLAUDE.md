# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin, Views + ViewBinding, **no Compose**) that is a terminal client for coding-agent CLIs — **Claude Code, Codex CLI, Gemini CLI** — running inside an embedded Debian Linux userland on the device. It does **not** use Termux. The app ships a full Linux rootfs, runs it via `proot`, serves a shell over `ttyd`, and renders that terminal in a `WebView` pointed at `http://127.0.0.1:<port>`.

Design docs: `docs/DESIGN-embedded-linux.md` (engine) and `docs/DESIGN-M4.md` (CLIs/UX). Note: the top-level `README.md` and `termux/*.sh` describe an **obsolete** earlier architecture that used Termux — ignore them; the app no longer touches Termux.

## Build / run

There is no `gradle` or `java` on PATH and the Gradle wrapper JAR is not committed. Builds use Android Studio's bundled JDK + a cached Gradle distribution:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
GRADLE_BIN=$(ls /c/Users/*/.gradle/wrapper/dists/gradle-*/*/gradle-*/bin/gradle | head -1)
"$GRADLE_BIN" assembleDebug --no-daemon
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Opening the project in Android Studio also works (it regenerates the wrapper on sync). `local.properties` points `sdk.dir` at the Android SDK.

There are **no automated tests**. Verification is done on a physical arm64 device (`abiFilters = arm64-v8a` only — no emulator/x86 build).

### On-device testing (ADB)
- `adb push` needs `export MSYS_NO_PATHCONV=1` with **Windows-style local paths** (`C:/...`) and unix remote paths (`/data/...`).
- Read the app's on-screen state without a screen (taps are blocked, see below): `adb shell uiautomator dump /sdcard/u.xml` then pull + parse the `error_message`/`error_title` TextViews. `adb exec-out screencap -p > x.png` for a screenshot.
- Inspect/repair the guest as the app user: `adb shell run-as com.agentcli.terminal ...`. **`run-as` has NO network and NO storage permission** — never test guest networking or `/sdcard` access via `run-as`; those only work from the real app process. Guest network + `apt`/`npm` work only when driven from the running app.

### HyperOS / Xiaomi gotchas (this test device is a Poco X6 Pro)
- `adb shell input tap` and `pm clear`/`pm grant` are **blocked** (SecurityException) unless "USB debugging (Security settings)" is enabled (needs a Mi account). So button taps and permission grants must be done by hand on the device.
- Android's **phantom process killer** kills long `apt`/`npm`/`proot` children when the app backgrounds. Disable it before long installs: `adb shell device_config put activity_manager max_phantom_processes 2147483647` (resets on reboot).

## Architecture

```
WebView (xterm.js served by ttyd)  ⇄ ws/http 127.0.0.1:<port> ⇄  ttyd  →  proot  →  Debian rootfs  →  CLI (claude/codex/gemini)
        MainActivity                                                LinuxService              LinuxEngine builds all of this
```

Three Kotlin files carry the whole app (package `com.agentcli.terminal`):

- **`LinuxEngine.kt`** (object) — the engine. Extracts the bundled tarballs to `filesDir/{engine,rootfs}`, builds every `proot` invocation, and runs commands in the guest. Everything the guest does flows through `buildGuestCommand()` (proot arg prefix) + `applyGuestEnv()` (loader paths, `LD_LIBRARY_PATH`, `TMPDIR=/tmp`, etc.). `runInGuest`/`runInGuestStreaming` for one-shot commands; `ensureSessionScript()` writes the per-session launcher script into the guest.
- **`LinuxService.kt`** — foreground `Service` that keeps a `ttyd` process alive per port/tab (map `port → Process`), holds a partial wake lock, and can `restart()` a session with a new working directory.
- **`MainActivity.kt`** — single Activity. Hosts the WebView + bottom nav (3 tabs → 3 ports: 7681/7682/7683, defined in `AgentSession.kt`), the special-keys bar, the folder picker, install/error status screen, and the browser-auto-open watcher.

### Non-obvious mechanisms (read these before changing behavior)
- **`targetSdk = 28` is load-bearing and must not be raised.** Only `targetSdk ≤ 28` lets Android execute binaries from the app's writable data dir (`filesDir`), which is how `proot`/`node`/`ttyd` run. Raising it breaks the entire engine (and makes it Play-Store-eligible but non-functional). This is also why `WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage=true` gives full real-path `/sdcard` access.
- **The rootfs is a pre-baked binary asset**, not built by Gradle. `app/src/main/assets/debian-rootfs.tarball` is a ~350 MB gzipped Debian bookworm-slim containing Node 22, `ttyd`, `tmux`, and the three CLIs already `npm i -g`'d. It is produced offline with Docker arm64 emulation via `scratchpad/.../bake-rootfs.sh` + `inject_tmux.py` (`docker export` → gzip). `engine.tar` holds `proot`, its loaders, `libtalloc.so.2`, `libandroid-shmem.so`, and the static `ttyd`. The `.tarball` extension is deliberate: naming it `.gz` makes `aapt` silently decompress/rename the asset. The APK is ~386 MB as a result.
- **`ROOTFS_VERSION`** (in `LinuxEngine`) is written to `filesDir/.provisioned`. Bump it whenever the rootfs/engine assets change — the app re-extracts on mismatch. Re-provision **preserves `/root`** (CLI logins survive updates).
- **Sessions run inside `tmux`** (`tmux new-session -A -s <agent> -c <workdir>`). Each ttyd (re)connection re-attaches to the same tmux session, so reloading the WebView / switching tabs / bouncing to the browser does not restart the CLI. Changing the working directory calls `killTmuxSession()` then `LinuxService.restart()`.
- **Special keys** (Esc/Tab/Ctrl/arrows) are injected by `evaluateJavascript` dispatching synthetic `keydown` events to xterm.js's `.xterm-helper-textarea` (xterm.js doesn't check `isTrusted`).
- **Browser OAuth** works via a fake `xdg-open`/`$BROWSER` in the guest that appends the URL to `/tmp/.agent-open-url`; `MainActivity`'s `FileObserver` fires `ACTION_VIEW`. The OAuth callback reaches the CLI because host and guest share `127.0.0.1`.
- **Project folder picker**: SAF `OpenDocumentTree` → translate `primary:sub` tree URI to `/sdcard/sub`; proot binds `-b /storage/emulated/0:/sdcard`; the CLI's cwd is set via tmux `-c`. Working dir is stored **per agent** in `SharedPreferences("agentterminal")` under `workdir_<agent>`.

### Gotchas when regenerating the rootfs or engine assets
- Debian bookworm has no `ttyd` package → it's shipped as a static binary in `engine.tar` and copied into the guest by `ensureTtyd()`.
- Files packaged into a tar **from Windows lose the executable bit** → `ensureSessionScript()` re-`chmod 0755`s `tmux` defensively. Prefer preserving modes when rebuilding tarballs (`docker export` preserves them; `tar -rf` on Windows may not).
- Under proot, `apt` needs `-o APT::Sandbox::User=root`; postinst scripts need `TMPDIR=/tmp` (already set in `applyGuestEnv`); SELinux forbids hardlinks in app data so proot runs with `--link2symlink` and the tar extractor falls back to copying hardlinks.
