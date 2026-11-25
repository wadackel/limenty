# The chrome's visual language

Everything that is not the terminal — the host list, settings, forms, dialogs, the key bar and the
overlays on the terminal — follows the rules below. The tokens live in
`app/src/main/kotlin/dev/wadackel/limenty/ui/theme/`; this document says when each one is used and why
the obvious alternative was not.

## Premises

- **Always dark**, whatever the Android setting. The terminal theme is an explicit choice, and a light
  chrome around a dark terminal would disagree with it.
- **Fixed, not derived from the terminal theme.** Switching the terminal theme should change the
  terminal and nothing else, or it stops being clear what changed.
- **Darker than every preset's background.** `Bg` (`#0C0C0F`) is below `#011627` (Night Owl), the
  darkest theme shipped, so the terminal reads as the front of the app. A custom theme may be lighter, or darker
  than the chrome; the chrome keeps its colours either way.
- **One accent**, the violet of the launcher mark (`#A99BEA`).

## Depth by lightness

There are no borders. A surface that is nearer is lighter:

| Role | Value | Used for |
|---|---|---|
| `Bg` | `#0C0C0F` | the screen |
| `Surface` | `#17161B` | a card, a settings group, the detail pane on the unfolded display, the key bar |
| `Surface2` | `#1E1D22` | a surface placed on a surface (a group inside the detail pane), a pinned key, a field |
| `Surface3` | `#25242A` | the nearest thing: a dialog, a key that scrolls, a key group's popup |
| `Surface4` | `#302F36` | a key in a key group's popup |
| `Surface5` | `#3E3C46` | a key group's slot while its popup is open |
| `KeyUnderFinger` | `#5A5866` | the key under a finger sliding across a key group's popup |
| `Line` | `#36343B` | the rare hairline between things on one surface (the key bar's pinned edge) |

The greys lean slightly towards the accent's hue (oklch hue 295, about 60% of the way from neutral).
Pure greys made the violet look pasted on; fully violet greys made everything look tinted.

Borders were the previous design's way of separating things, and they were what made it look
assembled: a hairline around every card, a recessed card for "not connected", a border colour for
each state. Lightness carries the same information with one rule.

## Text

| Role | Value | Used for |
|---|---|---|
| `Text` | `#EAE9EE` | labels, names, values being edited, a key over the terminal (a scrolling key on the key bar, the selection toolbar's actions) |
| `Text2` | `#ADABB4` | current values, descriptions, addresses |
| `Text3` | `#807F88` | the quietest text: an unset value (`None`), a tail domain, a note |

Seven sizes, each a role (`LimentyText`): **Title** 24 (a screen's or a pane's name), **Heading** 18
Medium (a host card's name, a dialog title), **Body** 15 (a row's label), **Label** 14 Medium (anything
pressed), **Secondary** 13 (values, descriptions, chips; **Section** is this size in Medium and the
accent), **Mono** 12 (addresses, versions), **MonoMeta** 11 (a session's meta line). A new piece of
text takes one of these; there were eleven sizes before, and none of them read as a level.

## Colour

| Role | Value | Used for |
|---|---|---|
| `Accent` | `#A99BEA` | anything that can be pressed or is selected, section headings, the one filled button; not a key over the terminal |
| `OnAccent` | `#1E1838` | text on an accent fill |
| `AccentSelection` | accent at 18% | the background of the selected item in a list: behind accent text in the settings index, behind `Text` in the host list, where a host keeps the name its card shows |
| `Ok` | `#73C786` | a live session: the dot before a host's name; a key bar that fits |
| `Warn` / `WarnSurface` | `#ECC16B` / `#312408` | something to read before going on: a changed host key, a notice, a key bar too tight to scroll, a `-dirty` build |
| `Danger` / `DangerSurface` | `#F8818D` / `#3B1B1E` | something that cannot be undone: deleting, trusting a new host key |

The status colours share the accent's lightness and chroma and differ only in hue, so a screen with
the accent and a status colour on it reads as one palette. Pastel versions (Material's tone 80) were
too close to white to tell apart; saturated ones competed with the accent. Deletion used to borrow
the warning colour, which made "read this" and "this cannot be undone" the same signal.

A status colour is used on its own surface (`WarnSurface` behind `Warn` text) or as text on a neutral
surface, never as a fill behind dark text.

## Corners

Decided by how large a surface is (`LimentyShapes`):

| Step | Radius | Used for |
|---|---|---|
| `Floating` | 20 | the detail pane on the unfolded display, a dialog, a terminal overlay |
| `Surface` | 12 | a card, a group, a picker option, a selected item, a key, a button |
| `Small` | 6 | a chip, a session preview inside a card |

A switch keeps its round track; it is the only fully rounded thing. One radius for everything made
the floating panes read as cards, and fully rounded buttons disagreed with the list items beside them.

## Spacing

On a 4dp grid. The inside of a surface follows its corner step: 24 inside a floating surface, 16
inside a surface, 12 inside a small one. Screens keep 16 at each side on the folded display and 20
on the unfolded one. Anything pressed is at least 44 tall and a row is 56. The key bar draws less
than it answers to: its keys are drawn 32 tall in a 44 row, and their hit area takes in the gaps.

Two targets answer to less than 44, each for a reason: the theme editor's sample, whose spans are a
line tall because it shows a terminal and every colour in it is also a 44-tall swatch, and those
swatches on the unfolded display, 38 wide where eight share a side panel that stops at 400 to leave
the sample room. `PressTargetSizeTest` measures the screens the JVM tests draw, every settings
section and the theme editor, across as well as down, and lets only these two through.

## Buttons

- **At most one filled button on a screen.** Filled (accent) creates or commits: `Add host`, a form's
  `Save`. Two filled buttons on one screen make it unclear which is the action.
- **Opening something that exists is not a button.** A host card opens its session, the session
  preview on the unfolded display resumes it, and an accent `Resume ›` says so. A second filled
  `Resume` next to `Add host` was the case the rule above is for.
- **Secondary actions are icons or a menu.** Edit, Disconnect and Delete sit behind `···` on a card
  and as icon buttons in the detail pane.
- **Text-only buttons close or cancel.** Deletion is confirmed in a dialog, in `Danger`.

## Layout per display

The Fold has a 475×751dp cover display and a 933×704dp inner one (landscape). Below 700dp of width a
screen is laid out for the cover; above it, for the inner display.

- **Host list.** Cover: cards in one column, all on `Surface`; a connected host has a dot before its
  name and shows its session preview. Inner: list and detail — the hosts on the left, the selected
  one on the right in a floating pane whose large preview is itself the way back into the session.
  On both, the session's meta line and `Resume ›` sit under the preview, never over it: the preview
  is what is being resumed, and a label on its corner covered part of it. The state is not said
  twice — a chip beside `···` read as part of the menu, and a lighter card repeated the preview.
- **Settings.** Cover: an index (Appearance, Keyboard & key bar, Gestures, Keep screen awake, About),
  each row showing its current value, opening a sub-screen. Inner: the index on the left, the
  selected sub-screen on the right in a floating pane. Fourteen gesture bindings and three pickers on
  one scrolling page were too long to find anything in; Android's settings guidance puts sub-screens
  at fifteen or more settings.
