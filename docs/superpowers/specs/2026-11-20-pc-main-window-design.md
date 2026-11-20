# Mjolnir PC main window and device nicknames: design

Status: draft for approval. Date: 2026-11-20. Builds on `2026-11-18-mjolnir-design.md` (rev 4) and the Forge redesign.

## Why
The Windows app has no home. Today everything except the Send window hangs off the tray icon's right-click menu (pair, paired devices, received files, settings, quit), so the app looks unfinished and Settings feels locked away. The owner wants a proper main window with a Settings page, a preview of what is about to be sent, and a tab for the upcoming guest room (separate spec, `2026-11-20-guest-room-design.md`). They also want to give paired devices nicknames so they are easy to tell apart.

## Goals
- One main window, opened from the tray icon (left-click / double-click) and from the Start menu shortcut, with a left sidebar: **Send**, **Guest room**, **Received**, **Devices**, **Settings**, and a relay status line at the bottom.
- **Send tab** with a preview of everything queued.
- **Settings tab** holding every setting (today's five plus three new ones).
- **Device nicknames** on both the PC and the phone.
- The tray menu stays as a set of shortcuts into the same window.

## Non-goals
- No change to how sending, receiving, pairing or the relay works.
- No light theme. No resizable layout beyond a sensible minimum size.
- The guest room's behaviour is specified in its own spec; this one only gives it a tab.

## Anti-goals
- A "dashboard" full of stats or empty placeholders. Every element must do something.
- Making the tray app heavier at idle: the window is created when opened and disposed when closed; the app keeps running in the tray.

## Approaches considered
1. **One main window with sidebar tabs; tray becomes shortcuts (chosen).** Matches the mockups the owner approved. Existing Send, Devices, Pair and Settings windows become tab contents; the pair-request dialog stays a separate always-on-top dialog because it must appear even when the window is closed.
2. Keep the separate windows and add a small launcher window. Cheaper, but keeps the "scattered" feel the owner objected to.
3. Rebuild the PC app as a web UI served locally. Throws away working Compose code for no user gain.

## Design (as approved in the mockups)
Source of truth: design board https://claude.ai/artifact/1icxaRRXueAkghY1UxXpa3, artboards `DesktopSend`, `DesktopGuest`, `DesktopSettings`. Forge tokens and fonts unchanged.

### Window
- 1040×680 default, minimum 900×600. Title "Mjolnir". Closing hides to tray (setting below); *Quit Mjolnir* stays in the tray menu.
- Sidebar 208 px, `#171717`: hammer + `MJOLNIR` wordmark; nav items with stroke icons, active item accent text on surface; the Guest room item shows the room's countdown badge while a room is open; relay status dot + `Relay connected` / `Connecting…` / `Relay not set` at the bottom.
- Tray menu: *Open Mjolnir*, *Send to device…* (opens window on Send), *Send clipboard to {name}*, *Open received files*, *Quit Mjolnir*. The other items move into the window.
- Explorer *Send to* and "drop on the tray" hand-offs open the window on the Send tab with the items added.

### Send tab
- Top: `Send to` heading and one card per paired device (nickname 600, subtitle `{PC|Phone}` + ` · last used`); an open guest room appears as an extra card `Guest` / `Guest room · {mm:ss} left`. Selected card 2 px accent border.
- Middle-left: `Sending · {n} items` with *Add files…* / *Add folder…*. One row per item, 56 px, with checkbox, 44 px thumbnail, name, and a detail line:
  - photo: real thumbnail; `Photo · {size} · {w} × {h}`
  - other files: type badge (extension); `{Type} · {size}` (+ `{n} pages` for PDFs if cheap to read, else omit)
  - link: first letter of the site as the tile; page title when known, else the URL; `Link · {host/path}`
  - folder: folder icon; `Folder · {files} files · {size}`; the row expands to show top-level children with counts and sizes
  - `Remove` on folders and files, as today.
- Right: `Preview` panel (300 px) for the selected row: large thumbnail for images, page title + full URL for links, folder tree for folders, icon + details otherwise; *Open* and *Show in folder* (files/folders), *Open* (links).
- Bottom bar: `{selected} of {n} selected · {total size}` and the full-width-right accent button `Send to {nickname}`. The disabled Send state must read as clearly disabled but legible (muted text on surface with border, not a dimmed accent).
- Paste or drop works anywhere in the window; the dashed strip at the bottom says so.

### Received tab (my addition, approved)
- A list of the last 100 things received on this PC (persisted in app data): links and files/folders, each with sender nickname, time, and *Open* / *Show in folder*. *Open received files folder* button at the top.
- With "Open received links straight away" on (default, today's behaviour), links still open on arrival and also appear here.

### Devices tab
- Paired devices as cards: nickname (or real name), the real device name underneath when a nickname is set, `PC`/`Phone`, *Rename…*, *Unpair*.
- *Pair a device* opens the existing show-a-code / enter-a-code flow inside the tab.

### Settings tab
Four cards in a 2-column grid, then version + *Open-source licences* + *Save*:
- **Connection:** relay address; status line with latency; *Test* (calls `/health`, shows result).
- **This PC:** device name ("This is the name your other devices and guests see.").
- **Receiving:** save folder with *Change…*; *Open received links in my browser straight away* (new, default on).
- **Windows:** start at sign-in; Explorer *Send to*; *Keep running in the tray when I close this window* (new, default on; off = closing the window quits).

### Guest room tab
Added by the guest-room project. This project only leaves room for it in the sidebar (the item is hidden until then), so no empty tab ever ships.

## Device nicknames (PC and phone)
- A nickname is a private label you give a paired device **on the device you're using**. It is stored locally (desktop app data / Android DataStore), keyed by device id, and never sent to the relay or the other device. (There are no accounts, so there is nothing to sync it through.)
- Set via *Rename…* on the Devices tab (PC) and a *Rename* action on each device row (phone). Empty = use the device's own name. Max 40 characters.
- Shown everywhere that device appears on that device: Send cards, phone `Throw to` selector, notifications (`Sent to {nickname}`, `From {nickname}`), Received list, pair/unpair dialogs. Where a nickname is shown on Devices, the real name is shown under it.
- Unpairing deletes the nickname.

## Constraints
- Forge tokens, fonts and radii as in the redesign plan (desktop inputs radius 10, ruling R29).
- Text contrast ≥ 4.5:1; targets ≥ 40 px on PC, 48 dp on phone.
- Existing tests stay green; new pure logic (nickname store, received history, preview metadata) gets unit tests.
- Thumbnails are generated off the UI thread and cached in memory only; a failure falls back to the type badge.

## Left to the builder
- Exact component split, state holders, and how the tray opens/focuses the single window instance.
- Thumbnail sizing/caching details; how link titles are obtained (only from what is already known — the pasted text or a shared title; no network fetch).
- Received history file format.

## Decisions settled after the builder review (designer's calls)
- **One destination at a time**, as today. Default selection is the last-used device; with none, nothing is selected and Send is disabled until a device and at least one item are selected.
- **Links and files still travel separately.** One Send makes a links delivery and/or a files delivery, as the current Send window does, with one combined result message (existing copy).
- **The queue lives in the app, not the window.** It survives closing the window, sent items are removed after a successful send, and unchecked items stay.
- **Settings apply on Save.** Changing the relay keeps the existing "Changing the relay unpairs all devices" confirmation. *Test* checks the address as typed, so you can test before saving.
- **Quitting during transfers** needs no warning: the outbox is already persistent and resumes on the next start.
- **Refactoring is fine.** The Compose layer may be restructured freely; logic tests move with the logic.
- **Nicknames are in scope on Android too.** Every existing message that names a device (`{device}`, `{pc}`, `{name}`) uses the nickname when one is set; the wording is otherwise unchanged.
- **Received tab link titles** come from the delivery's `tabs[].title`, falling back to the URL.

## Ready for spaces
Spaces (`2026-11-20-spaces-design.md`) is built next. The Send tab's device cards and the Devices tab list are built as grouped lists from the start. For now there is one group, `My devices`; its heading stays hidden while it is the only group.
