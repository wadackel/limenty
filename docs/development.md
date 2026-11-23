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
tools/emu start               # boot it (LIMENTY_AVD picks another AVD; limenty_fold by default)
tools/emu serial              # the serial of that AVD's emulator, looked up by name
tools/emu fold | unfold       # drive the hinge sensor
tools/emu rotate 0|1|2|3      # force a display rotation (rotate-auto restores)
tools/emu shot out.png        # screenshot
tools/app run                 # assemble + install + launch the debug build
tools/app itest               # instrumentation tests (emulator only, wipes the app's data there)
tools/sshd [--port 2222]      # a test SSH server for the emulator, at 10.0.2.2
tools/app log | logf          # app-emitted structured log lines (once / follow)
tools/app crash               # the platform crash buffer
tools/app dump [dir]          # pull the on-device diagnostics reports
tools/app devices             # what is attached, and which one commands will use
tools/dev wifi                # put the phone on the network so the cable can come out
tools/dev info | shot | fold  # drive the physical device
```

Several devices are usually attached at once — an emulator, the phone over the cable, the phone
over the network — so `tools/app` names one rather than letting adb pick, and says so when the
choice is not obvious. `ANDROID_SERIAL` settles it, and `LIMENTY_AVD=<avd>` settles it by the
emulator's AVD name, which stays the same when serials change with start order; `tools/dev`
always means the phone, and refuses to run when either of them names an emulator. Before
picking, they wait for adb's device list to settle (up to `LIMENTY_ADB_WAIT` seconds, 30 by
default), since a freshly restarted adb server lists devices over its first second.

The AVD is a `7.6in Foldable` profile with a hinge sensor and `hw.keyboard=yes`, so fold,
rotation and physical-key paths can be exercised without the real device. `tools/emu create`
reproduces that configuration from scratch. `tools/emu start` passes `-crash-report-mode never`:
with crash reports left from an earlier run, the emulator otherwise stops at "Showing crashdialog
to get consent" and never boots.

`tools/app itest` installs the app, runs `connectedDebugAndroidTest`, and uninstalls it, so the
app's data on that emulator is gone afterwards; it says so before it starts. Arguments after
`itest` go to Gradle, which is how one class is run while iterating:

```sh
tools/app itest -Pandroid.testInstrumentationRunnerArguments.class=dev.wadackel.limenty.transport.SshOnAndroidTest
```

CI runs the whole suite as well, on every pull request and push to `main`, in the
`instrumentation` job beside `check`. It boots a Gradle-managed emulator (`ci` in
`app/build.gradle.kts`, the same `7.6in Foldable` profile as `tools/emu create`, on an ATD image),
starts `tools/sshd` so `SshOnAndroidTest` connects rather than skips, and keeps the reports when it
fails. It takes about seven minutes, so `tools/app itest` on one class is still the faster loop.

A test belongs in `androidTest` only when it needs something Robolectric does not have: the
Android Keystore, a real IME and `InputConnection`, the platform's gesture detectors timing a long
press, glyphs drawn through the platform's font chain, ART's security providers, or the whole
activity. Compose layout, semantics and touch input, storage, and `org.json` all run in `src/test`
under Robolectric, which is where `./gw test` and `check` reach them in seconds.

### A private adb server

The shared adb server on port 5037 can crash every minute or so (see
[`AGENTS.md`](../AGENTS.md#adbs-server-crashes-on-its-own)), and a run of the whole
instrumentation suite does not survive that. `LIMENTY_ADB_PORT=<port>` moves `tools/*` onto a
server of their own on that port, with mDNS, USB and the emulator port scan switched off.
`tools/emu start` then puts the emulator on a port from 5600 up, which the shared server does not
scan, so the emulator is attached to the private server alone:

```sh
LIMENTY_ADB_PORT=5047 tools/emu start
LIMENTY_ADB_PORT=5047 tools/app itest
LIMENTY_ADB_PORT=5047 tools/emu stop
```

The setting has to be on `tools/emu start` as well as on the commands after it; an emulator that
was started without it is attached to the shared server, and `tools/emu` says so. The phone is
not on the private server, so `tools/dev` finds nothing there. Leave mDNS on for the shared server:
the phone is attached through it.

### A test SSH server

`tools/sshd` runs an SSH server on `127.0.0.1` with password authentication and a real shell in a
PTY, for an emulator to reach as `10.0.2.2` (the emulator's alias for the host's loopback). It
answers to `tester` on port 2222 with a new random password each time it starts, printed on its
first line; `--user`, `--password` and `--port` change them. It runs in the foreground until
Ctrl-C. asyncssh comes from the flake's `sshd` shell.

The shell runs as you, on this machine. Its home and working directory are a temporary directory
that is deleted when the server stops, and it gets none of your environment beyond `PATH` and your
user name, but anything typed into it still runs with your permissions. Anything that reaches this
machine's loopback can try the password, which is why it is not a fixed one.

`SshOnAndroidTest` connects to it when given its address, and skips the end-to-end check without
one. Its password defaults to `testpass`:

```sh
tools/sshd --port 2222 --password testpass &
tools/app itest -Pandroid.testInstrumentationRunnerArguments.class=dev.wadackel.limenty.transport.SshOnAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2
```

`sshPort`, `sshUser` and `sshPassword` are the other arguments, defaulting to 2222, `tester` and
`testpass`. The embedded echo server, `./gw :core:transport:runTestSshServer -PsshPort=2222`,
answers to those credentials too, but listens on every interface rather than loopback alone.

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

### Long names

`LongNameScreenshotsTest` draws the screens that show a name someone typed or a remote sent (a
theme, a key set, a host, a session's title) with names far longer than the room they get, on the
cover and the unfolded display. It is compared the same way, against
`app/src/test/screenshots/long-names/`. These images are a regression check, not pictures for the
README.

```sh
./gw :app:recordRoborazziDebug --tests 'dev.wadackel.limenty.ui.LongNameScreenshotsTest'
```

Before committing re-recorded images, open each one and look for text that is cut without an
ellipsis, runs under another element, or wraps beside a neighbour. Recording a broken layout makes
it the expected one.

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
