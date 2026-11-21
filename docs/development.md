# Development

Building, running and diagnosing Limenty. The reasoning behind the module split is in
[`architecture.md`](architecture.md); credential handling and what is never logged are in
[`security.md`](security.md). Rules for an AI agent working in this repository are in
[`AGENTS.md`](../AGENTS.md).

## Toolchain

The JDK and ktlint come from the Nix flake; the Android SDK is the one already installed under
`~/Library/Android/sdk`. Gradle is **not** in the devshell — its version is pinned by
`gradle/wrapper/gradle-wrapper.properties`, and a second copy on `PATH` would invite builds that
silently run a different one.

```sh
nix develop           # interactive shell with JAVA_HOME / ANDROID_HOME / adb / emulator on PATH
./gw <gradle task>    # the Gradle wrapper, wrapped in `nix develop --command`
```

## Modules

| Module | Kind | Responsibility |
|---|---|---|
| `:core:common` | JVM | logging, shared primitives |
| `:core:terminal` | JVM | terminal emulator core, backend boundary, terminal modes |
| `:core:input` | JVM | IME edit buffer, input arbitration, key encoding |
| `:core:transport` | JVM | `TerminalTransport` abstraction and the SSH implementation |
| `:core:session` | JVM | connection/session management |
| `:app` | Android | Compose UI, Android input glue, platform services |

The core modules are plain JVM libraries so their logic is unit-testable without an emulator,
and so a terminal or input concern cannot reach for an Android API by accident — `:core:input`
literally cannot see `android.view.KeyEvent`, which is why key mapping lives in `:app`.

## Development

```sh
./gw test                     # JVM unit tests
tools/emu create              # create the foldable AVD (idempotent)
tools/emu start               # boot it
tools/emu fold | unfold       # drive the hinge sensor
tools/emu rotate 0|1|2|3      # force a display rotation (rotate-auto restores)
tools/emu shot out.png        # screenshot
tools/app run                 # assemble + install + launch the debug build
tools/app itest               # instrumentation tests (emulator only — see AGENTS.md)
tools/app log | logf          # app-emitted structured log lines (once / follow)
tools/app crash               # the platform crash buffer
tools/app dump [dir]          # pull the on-device diagnostics reports
tools/app devices             # what is attached, and which one commands will use
tools/dev wifi                # put the phone on the network so the cable can come out
tools/dev info | shot | fold  # drive the physical device
```

Several devices are usually attached at once — an emulator, the phone over the cable, the phone
over the network — so `tools/app` names one rather than letting adb pick, and says so when the
choice is not obvious. `ANDROID_SERIAL` settles it; `tools/dev` always means the phone. Before
picking, they wait for adb's device list to settle (up to `LIMENTY_ADB_WAIT` seconds, 30 by
default), since a freshly restarted adb server lists devices over its first second.

The AVD is a `7.6in Foldable` profile with a hinge sensor and `hw.keyboard=yes`, so fold,
rotation and physical-key paths can be exercised without the real device. `tools/emu create`
reproduces that configuration from scratch.

## Documentation screenshots

The images in `docs/images/` are rendered by a unit test, `DocsScreenshotsTest`, from the real
screens with fixed sample data, on the JVM through Robolectric's native graphics. No emulator or
SSH server is involved, so they come out the same on every run.

```sh
./gw :app:recordRoborazziDebug --tests 'dev.wadackel.limenty.docs.*'   # rewrite docs/images/
```

Every `./gw test` compares the screens against the committed images (`roborazzi.test.verify` in
`gradle.properties`), and so does CI. A change that alters one of these screens therefore fails
until the images are recorded again; the expected and actual pictures are left in
`app/build/outputs/roborazzi/`. The terminal content is written by `DocsScenes` for whatever
grid the screen lays out, so a font or layout change re-records cleanly instead of wrapping.

## Diagnosing a failure on a real device

Subsystems log through `TermLog.logger(LogTags.X)` and emit `event key=value` lines rather than
prose, so a failure can be reconstructed by grepping for a fixed pattern:

```
14 07:32:40.651 D [main] Ime: commitText len=1 cursor=1
15 07:32:41.402 D [main] Key: key.down keyCode=111 modifiers=none terminalKey=Escape codePoint=0
16 07:32:41.402 D [main] Input: key.arbitrated key=Escape composing=true reason=CompositionCancelled events=0
```

Each record carries a monotonic sequence number and the producing thread, because IME and
rendering bugs are usually disagreements about ordering across threads, and wall-clock
milliseconds collide across a burst of callbacks.

Records also land in an in-memory ring buffer that is written to
`files/diagnostics/` on an uncaught exception, or on demand from the debug harness. `tools/app
dump` retrieves those reports; each one carries the build and device fingerprint, the stack
trace, and the event sequence that led up to the failure — the log that survives a crash nobody
was attached to.

Secrets are wrapped in `Redacted` at the call site, which is the only place their sensitivity is
known. Text content is never logged: IME and input records carry lengths and code points only,
since a preedit at a remote password prompt is still a secret.

## Debug harness

The debug build ships an extra activity (`app/src/debug`, so it cannot reach a release build):

```sh
adb shell am start -n dev.wadackel.limenty.debug/dev.wadackel.limenty.debugharness.DebugActivity
```

It shows the live preedit, the exact bytes the input layer produced, a running count of bytes
emitted *while composing* — which has to stay at zero — and the input event trace.
