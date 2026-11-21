# Security design

What this app holds, where it puts it, and what it deliberately refuses to record. This is a design
document; for reporting a vulnerability see [`SECURITY.md`](../SECURITY.md).

## Threat model

The app is a terminal on a phone that connects to machines its owner controls. The realistic
concerns, in order:

1. **A lost or stolen device.** Someone with the handset should not be able to read stored
   passwords out of it.
2. **A leak through diagnostics.** A crash report or a `logcat` capture is shared far more casually
   than a password is, and both outlive the moment.
3. **A machine-in-the-middle.** The whole point of SSH host keys, and the case where a wrong
   decision is silent.
4. **Hostile remote output.** Whatever the far end writes to the terminal is parsed here, and a
   `cat` of one crafted file is enough to write it. The classic terminal risks apply, and the
   graphics protocol adds a larger surface — see "Remote output" below.

Out of scope for V1: an app lock or biometric gate (one-tap connect is the product's central
promise), and defending against a rooted device with an attacker present.

## Credential storage

Passwords are encrypted with AES-GCM under a key generated in, and never leaving, the Android
Keystore. The ciphertext sits in shared preferences; the key does not, which is what makes the
ciphertext useless on its own.

- A fresh 12-byte IV per encryption, stored alongside the ciphertext. Reusing a nonce under GCM
  would reveal that two hosts share a password.
- `setUserAuthenticationRequired(false)`, deliberately — see the scope note above.
- `android:allowBackup="false"` in the manifest, so nothing reaches a cloud backup where the
  Keystore key would not follow it.
- Deleting a host deletes its credential.

`CredentialStoreTest` asserts the properties rather than the implementation: the stored form
contains neither the password nor its base64, two hosts with the same password produce different
ciphertext, and a tampered payload fails to decrypt rather than returning something wrong.

## Host key verification

A key is compared against the stored one for that host and port, by its full SSH wire encoding —
not by fingerprint alone.

- **Unknown host:** the user is shown the fingerprint and decides. Accepting stores it.
- **Known and matching:** the connection proceeds.
- **Known and different:** the connection fails. The user is not asked, because the question is not
  answerable mid-handshake by the person being attacked. There is no "trust anyway" button.
- **Known, but offering only a key type never stored for it:** treated as different, not as unknown.
  Asking would show the first-connection question for a host already trusted, and an impostor would
  need only a different key type to get it. A server that adds a key type keeps working, because the
  client asks for the trusted type first (an RSA key by its `rsa-sha2-*` names as well as
  `ssh-rsa`); one that drops the trusted type has to be deleted and added again.

A stored key belongs to the host and port rather than to a profile. Deleting a profile forgets it
only once no other profile connects to that host and port; forgetting it while another still does
would put the next connection through the unknown-host question again, for a key already checked.

A host found on the network through Bonjour (`_ssh._tcp`) is saved by its service name, and its key
is stored under that name, not under the address the connection dialled. The address is whatever
DHCP gave the machine today and is looked up again at every connection; keying trust on it would
ask about the same key each time the address changed, and would put a different machine that
announced the same name to the same question as a new one. Keyed on the name, a machine announcing
another's name is refused as a changed key, whatever type of key it offers. The first trust of a
LAN host is still a question the user answers, so it is worth comparing the fingerprint on a
network you trust. A LAN host that cannot be found is not dialled at all, so its service name is
never sent to the network's DNS. Discovery logs how many services it sees and the error codes it
meets, never their names or addresses: on a shared network those are other people's machines.

Fingerprints are printed exactly as OpenSSH prints them (`SHA256:` + unpadded base64 of the digest
over the wire encoding), because the user is expected to compare them against `ssh-keygen -lf`
output. `HostKeyDescriptionTest` measures this against checked-in keys and their real `ssh-keygen`
output, so the format is verified against OpenSSH rather than against itself.

The handshake deliberately allows a long deadline while that decision is on screen — a person
reading a fingerprint is slower than a network timeout expects.

## What is never logged

The rule is applied at the point where a value's sensitivity is known, not in the sink:

```kotlin
log.i("connect.start", "host" to config.host, "password" to password.redacted())
// connect.start host=example password=<redacted:8>
```

Filtering inside a sink would mean every new sink has to re-learn the rule. Wrapping at the call
site means a sink cannot leak what it never receives.

Beyond explicit secrets, **text content is never recorded at all**. Keystroke and IME records carry
shapes, lengths and character classes:

```
key.down keyCode=text modifiers=ctrl terminalKey=- codePoint=alnum
commitText len=7 script=hiragana+kanji
```

A key that produces text is reduced to `alnum` / `space` / `other`; a key that cannot spell a
secret — Escape, the arrows, the function row — is logged by name, because those are exactly what a
report needs and none of them can spell a password.

This is not conditional on build type or on whether anyone is attached. The in-memory ring buffer is
written to `files/diagnostics/` on a crash, and `logcat` is retained by the OS and collected into a
bugreport; a rule that only holds while a developer is watching is not a rule.

The consequence for debugging is real and is covered in [`AGENTS.md`](../AGENTS.md): content-level
problems need a side channel, not a louder log.

## Transport

SSHJ with the full BouncyCastle provider installed at position 1, using `DefaultConfig` rather than
`AndroidConfig` — the Android-flavoured configuration offers a key-exchange and host-key set narrow
enough that a current macOS or OpenSSH server will refuse it outright.

Host keys, known-hosts entries and credentials are per host *and port*: the same name on another
port is a different host, because it may well be a different machine.

## Remote output

The terminal treats every byte from the remote as untrusted. Two paths deserve spelling out,
because both are places where a later change could loosen a limit without noticing what it held.

**Replies.** A few sequences oblige the terminal to write something back — cursor position
reports, device attributes, kitty graphics acknowledgements — and that reply ends up in the same
SSH stream as the user's keystrokes. Replies wait in a queue of their own, bounded at 1024 and
dropping the oldest, and are sent only when no typed input is pending. The remote can provoke
replies without limit, but they only accumulate while they are produced faster than the link
carries them away — a remote that has stopped reading, or a tight loop of queries over a slow
link — and interactive use never comes near the bound. A program that floods queries can only
knock out replies owed to its own session. A reply must also never carry text the remote chose:
a graphics error is a fixed phrase plus validated integers (`EINVAL:bad value`, never the
offending key), and no reply contains a newline or an escape. Otherwise a crafted sequence would
type into the user's shell.

**Images.** The kitty graphics receiver bounds everything the remote can size:

- one APC string is at most 8192 bytes, and a longer one is dropped with the transfer it belonged to;
- raw images declare their size up front and are refused above 4096 pixels a side before a byte is
  buffered; compressed ones may grow only to the declared size plus deflate's framing, and a PNG to
  the single-image ceiling;
- bytes in flight are charged against the same 64 MiB quota as stored images as they arrive, with
  unplaced images evicted first, so an unfinished transfer cannot hold a second budget on the side;
- a PNG is charged the larger of its decoded size and its byte length, so a one-pixel header does
  not make megabytes cheap;
- the store holds at most 256 images and a screen at most 2048 placements, and a placement is
  clamped to 4096 cells a side so cursor arithmetic cannot wrap.

**Clipboard.** OSC 52 lets the remote copy text to the device clipboard, which is how nvim and
tmux yank across SSH. Only writes are honoured: a request to read the clipboard back is dropped,
since answering it would hand whatever the user last copied to the far end. A write is applied
only while the terminal screen is resumed and is never replayed later, so a session left running in
the background cannot replace the clipboard while the user is in another app. Split screen is the
exception: both apps stay resumed there, so a remote can still write while the other one is in use.
The cost is the one kitty, WezTerm and Alacritty accept by default and xterm does not: printing a
crafted file can replace what is on the clipboard, and what a compromised host put there can then
be pasted into a session on another. One OSC is at most 512 KiB, and a longer one is dropped whole.

**Links.** A link the remote printed leaves the app only when the user long-presses it and taps
Open, so a host cannot open anything by printing. Only `http` and `https` are recognised, and the
intent carries `CATEGORY_BROWSABLE`, which keeps it to activities that declared they may be opened
from a link. The address is read from the cells under the selection when Open is tapped, and only
from cells the screen actually draws: a concealed cell (SGR 8), a cell under an image, and a
scrollback column past the edge of a narrower screen are never part of a link. A backslash ends
one, since a browser reads it as `/`. What this cannot rule out is text coloured to nearly match
the selection highlight; the browser's address bar is the last check. The log records the
address's length only.

Logs record ids, sizes and error codes, never payloads or the remote's own characters.
