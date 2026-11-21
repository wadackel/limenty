# Architecture

Why the code is split the way it is. The module table and the commands are in
[`development.md`](development.md); this file is the reasoning behind them.

## Dependency direction

```
:core:common ──────────────┐
   ↑         ↑        ↑    │  (logging, shared primitives — depends on nothing)
:core:terminal  :core:transport
   ↑     ↑              ↑
:core:input   :core:session
   ↑                    ↑
   └────── :app ────────┘
```

Every `:core:*` module is a plain Kotlin JVM library. None of them applies the Android plugin, so
none of them *can* reach an Android API — the classes are not on their compile classpath at all.
This is enforced by the build rather than by review, which is the point.

Two things follow:

- The terminal emulator, the IME edit buffer, the key encoder and the SSH transport are all
  testable without an emulator. Most of this repository's tests are ordinary JVM tests that run in
  seconds.
- An Android concern cannot leak downward by accident. When a change wants
  `android.view.KeyEvent` inside `:core:input`, the compiler refuses, and the refusal is correct:
  the mapping from a platform key event to this app's `TerminalKey` belongs in `:app`.

## The input layer is a first-class component

IME composition state and physical key events are not flattened into a byte array early. They
are different in kind: a physical `Ctrl-C` must interrupt immediately, while a
key pressed during a Japanese conversion belongs to the conversion and must produce nothing.

So the input path has an explicit arbiter between the two sources:

```
Physical KeyEvent ─┐
                   ├→ InputArbiter → TerminalInputEvent → encoder → bytes
Gboard / IME ──────┘        ↑
                     ImeEditBuffer (the composing region, and nothing else)
```

`InputArbiter.onKey` returns a `KeyDecision` — what to emit, whether the key was consumed, and the
reason — rather than writing bytes. That reason is what makes an input bug legible in the log
(`reason=CompositionCommitted`, `reason=CompositionInterrupted`), and it is what the tests assert
against.

### The edit buffer holds only the composing region

`ImeEditBuffer` deliberately does not model the document. Committed text is handed to the terminal
and forgotten. An IME therefore cannot talk the app into re-sending or deleting something the
remote has already consumed, and `getTextBeforeCursor` can only ever return the preedit.

The cost is that the app cannot answer questions about text it has already sent — which is the
correct trade for a terminal, where the remote owns the document.

### Zero bytes while composing

Nothing is written to the transport until the user confirms. This is covered by tests that assert
on the exact byte count produced by a full conversion (`ZeroBytePreeditTest`).

One consequence is subtle enough to be worth stating: because the preedit is never on the wire,
`finishComposingText` — which for a normal editor means "the text you already displayed is now
final" — has to *send* that text here, not drop it. Getting this backwards silently ate the
leading consonant of every syllable.

## The terminal core is transport-agnostic

`:core:terminal` knows about bytes and a window size. It does not know that SSH exists:

```kotlin
interface TerminalBackend {
    fun write(bytes: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}
```

`:core:transport` implements the other side of that boundary (`TerminalTransport`), and
`:core:session` joins them. This is what lets the emulator be exercised at
20+ MB/s in a JVM test with no network, and what would let a local shell or a serial port be added
without touching the emulator.

## A session is not a screen, and not a connection

Navigating away from the terminal must not end the connection. Three lifetimes are therefore
distinct:

| | Owned by | Lives as long as |
|---|---|---|
| Terminal screen | Compose navigation | the user is looking at it |
| `TerminalSession` | `ConnectionManager` | the host stays open |
| SSH connection | `SshTransport` | the remote and the network allow |

`ConnectionManager` holds a list of sessions and an active id, so the internal model is already
multi-session even though the V1 UI shows one at a time. Tapping a host that already has
a session reuses it, with its scrollback and its connection intact.

## Where the viewport size comes from

The screen is the source of truth for the terminal's size, not the other way round. A session is
created at a default size, and the view corrects it as soon as it is laid out — the same path a
fold, a rotation or a keyboard opening takes later.

Two details in that path exist because of specific failures:

- **The remote is told once the size settles.** A window animation produces a resize per frame, and
  forwarding each one makes tmux and nvim redraw the whole screen a dozen times for one keyboard
  opening. `TerminalSession.resize` debounces before calling the transport.
- **The scroll offset absorbs the change.** The offset is measured from the live bottom, so a resize
  moves the screen's top edge under a user who is scrolled back. Both directions are corrected —
  a shrink pushes rows into scrollback, a taller viewport pulls them back out.

The cell's pixel size travels the same path. The view measures it, `TerminalSession.resize` hands
it to the emulator and puts `columns × cellWidth` on the pty window-change, and the emulator answers
`CSI 14 t` / `CSI 16 t` from the same integers — so a program that divides the pty's pixel size by
its column count, as every image-drawing client does, arrives at the cell the renderer actually
uses. A font-size change keeps the grid but not the cell, which is why the view compares both
before deciding nothing changed, and why a session sends one window-change after connecting even
when the grid still matches: the pty was requested before anything had been measured.

## Rendering

The renderer draws ASCII runs in batches and everything else cell by cell, because a monospace
grid and a proportional CJK fallback disagree about advance width.

Glyphs are never distorted along one axis to fill their cell. At the default typography a kanji
advances 37px into a 46px pair of cells and a Nerd Font icon advances 39px into a 23px cell;
stretching each to fit made both read as flattened. A glyph narrower than its cells is centred, and
only one that overflows is scaled — on both axes. `GlyphFitTest` records those advances on a device
so a font change that invalidates them is visible.

A scaled glyph shrinks about the middle of its cell, not about the baseline. Nerd Font icons come
from a symbols face whose line is centred on the em, and shrinking one toward the baseline dropped
it a fifth of an em below the text beside it. `GlyphPlacementTest` checks where the icon lands.

The Powerline separators U+E0B0–E0B7 are not glyphs at all: `PowerlineGlyphs` draws them as paths
over the whole cell, the way WezTerm does. A separator has to meet the coloured segments on either
side edge to edge, and no font glyph does — the text face's arrows stop short of a cell taller than
its line, and the rounded ones exist only in the symbols face, where scaling leaves them under half
the height of their segment. The flat side is pushed out to the pixel boundary, because the
neighbouring background is filled without smoothing and a fractional edge left a seam.

Ligatures are off (`calt`, `liga`). One glyph spanning `->` hides the characters the remote wrote
and puts a cursor on either cell over half an arrow.

### Images

The kitty graphics protocol is split along the module boundary. `:core:terminal` parses the APC
commands, keeps the transmitted bytes in a `GraphicsStore` and records where each image was placed;
`:app` turns those bytes into a `Bitmap` when it first draws them and keeps the decoded copy keyed
by the image's serial. The core module cannot see `android.graphics`, and that is the reason the
store holds PNG or RGBA bytes rather than pixels the renderer could use directly.

A placement is not stored in the cells it covers. Rows are recycled as they scroll off (the row
object that leaves the top comes back as the blank line at the bottom), so anything attached to a
row would come back with it. Placements live in a list beside the rows instead, addressed in the
same line space the renderer and selection use — zero is the top of the screen, negative numbers
reach into scrollback — and the buffer moves them as it scrolls, exactly as it moves the lines. An
image placed beside a line in the main screen follows it into scrollback; on the alternate screen,
which has none, it is gone once it leaves the top.

Only the direct placement mode is implemented: what eda.nvim, `kitten icat`, chafa and timg send.
Unicode placeholders, animation and file-based transmission are not, and a command asking for them
is refused with `EINVAL` rather than drawn somewhere it was not wanted.
