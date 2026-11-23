# Working in this repository as an agent

Rules and judgement calls for an AI agent doing work here. Reference material — commands, log
format, module table — lives in [`docs/development.md`](docs/development.md) and the rest of
[`docs/`](docs/); this file is only what is easy to get wrong.

## Never run instrumentation tests on the physical device

Instrumentation installs the app and uninstalls it when the tests end, and uninstalling wipes its
data — the owner's saved hosts and their credentials with it. On the phone they actually use, that
is a real loss and they have to type a password back in.

```sh
tools/emu start                 # then
tools/app itest                 # refuses anything that is not an emulator
```

`tools/app itest` enforces this, so the mistake is hard to make through the tool. It is still
possible to reach around it by calling Gradle directly — don't. If a check genuinely needs the
physical device (a sensor, a peripheral, a display the emulator has no equivalent for), say so and
ask, rather than spending the owner's saved state on it.

The emulator loses its app data the same way, and `tools/app itest` says so before it starts.
Finish any check that depends on what the app has saved there (a host you added, a theme you
set up) before running it, not after.

`tools/dev` is the phone's tool and refuses when `ANDROID_SERIAL` or `LIMENTY_AVD` names an
emulator, since it would otherwise ignore them and act on the phone. Reach an emulator through
`tools/app` and `tools/emu`.

## Check on the phone when the owner can lend it

The emulator is for iterating and for instrumentation. The phone is where the app is used and
where the emulator has already disagreed with reality — a foldable's second panel, the owner's
own IME, a network that is not the host's — so a feature or a fix is also seen there when the
phone is available. Often it is not: the owner is using it, or is out where it cannot be reached.

```sh
tools/app devices               # is the phone attached at all
tools/dev run                   # build, install over the existing app, launch
# reproduce the change on the device: `adb -s <phone serial> shell input ...` (the serial from
# `tools/app devices`; bare adb refuses with the emulator attached), or type into a session
tools/dev shot                  # then read the image
tools/dev log                   # the event sequence; look for warnings nobody asked for
tools/dev crash                 # should be empty
```

- **Ask before installing on it or driving it.** `tools/dev run` replaces the running app, which
  ends the SSH sessions the owner has open, and `adb shell input` types into whatever is on their
  screen. Ask right before the first of either; a yes covers the rest of that piece of work, and
  the next piece asks again. Reading — `tools/dev shot`, `log`, `crash` — changes nothing on the
  phone and needs no asking.
- **Without the phone, the emulator's check is the check.** When the owner says no, when
  `tools/app devices` does not list the phone, or when nobody is there to answer (an unattended
  run), verify on the emulator and finish the work, merge included. Do not ask the owner to
  reconnect it; they may be away from it. The report says the change was not seen on the phone
  and lists what to try there.
- **`tools/dev run` keeps the owner's data.** It installs with `-r`, which replaces the code and
  leaves the saved hosts alone. If the install is refused — a signature mismatch, a downgrade —
  stop and ask. Uninstalling or `pm clear` would get it through and is exactly the loss the
  instrumentation rule above exists to prevent.
- **What adb cannot observe goes to the owner** with what you measured attached — see "Know what
  you cannot settle" below.
- **Say what was seen.** The report names the device, the commit that was installed, and what the
  screen and the log showed.

## The phone is reachable without the cable, but not the way the platform suggests

`tools/dev wifi` puts it on the network. Run it once with the cable in; adb's USB entry then
disappears, because arming the listener restarts adbd, and what is left is the network one. Even
so, the phone is out of reach some of the time — after a reboot, or wherever the owner has taken
it. That is the ordinary case the section above covers, not a connection to repair before
finishing.

Android's own wireless debugging is a trap here. Its toggle can be on, `adb_wifi_enabled` can read
`1`, and the listener will still be bound to loopback and nothing else — it only starts properly
when the device is on Wi-Fi, and this phone usually is not:

```
LISTEN [::ffff:127.0.0.1]:40491     # the toggle is on. nothing can reach it.
```

So `tools/dev wifi` arms the plain TCP listener instead, which binds everywhere, and connects over
the tailnet address the phone already has. That works on mobile data and from anywhere, at the
cost of two things worth knowing:

- **A reboot clears it.** The listener is gone and the cable is needed once more to arm it again.
- **It is slower.** A debug install is around 13s over the tailnet against 3-4s over the cable, so
  a change-build-look loop is noticeably longer.

If the phone is on the same Wi-Fi as the machine, Android's own pairing flow is the better path —
it survives reboots and the link is local. Check what the device actually has before assuming
either: `adb shell ip -o -f inet addr`.

## adb's server crashes on its own

The adb server from platform-tools 37 aborts by itself: a panic in its mDNS discovery, or losing
a start-up race when several adb clients launch it at once. macOS keeps the reports in
`~/Library/Logs/DiagnosticReports/adb-*.ips`. Every device drops with it, and the next command
starts a fresh server that lists the emulator, USB and mDNS-found devices again over about a
second, the emulator as `offline` first. A phone attached with `adb connect` (the tailnet path of
`tools/dev wifi`) is not remembered: `adb connect <host>:5555` brings it back.

It shows up as a failure that looks like something else:

```
* daemon not running; starting now at tcp:5037
> com.android.builder.testing.api.DeviceException: No connected devices!
adb: device offline
```

- **Re-run before diagnosing.** A run that failed this way says nothing about the code; it is
  not a test failure and not a reason to change the test.
- **Go through `tools/*`, not bare `adb`.** They wait for the device list to settle before
  picking a device (`LIMENTY_ADB_WAIT`, 30s by default), and they name a device instead of letting
  adb refuse because the phone and the emulator are both attached.
- **Run adb-using commands one at a time.** Parallel ones are what lose the start-up race.
- **Move a long run to a private adb server.** When the shared one keeps falling over, start the
  emulator and run the tests with `LIMENTY_ADB_PORT=5047` set on both (see
  [`docs/development.md`](docs/development.md#a-private-adb-server)). That server has mDNS and USB
  off, so it does not hit the panic and does not see the phone. Do not switch mDNS off for the
  shared server instead: the phone reaches it over mDNS.

## Prefer evidence the code cannot fabricate

Logs describe what the app *believed* it did. When the question is what actually arrived somewhere,
go and look at that instead.

A Japanese IME corruption in this codebase survived two confident, log-derived diagnoses. What
settled it was capturing the bytes at the far end:

```sh
# on the device, in the terminal being tested
cat > /tmp/check.txt        # type the phrase, then C-c
# on the SSH host
diff <(cat /tmp/check.txt) expected.txt
```

The diff showed `特定ｎお` where `特定の` was intended — the leading consonant of each syllable was
being dropped. No amount of log reading had produced that, because the logs recorded the app's own
interpretation of the IME's callbacks, and the app's interpretation was the bug.

Reach for this whenever a hypothesis has failed twice. Two wrong hypotheses mean the model of the
problem is wrong, and more inference on the same evidence will not fix a wrong model.

## Content is never logged, by design

Input and IME records carry lengths, code points and character classes — never the text. A preedit
at a remote password prompt is still a secret, and the in-memory ring buffer is written to disk on
a crash. See [`docs/security.md`](docs/security.md).

The consequence is the section above: content-level debugging needs a side channel, not a louder
log. Do not "temporarily" add content logging to the app to work around it; the temporary line
outlives the session that added it.

## Prove a test catches the bug

A test written after a fix usually passes for the wrong reason. Before believing it:

1. Revert the fix (keep a copy).
2. Re-run the test. It must fail, and fail for the stated reason.
3. Restore the fix. It must pass.

Several changes in this repository's history were only confirmed real this way, and at least one
"fix" turned out to be a test that would have passed either way.

## A UI change re-records the docs screenshots

`./gw test` fails when a screen in the README no longer matches `docs/images/`. That failure is
the point, not noise: re-record with `./gw :app:recordRoborazziDebug --tests
'dev.wadackel.limenty.docs.*'`, open the changed images and check they show what was intended,
and commit them with the change. Do not loosen the comparison or skip the test to get past it;
the tolerance is already set to what macOS and Linux disagree on and no more. See
[`docs/development.md`](docs/development.md#documentation-screenshots).

The same holds for `app/src/test/screenshots/long-names/`, the screens drawn with names longer
than they have room for: re-record with `--tests
'dev.wadackel.limenty.ui.LongNameScreenshotsTest'`, and before committing, look in each image for
text cut without an ellipsis or pushed under its neighbour.

## Know what you cannot settle

Some questions are not resolvable by reading code or running it, and guessing at them wastes the
owner's time twice — once on the wrong answer and once undoing it.

- **Subjective feel.** Whether a selection handle is comfortable under a real thumb, whether an
  animation is too slow. Measure what you can (this handle was 5.8dp across, against Android's
  48dp touch-target guidance) and hand over the judgement with the measurement attached.
- **Hardware you cannot attach.** A physical keyboard, a mouse, DeX, a foldable's cover display.
  Check what is present (`adb shell dumpsys input`) rather than assuming, then ask.
- **Trade-offs that are the owner's to make.** This app advertises
  `IME_FLAG_NO_PERSONALIZED_LEARNING`, which keeps the keyboard from learning what is typed at a
  password prompt — and, as a side effect, keeps copied text out of the keyboard's clipboard
  history. Neither answer is right for everyone. Establish the mechanism, state the cost of each
  side, and let the owner choose. Do not pick the one that makes your current task easier.

## Verification habits that pay off here

- **Baseline first.** Capture the current output, geometry or screenshot before changing anything;
  "it looks right" is not comparable against a memory.
- **Grep the structured log rather than reading it.** Records are `event key=value`, so a question
  like "did the PTY resize more than once?" is a `grep -c`, not a read-through. The monotonic
  sequence number reconstructs ordering across threads when wall-clock milliseconds collide.
- **Check the log after every device interaction**, including successful ones. Warnings that
  nobody is looking for are how a regression ships.
- **Screenshots on a foldable need the right panel.** `tools/dev shot` picks the display whose size
  matches `wm size`, because the default choice is unstable and has returned the powered-off cover
  screen.

## Ship a finished change as a pull request

A finished change goes on its own branch from `main`, into a pull request, and is watched until
CI passes: read `gh pr checks <number>` after the last push, not a local test run. CI has two
checks, `check` and `instrumentation`, and the second runs the whole `androidTest` suite on an
emulator of its own, so a local `tools/app itest` is for iterating, not the last word. Merging is left
to the owner, except in an unattended run, where the work is finished merge included (see "Check on
the phone when the owner can lend it" above).

## Repository conventions

- The `:core:*` modules are plain JVM libraries and must stay that way — see
  [`docs/architecture.md`](docs/architecture.md) for why. If a change wants an Android API in a core
  module, the design is wrong, not the boundary.
- Comments explain why an obvious alternative was rejected. They do not restate what the code does,
  and they do not record the conversation that produced them.
- Tests are named as sentences describing the behaviour, not the method under test.
- The JDK exists only inside the devshell. `java`, `javap` and the like go through
  `nix develop -c`; outside it macOS answers with "Unable to locate a Java Runtime".
