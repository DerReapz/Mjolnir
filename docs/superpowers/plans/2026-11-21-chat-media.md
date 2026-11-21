# Chat Media Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let chat messages carry images, animated GIFs and files. Images and GIFs show inline, files show as file bubbles, and both apps offer Download (and Share… on the phone).

**Architecture:**
- **Upload:** attachments are uploaded with the existing resumable upload API. A new message references them by file id, and the relay stamps those files with `chat_message_id`. That stamp exempts them from the 1-hour and 7-day purges, opens download to chat members and the space admin, and deletes them when their message or chat is deleted.
- **Bubbles:** the sender attaches metadata (kind, size, dimensions) and a small JPEG thumbnail, so bubbles render before the full file downloads.

**Tech Stack:** as the chat core plan; desktop images via Skia (`org.jetbrains.skia.Image`, `Codec` for GIF frames); Android `ImageDecoder` / `AnimatedImageDrawable` (no new dependencies).

**Spec:** `docs/superpowers/specs/2026-11-21-space-chats-design.md`. Runs after `docs/superpowers/plans/2026-11-21-chat-core.md`. Mockups: the `DesktopChats` image, GIF and file bubbles, the ⋯ menu, and the `PhoneChat` long-press sheet.

## Global Constraints
- **Attachment model (protocol):** `Attachment(fileId: String, kind: AttachmentKind, name: String, sizeBytes: Long, mime: String, width: Int? = null, height: Int? = null, durationMs: Long? = null, thumbFileId: String? = null)`. `AttachmentKind` is one of `IMAGE`, `GIF`, `VIDEO`, `FILE`. `ChatMessage` gains `attachments: List<Attachment> = emptyList()`.
- **Message shape:** a message has text, attachments, or both, and at most 20 attachments. Folders are sent as their files with `relativePath` kept, shown as one folder bubble per top folder (`Folder · {n} files · {size}`).
- **Per-file limit:** `MJOLNIR_MAX_CHAT_FILE_MB` defaults to the existing per-file limit. The relay's global storage cap still applies. When storage is full the relay returns 507 `relay_full`, and the apps show the existing "relay is full" copy.
- **Thumbnails:** JPEG, longest side 320 px, quality 80, uploaded as their own files and listed as `thumbFileId`. GIF thumbnails are the first frame.
- **Kind detection:**
  - `GIF` for `image/gif`
  - `IMAGE` for `image/jpeg|png|webp|heic|bmp`
  - `VIDEO` for `video/*`
  - `FILE` for everything else
  - Until the video plan lands, videos are sent and shown as `FILE` bubbles labelled `Video` with Download.
- **Copy:**
  - Menu items: `Download`, `Share…` (phone), `Copy link` (PC; message has a link)
  - Notification media labels: `Photo`, `GIF`, `Video`, `File`
  - Bubble detail line: `{name} · {size}`
  - Download result: `Saved to {folder}` (PC) / `Saved to Downloads` (phone)
- **Deletion:** deleting a message or chat deletes its files and thumbnail blobs right away. The relay never lists or serves them again.
- **Downloads:**
  - Use the existing ranged `GET /v1/files/{id}` with the bearer token, made available to chat members and the space admin.
  - Clients cache the downloaded media in an LRU cache in app data, capped at 512 MB.
  - Download to disk copies from the cache, or downloads first if the file isn't cached.
- **Saved names:** files saved on the PC go to the configured save folder with the existing safe-name and Mark-of-the-Web rules. On the phone they go through `MediaStoreFilePublisher`.

## Review Focus
1. **A huge or corrupt image.** The thumbnail is generated with `ImageIO` subsampling (PC) or `inSampleSize` / `ImageDecoder` target size (phone) with no out-of-memory error. A corrupt image falls back to a FILE bubble. Tested in M2 (`corruptImageBecomesFile`, `hugeImageThumbnailWithin320`).
2. **A message deleted while someone is downloading its file.** The download fails cleanly with `Message deleted` and leaves no partial file. Tested in M1 (`fileGoneAfterMessageDelete`) and M2 (`downloadOfDeletedMessageFails`).
3. **An upload interrupted halfway (app closed or network lost).** The message is not posted until all its uploads finish. The outbox resumes the upload and then posts. Orphaned uploads are purged by the existing 24-hour rule. Tested in M2 (`messagePostedOnlyAfterAllUploads`).
4. **Disk cache pressure.** The 512 MB cap evicts least-recently-used entries and never evicts a file currently being displayed or played. Tested in M2 (`cacheEvictsLruNotPinned`).
5. **A non-member trying to fetch a chat file by id.** They get 404 even with a guessed or reused id. Tested in M1 (`nonMemberCannotDownloadChatFile`).

---

### Task M1: Relay attachments

**Files:**
- Modify:
  - `protocol/.../Models.kt` (`Attachment`, `AttachmentKind`, `ChatMessage.attachments`, `PostMessageRequest(text: String? = null, attachments: List<Attachment> = emptyList())`)
  - `server/.../Store.kt`:
    - `files.chat_message_id`
    - `attachToMessage`
    - `purgeExpired` excludes chat files
    - `fileForDownload` allows chat members and the space admin
    - message and chat delete returns the blob ids to delete
  - `ChatRoutes.kt` (validate attachments)
  - `Main.kt` / `RelayModule.kt` (`MJOLNIR_MAX_CHAT_FILE_MB`)
- Test: `server/src/test/kotlin/app/mjolnir/server/ChatFilesTest.kt`

**Interfaces — Produces:**
- `PostMessageRequest` takes attachments. The relay accepts only files the poster owns, unsent and not yet attached, with each `fileId`/`thumbFileId` used once. Errors: 400 `invalid_files`, 413 `too_large`.
- `ChatMessage.attachments` is returned through sync.

- [ ] **Step 1: Write the failing tests** in `ChatFilesTest`:
  - `attachmentsStampedAndReturnedInSync`
  - `chatFilesSurvivePurges` (fake clock +8 days)
  - `membersAndAdminCanDownload`
  - `nonMemberCannotDownloadChatFile`
  - `fileGoneAfterMessageDelete`
  - `chatDeleteFreesBlobs`
  - `cannotAttachOthersOrUsedFiles`
  - `perFileChatLimit`
  - `storageFullIs507`
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*ChatFiles*' --offline`. Expected: FAIL.
- [ ] **Step 3: Implement** the M1 changes above.
- [ ] **Step 4: Run** `./gradlew :server:test check --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(relay): chat attachments`

### Task M2: Client media send, cache and download

**Files:**
- Create: `relay-client/.../MediaInfo.kt`, `ChatMediaCache.kt`
- Modify: `ChatController.kt` (`send(chatId, text, files: List<File>)`, `download(att, target)`), `Outbox.kt` (a chat-target variant that posts the message after all uploads), `FakeRelayApi.kt`
- Test: `MediaInfoTest.kt`, `ChatMediaCacheTest.kt`; extend `ChatControllerTest`

**Interfaces — Produces:**
- `fun classify(mime: String?, name: String): AttachmentKind`
- `fun interface Thumbnailer { fun thumbnail(file: File, kind: AttachmentKind): Thumb? }`, with `Thumb(jpeg: ByteArray, width: Int, height: Int, durationMs: Long?)`
  - Default JVM implementation `ImageIoThumbnailer` (subsampled `ImageIO` read, first GIF frame, scaled to 320 px, JPEG quality 0.8). Android supplies its own in M4.
- `class ChatMediaCache(dir: File, capBytes: Long = 512L shl 20)` with:
  - `suspend fun get(att: Attachment, api: RelayApi): File`
  - `pin(fileId)` / `unpin(fileId)`
- `ChatController`:
  - `send(chatId: String, text: String?, files: List<File>, thumbnailer: Thumbnailer)`
  - `download(chatId: String, att: Attachment, target: File)`
  - `pendingSends: StateFlow<List<PendingSend>>` with progress, shown as greyed bubbles

- [ ] **Step 1: Write the failing tests:**
  - `MediaInfoTest`:
    - `classifiesByMime`
    - `corruptImageBecomesFile`
    - `hugeImageThumbnailWithin320` (a 12000×9000 PNG generated in the test)
    - `gifThumbnailIsFirstFrame`
  - `ChatMediaCacheTest`: `cacheEvictsLruNotPinned`, `getDownloadsOnce`
  - `ChatControllerTest`:
    - `messagePostedOnlyAfterAllUploads`
    - `uploadResumesAfterRestart`
    - `downloadOfDeletedMessageFails`
    - `notificationLabelsForMedia`
- [ ] **Step 2: Run** `./gradlew :relay-client:test --offline`. Expected: FAIL.
- [ ] **Step 3: Implement** the M2 interfaces above.
- [ ] **Step 4: Run** `./gradlew :relay-client:test check --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(client): chat media send, cache and download`

### Task M3: PC media bubbles

**Files:**
- Create: `desktop/.../ui/ChatBubbles.kt` (image, GIF, file and folder bubbles), `ui/AnimatedGif.kt` (Skia `Codec` frame loop with each frame's own duration, paused when off screen)
- Modify: `ui/ChatsTab.kt` (attach button, drag and drop or paste into the open chat, the ⋯ menu adds `Download`)
- Test: `desktop/.../ui/ChatBubblesTest.kt`, `AnimatedGifTest.kt`

- [ ] **Step 1: Write the failing tests:**
  - `ChatBubblesTest`:
    - `imageBubbleUsesThumbThenFull`
    - `gifShowsBadge`
    - `fileBubbleShowsNameAndSize`
    - `folderBubbleCounts`
    - `menuHasDownloadForMedia`
    - `pendingSendGreyedWithProgress`
  - `AnimatedGifTest.framesAdvanceByDuration` (a 3-frame GIF generated in the test)
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*ChatBubbles*' --tests '*AnimatedGif*' --offline`. Expected: FAIL.
- [ ] **Step 3: Implement** to the `DesktopChats` mockup. Images are at most 320 px wide inline, and clicking one opens a full-size viewer dialog. Download saves to the configured save folder and shows `Saved to {folder}`.
- [ ] **Step 4: Run** `./gradlew :desktop:test check --offline`. Expected: PASS. Render headless to `scratchpad/chat-media.png`.
- [ ] **Step 5: Commit** `feat(desktop): chat images, GIFs and files`

### Task M4: Phone media bubbles

**Files:**
- Create: `android/.../ui/ChatMediaBubbles.kt`, `android/.../chat/AndroidThumbnailer.kt` (`ImageDecoder` with a target size, first GIF frame)
- Modify:
  - `ui/ChatScreen.kt`: attach via `ActivityResultContracts.OpenMultipleDocuments`; the long-press sheet adds `Download` and `Share…` for media
  - The share-sheet intake (`share/`): a "Send to a chat" target picker when a chat exists
  - `MediaStoreFilePublisher` use for Download
- Test: `android/src/test/.../ui/ChatMediaTest.kt`

- [ ] **Step 1: Write the failing tests** in `ChatMediaTest`:
  - `sheetActionsForMedia`
  - `downloadPublishesToMediaStore` (fake publisher)
  - `shareIntoChatSendsFiles`
  - `pendingSendShowsProgress`
- [ ] **Step 2: Run** `./gradlew :android:testDebugUnitTest --tests '*ChatMedia*' --offline`. Expected: FAIL.
- [ ] **Step 3: Implement** to the `PhoneChat` mockup. GIFs animate through `AnimatedImageDrawable`, wrapped in `AndroidView`. Tapping an image opens it full screen with pinch-zoom.
- [ ] **Step 4: Run** `./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): chat images, GIFs and files`

### Task M5: Docs
- [ ] **Step 1:**
  - README: chat attachments and `MJOLNIR_MAX_CHAT_FILE_MB`.
  - Manual checklist: send a photo, a GIF, a file and a folder from each app; the GIF animates; Download saves; a deleted message's file is gone; a phone that was offline catches up.
- [ ] **Step 2:** Run `./gradlew check --offline`. Expected: PASS.
- [ ] **Step 3: Commit** `docs: chat media`
