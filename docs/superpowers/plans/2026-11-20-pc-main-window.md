# PC Main Window and Device Nicknames Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the tray-only Windows app with one Forge-styled main window (Send with preview, Received, Devices, Settings) and add private device nicknames on the PC and the phone.

**Architecture:** Pure logic goes into small, testable units: shared ones (nicknames, destination grouping, relay health) in `:relay-client`, and desktop ones (received history, item preview, the app-level send queue) in `:desktop`. The Compose layer becomes one `MainWindow` with a sidebar and one file per tab. The old Send, Devices, Settings and Pair windows are deleted once their content has moved into tabs. The tray menu shrinks to shortcuts that open the window.

**Tech Stack:** Kotlin 2.2.20, Compose Multiplatform 1.9.0 (desktop), Jetpack Compose (Android), kotlinx.serialization, `JsonFileStore`, Ktor client, JUnit/kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-20-pc-main-window-design.md` (read it with this plan). Mockups: artboards `DesktopSend`, `DesktopSettings` on https://claude.ai/artifact/1icxaRRXueAkghY1UxXpa3.

## Global Constraints
- Forge tokens and fonts only (`desktop/.../ui/ForgeTheme.kt`, `android/.../ui/ForgeTheme.kt`); desktop input radius 10 (ruling R29); text contrast ≥ 4.5:1; targets ≥ 40 px (PC) / 48 dp (phone).
- Window: default 1040×680, minimum 900×600, title `Mjolnir`; sidebar 208 px `#171717`; preview panel 300 px.
- New copy is exactly as quoted in the spec. Strings that name a device use the nickname when set, and the wording is otherwise unchanged.
- Nicknames: at most 40 characters; stored locally and never sent to the relay; deleted on unpair.
- Received history: the last 100 entries, persisted in app data.
- No network fetch for link titles.
- `./gradlew check :android:lintDebug :android:assembleDebug` stays green; new pure logic gets unit tests.
- Destination lists are built from `groupDestinations()` so that spaces can add groups later. With a single group, its heading is hidden.

## Review Focus
1. **Very long nickname or device name:** a nickname of 40 wide characters (CJK or emoji) must ellipsize on the cards, the tray menu and the phone selector, not wrap or overflow. This is tested in M1 (`trimsAndCapsAt40CodePoints`), and the UI uses `maxLines = 1` with `TextOverflow.Ellipsis`.
2. **A file removed or renamed after it was queued:** the preview and thumbnail must fall back to a type badge and the "missing" detail line, and Send must report the existing unreadable message, not crash. Tested in M3 (`previewOfMissingFileFallsBack`).
3. **A corrupt or truncated `received.json` or `nicknames.json`:** the app starts with an empty list instead of failing. `JsonFileStore` already defaults on a parse error; the M1 and M2 tests pin this (`corruptFileReadsAsEmpty`).
4. **A second instance hand-off or Explorer *Send to* while the window is closed:** the window opens on the Send tab with the items added, and an already-open window is focused rather than duplicated. Tested in M4 (`handOffOpensSendTabOnce`, at the controller level).
5. **Settings Save with an invalid relay URL:** Save is refused with the existing invalid-address message and nothing changes. *Test* on an unreachable host reports a failure within 5 s and doesn't freeze the UI. Tested in M5 (`saveRejectsInvalidRelay`) and in M1's `RelayHealth` timeout test.

---

### Task M1: Shared logic: nicknames, destination groups, relay health

**Files:**
- Create: `relay-client/src/main/kotlin/app/mjolnir/client/Nicknames.kt`, `relay-client/src/main/kotlin/app/mjolnir/client/Destinations.kt`, `relay-client/src/main/kotlin/app/mjolnir/client/RelayHealth.kt`
- Test: `relay-client/src/test/kotlin/app/mjolnir/client/NicknamesTest.kt`, `DestinationsTest.kt`, `RelayHealthTest.kt`

**Interfaces:**
- Produces:
  - `class Nicknames(store: JsonFileStore<Map<String, String>>)` with:
    - `fun get(deviceId: String): String?`
    - `fun set(deviceId: String, nickname: String)` (trims; blank removes; caps at 40 code points)
    - `fun remove(deviceId: String)`
    - `fun displayName(peer: Peer): String` (nickname or `peer.name`)
    - `val changes: StateFlow<Map<String, String>>`
  - `data class DestinationGroup(val key: String, val title: String?, val peers: List<Peer>)`
  - `fun groupDestinations(peers: List<Peer>, lastUsedId: String?): List<DestinationGroup>`. It returns one group with key `"mine"`, whose title is `null` while it is the only group and `"My devices"` otherwise. Peers are sorted last-used first, then by name.
  - `suspend fun checkRelay(url: String, timeoutMs: Long = 5_000): RelayCheck`, where `sealed interface RelayCheck { data class Ok(val millis: Long); data class Failed(val message: String) }`. It calls `GET {url}/health` and expects `ok`.

- [ ] **Step 1: Write the failing tests**
  - `NicknamesTest`:
    - `setThenDisplayNameUsesNickname`
    - `blankRemoves`
    - `trimsAndCapsAt40CodePoints` (input: 45 × "漢" gives 40)
    - `removeFallsBackToDeviceName`
    - `corruptFileReadsAsEmpty` (write `{oops` to the file; `get` gives null)
    - `changesEmitsAfterSet`
  - `DestinationsTest`:
    - `singleGroupHasNoTitle`
    - `lastUsedFirstThenByName`
  - `RelayHealthTest`, against a Ktor `testApplication` or MockEngine:
    - `okReturnsMillis`
    - `wrongBodyFails`
    - `timeoutFailsWithin5s` (an engine that delays 10 s, with `timeoutMs = 200`, gives `Failed`)
- [ ] **Step 2: Run** `./gradlew :relay-client:test --tests '*Nicknames*' --tests '*Destinations*' --tests '*RelayHealth*'`. Expected: FAIL (unresolved references).
- [ ] **Step 3: Implement the three files** per the Interfaces above. `checkRelay` uses the existing Ktor client setup from `RelayClient`, through a small factory; reuse it rather than building a second engine configuration. It normalises the URL with the existing `RelayUrls` helper.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(client): nicknames, destination groups, relay health check`

### Task M2: Desktop received history and link auto-open setting

**Files:**
- Create: `desktop/src/main/kotlin/app/mjolnir/desktop/ReceivedHistory.kt`
- Modify: `desktop/src/main/kotlin/app/mjolnir/desktop/DesktopConfig.kt` (add `openLinksOnArrival: Boolean = true`, `keepInTray: Boolean = true`, and their load/save), `desktop/src/main/kotlin/app/mjolnir/desktop/DesktopCatcher.kt` (record every delivery; open links only when `openLinksOnArrival`)
- Test: `desktop/src/test/kotlin/app/mjolnir/desktop/ReceivedHistoryTest.kt`, plus extensions to `DesktopCatcherTest.kt` and `DesktopConfigTest.kt`

**Interfaces:**
- Consumes: `Nicknames.displayName` (M1)
- Produces:
  - `@Serializable data class ReceivedEntry(val id: String, val fromDeviceId: String, val fromName: String, val atEpochMs: Long, val kind: Kind, val title: String, val target: String)`, with `enum class Kind { LINK, FILE, FOLDER }`. `target` is the URL or the saved absolute path; `title` is the tab title or URL for a link, and the file or top-folder name for files.
  - `class ReceivedHistory(store: JsonFileStore<List<ReceivedEntry>>, cap: Int = 100)` with `fun add(entries: List<ReceivedEntry>)` (newest first, trimmed to `cap`), `val entries: StateFlow<List<ReceivedEntry>>`, and `fun clear()`.
  - `DesktopCatcher` gains the constructor params `history: ReceivedHistory`, `openLinks: () -> Boolean`, `displayName: (Peer) -> String`. Its notification uses `displayName(d.from)`.

- [ ] **Step 1: Write the failing tests**
  - `ReceivedHistoryTest`:
    - `newestFirst`
    - `capsAt100`
    - `corruptFileReadsAsEmpty`
    - `survivesReload` (a new instance on the same file sees the entries)
  - `DesktopCatcherTest`:
    - `recordsLinksAndFolders` (a delivery with 2 tabs and a folder `Holiday/a.jpg`, `Holiday/b.jpg` gives 2 LINK entries plus 1 FOLDER entry, title `Holiday`)
    - `doesNotOpenLinksWhenSettingOff` (the TabOpener fake receives nothing)
    - `notificationUsesNickname` (`"Caught 1 tab from Pixel"` becomes `"Caught 1 tab from Sam's phone"`)
  - `DesktopConfigTest`: `newFlagsRoundTrip`, `defaultsAreTrue`
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*ReceivedHistory*' --tests '*DesktopCatcher*' --tests '*DesktopConfig*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** The history file is `received.json` beside the existing config. Store it with `JsonFileStore` and `ListSerializer(ReceivedEntry.serializer())`. Entry ids are `"{deliveryId}:{index}"`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(desktop): received history and link auto-open setting`

### Task M3: Desktop send queue in the app, plus item previews

**Files:**
- Create: `desktop/src/main/kotlin/app/mjolnir/desktop/ItemPreview.kt`
- Modify: `desktop/src/main/kotlin/app/mjolnir/desktop/AppController.kt` (owns the queue), `desktop/src/main/kotlin/app/mjolnir/desktop/SendState.kt` (adds `lastUsedId`, `groups`, `selectedRow`)
- Test: `desktop/src/test/kotlin/app/mjolnir/desktop/ItemPreviewTest.kt`, plus extensions to `SendStateTest.kt` and `AppControllerTest.kt`

**Interfaces:**
- Consumes: `groupDestinations` (M1)
- Produces:
  - `sealed interface ItemPreview`, with these members:
    - `data class Photo(val name: String, val sizeBytes: Long, val width: Int, val height: Int, val path: String)`
    - `data class OtherFile(val name: String, val sizeBytes: Long, val typeLabel: String, val path: String)` (`typeLabel` is the upper-cased extension, or `File`)
    - `data class Folder(val name: String, val files: Int, val sizeBytes: Long, val children: List<FolderChild>, val path: String)`, with `data class FolderChild(val name: String, val isDir: Boolean, val files: Int, val sizeBytes: Long)`
    - `data class Link(val url: String, val title: String?, val hostPath: String)`
    - `data class Missing(val name: String)`
  - `fun previewOf(file: File): ItemPreview` and `fun previewOfLink(url: String, title: String?): ItemPreview.Link`. Image dimensions come from `ImageIO.getImageReaders` reading the header only, never the full image.
  - `fun detailLine(p: ItemPreview): String`, which returns:
    - `Photo · 4.2 MB · 4032 × 3024`
    - `PDF · 180 KB`
    - `Folder · 212 files · 1.4 GB`
    - `Link · en.wikipedia.org/wiki/Mjölnir`
    - `Missing` (for a file that no longer exists)

    Sizes use `Messages.size`.
  - `AppController`:
    - `val send: StateFlow<SendState>`
    - `fun updateSend(change: (SendState) -> SendState)`
    - `fun sendSelected(): Job`, which calls the existing `send(...)`, then removes the sent links and files from the queue and keeps unchecked ones
    - `val openRequests: SharedFlow<Tab>`
    - `fun openWindow(tab: MainTab = MainTab.SEND)`
    - `enum class MainTab { SEND, GUEST, RECEIVED, DEVICES, SETTINGS }` (`GUEST` is unused until the guest-room plan)
  - `SendState`:
    - `val groups: List<DestinationGroup>`, built from `targets` and `lastUsedId`
    - `val selectedRow: String?`, the key of the row shown in the preview: a URL or a top-level path
    - `fun selectRow(key: String): SendState`
    - `val summary: String`, e.g. `3 of 4 selected · 1.41 GB`

- [ ] **Step 1: Write the failing tests**
  - `ItemPreviewTest`:
    - `photoReadsDimensionsFromHeader` (a 1×1 PNG written in the test gives `Photo(width = 1, height = 1)`)
    - `pdfIsOtherFileWithTypeLabel`
    - `folderCountsChildren`
    - `previewOfMissingFileFallsBack`
    - `detailLinesMatchSpec`, with the four example strings above
  - `SendStateTest`: `summaryCountsSelectedAndSize`, `selectRowSetsPreviewKey`
  - `AppControllerTest`:
    - `queueSurvivesWindowClose` (update the queue, call `openWindow` twice; the same state is kept)
    - `sendSelectedKeepsUnchecked`
    - `handOffOpensSendTabOnce` (two IPC hand-offs emit `openRequests` twice with `SEND`, and the queue holds both items)
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*ItemPreview*' --tests '*SendState*' --tests '*AppController*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Move queue ownership from `SendWindow`'s local state into `AppController`. Route the IPC hand-off and the Explorer *Send to* path through `updateSend` plus `openWindow(SEND)`.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(desktop): app-level send queue and item previews`

### Task M4: Main window shell and Send tab

**Files:**
- Create: `desktop/src/main/kotlin/app/mjolnir/desktop/ui/MainWindow.kt` (window, sidebar, tab switch), `desktop/src/main/kotlin/app/mjolnir/desktop/ui/SendTab.kt` (stateless `SendTabContent`), `desktop/src/main/kotlin/app/mjolnir/desktop/ui/Thumbnails.kt` (async thumbnails with an in-memory LRU of 64)
- Modify: `desktop/src/main/kotlin/app/mjolnir/desktop/ui/TrayApp.kt` (tray menu becomes *Open Mjolnir*, *Send to device…*, *Send clipboard to {name}*, *Open received files*, *Quit Mjolnir*; double-clicking the tray icon opens the window), `desktop/src/main/kotlin/app/mjolnir/desktop/ui/ForgeTheme.kt` (adds a `ForgeDisabledButton` style: surface fill, border, muted text)
- Delete: `desktop/src/main/kotlin/app/mjolnir/desktop/ui/SendWindow.kt`, once `SendTabContent` replaces it
- Test: `desktop/src/test/kotlin/app/mjolnir/desktop/ui/` (an existing UI test folder): `SendTabContentTest.kt` using `runComposeUiTest`

**Interfaces:**
- Consumes: `AppController.send`, `updateSend`, `sendSelected`, `openRequests`, `MainTab` (M3); `previewOf`, `detailLine` (M3); `Nicknames.displayName` (M1)
- Produces:
  - `@Composable fun MainWindow(c: AppController, tab: MainTab, onTab: (MainTab) -> Unit, onClose: () -> Unit)`
  - `@Composable fun Sidebar(selected: MainTab, relayStatus: String, guestBadge: String?, onSelect: (MainTab) -> Unit)`. The Guest room item is hidden while `guestBadge == null` and no room feature exists.

- [ ] **Step 1: Write the failing UI tests**, in `SendTabContentTest`:
  - `sendButtonNamesTarget`: the button text is `Send to Samsung Fold 3`
  - `disabledWithNothingSelected`: the button is not enabled, and its text colour is `Forge.muted`
  - `clickingRowShowsPreview`: clicking `IMG_2041.jpg` makes the preview show its detail line
  - `singleGroupHeadingHidden`: no `My devices` text appears
  - `cardsEllipsizeLongNames`: a 40-character nickname renders in a single line
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*SendTabContent*'`. Expected: FAIL.
- [ ] **Step 3: Implement** to the `DesktopSend` mockup and the spec's Send tab section:
  - The `Send to` cards at the top, rendered from the groups.
  - The 56 px rows, with 44 px thumbnails and folder expansion.
  - The preview panel with *Open* / *Show in folder*.
  - The bottom bar with the summary and the accent button.
  - Paste and drop anywhere in the window.
  - Closing the window hides it when `keepInTray`, and quits otherwise.
  - Delete `SendWindow.kt`.
- [ ] **Step 4: Run** `./gradlew :desktop:test :desktop:compileKotlin`. Expected: PASS. Render `MainWindow` on the Send tab headless with `ImageComposeScene` into the scratchpad, and compare it by eye with the `DesktopSend` artboard.
- [ ] **Step 5: Commit** `feat(desktop): main window with Send tab and preview`

### Task M5: Received, Devices and Settings tabs

**Files:**
- Create: `desktop/src/main/kotlin/app/mjolnir/desktop/ui/ReceivedTab.kt`, `DevicesTab.kt` (the content of `DevicesWindow.kt`, plus *Rename…* and the pairing flow from `PairWindow.kt` inline), `SettingsTab.kt`
- Modify: `AppController.kt`: add `nicknames: Nicknames`; `unpair` also removes the nickname; add `fun saveSettings(draft: SettingsDraft): Job`
- Delete: `DevicesWindow.kt`, `SettingsWindow.kt`, `PairWindow.kt`, once their content has moved. The pair-*request* dialog stays a separate always-on-top dialog.
- Test: `desktop/src/test/kotlin/app/mjolnir/desktop/SettingsDraftTest.kt`, plus extensions to `AppControllerTest.kt` and `ui/DevicesTabTest.kt`

**Interfaces:**
- Consumes: `ReceivedHistory`, `ReceivedEntry` (M2); `checkRelay` (M1); `Nicknames` (M1)
- Produces:
  - `data class SettingsDraft(val relayUrl: String, val deviceName: String, val saveDir: String, val openLinksOnArrival: Boolean, val launchAtLogin: Boolean, val sendToMenu: Boolean, val keepInTray: Boolean)`
  - `fun SettingsDraft.validate(): String?`, which returns the existing invalid-relay or empty-name messages, or `null`
  - `fun SettingsDraft.relayChanged(current: DesktopConfig): Boolean`. When it returns true, the UI shows the existing "Changing the relay unpairs all devices" confirmation before calling `saveSettings`.

- [ ] **Step 1: Write the failing tests**
  - `SettingsDraftTest`:
    - `saveRejectsInvalidRelay`
    - `emptyNameRejected`
    - `relayChangedDetectsDifferentHost`
  - `AppControllerTest`:
    - `saveAppliesAllFields`
    - `unpairRemovesNickname`
    - `renameNicknameUpdatesSendCards` (after `nicknames.set`, `send.value.groups` shows the nickname through `displayName`)
  - `DevicesTabTest`: `showsRealNameUnderNickname`
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*SettingsDraft*' --tests '*AppController*' --tests '*DevicesTab*'`. Expected: FAIL.
- [ ] **Step 3: Implement** to the `DesktopSettings` mockup and the spec sections:
  - The four Settings cards. *Test* calls `checkRelay(typedUrl)` off the UI thread and shows `Connected · {n} ms` or the failure message. The bottom row shows the version, an *Open-source licences* link (opens the bundled OFL files' folder) and *Save*.
  - The Received list, with *Open*, *Show in folder* and *Open received files folder*.
  - Devices cards with *Rename…*. The rename dialog is titled `Rename {name}`, with a text field and *Save* / *Cancel*; leaving it empty clears the nickname. Each card also has *Unpair* and *Pair a device*.
- [ ] **Step 4: Run** `./gradlew :desktop:test :desktop:compileKotlin`. Expected: PASS. Render the Settings, Devices and Received tabs headless and compare them by eye with the mockup.
- [ ] **Step 5: Commit** `feat(desktop): Received, Devices and Settings tabs; nicknames`

### Task M6: Android nicknames

**Files:**
- Modify:
  - `android/src/main/kotlin/app/mjolnir/android/MjolnirApp.kt`: create `Nicknames` over `filesDir/nicknames.json`.
  - `android/.../ui/DevicesScreen.kt`: add a *Rename* action per row and a rename dialog with the same copy as on the desktop. When a nickname is set, the row shows the nickname with the real name underneath.
  - `android/.../ui/ThrowViewModel.kt` and `ThrowScreen.kt`: the `Throw to` selector uses display names and is grouped by `groupDestinations`.
  - `android/.../receive/PhoneCatcher.kt` and `Notifications.kt`: use display names in the caught notification.
  - `android/.../send/ThrowWorker.kt`: use display names in the thrown notification.
  - The unpair path in `DevicesScreen`'s view model removes the nickname.
- Test: `android/src/test/kotlin/app/mjolnir/android/ui/ThrowViewModelTest.kt` (extend) and `receive/PhoneCatcherTest.kt` (extend)

**Interfaces:**
- Consumes: `Nicknames`, `groupDestinations` (M1)
- Produces: `ThrowUiState.groups: List<DestinationGroup>`. The `targets` field is kept for existing callers.

- [ ] **Step 1: Write the failing tests**
  - `ThrowViewModelTest`: `targetsShowNickname`, `groupsSingleGroupNoTitle`
  - `PhoneCatcherTest`: `caughtNotificationUsesNickname`
- [ ] **Step 2: Run** `./gradlew :android:testDebugUnitTest --tests '*ThrowViewModel*' --tests '*PhoneCatcher*'`. Expected: FAIL.
- [ ] **Step 3: Implement** per the file list above. Keep the Forge styling. Long names get `maxLines = 1` and `TextOverflow.Ellipsis`.
- [ ] **Step 4: Run** `./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug`. Expected: PASS, with no new lint warnings.
- [ ] **Step 5: Commit** `feat(android): device nicknames`

### Task M7: Docs and checklist

**Files:**
- Modify: `README.md` (describe the main window, the new tray menu and the Settings location; fix the Firebase step order so it comes after the first deploy; replace the stale "Material Symbols 'hardware' glyph" icon-credit line with the hammer icon credit), `docs/manual-verification.md` (add a "Main window" section)

- [ ] **Step 1: Update** the README. Add to the checklist:
  - The tray double-click opens the window.
  - Closing the window hides it, and quits when *Keep running in the tray* is off.
  - Explorer *Send to* opens the Send tab with the items.
  - The preview shows photo dimensions.
  - Renaming a device updates the Send cards, notifications and the phone selector.
  - *Test* with a bad address fails within 5 s.
- [ ] **Step 2: Run** `./gradlew check`. Expected: PASS.
- [ ] **Step 3: Commit** `docs: main window, nicknames; README fixes`
