# Chat Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make chat videos play inline on both apps. The phone also gets a full-screen player. Download comes from a long-press (phone) or the ⋯ menu (PC).

**Architecture:**
- **Phone:** streams video with Media3 ExoPlayer over ranged HTTP with the bearer header.
- **PC:** plays with a bundled JavaFX Media player, embedded through `SwingPanel` + `JFXPanel`. JavaFX can't send auth headers, so the PC plays from the local media cache (`ChatMediaCache`), downloading with progress first.
- **Thumbnails and duration:** the sender captures these. The phone uses `MediaMetadataRetriever`; the PC uses JavaFX `MediaPlayer` plus a snapshot.

**Tech Stack:**
- PC: `org.openjfx:javafx-media`, `javafx-swing` and `javafx-graphics` (21.x, Windows classifier), bundled in the MSI.
- Phone: `androidx.media3:media3-exoplayer`, `media3-ui` and `media3-datasource-okhttp`.

**Spec:** `docs/superpowers/specs/2026-11-21-space-chats-design.md`. This runs after the chat media plan. Mockups: the video bubble in `DesktopChats`, the `PhoneChat` video bubble and long-press sheet, and `PhoneVideo`.

## Global Constraints
- **Video bubble:** a thumbnail with a play button and a duration badge (`m:ss`).
  - PC: plays inline in the bubble at 320 px wide, with play/pause, a scrub bar and `0:16 / 0:42` time text.
  - Phone: plays inline when tapped; a full-screen button opens the `PhoneVideo` screen with ±10 s, scrubbing and Download.
- **Download:** long-press (phone) or ⋯ (PC) → `Download`, as for other media. The video is never only a download link.
- **One video at a time:** only one video plays at once on each app. Leaving the chat or the screen pauses it.
- **PC fallback:** if JavaFX reports an unsupported codec (`MediaException`), the bubble shows `Can't play this video here` with an `Open in player` button that opens the cached file in the system player.
- **PC download before play:** a video not yet in the cache downloads first, showing a progress ring on the play button. Large videos still respect the 512 MB cache cap (pinned while playing).
- **Phone streaming:** requests carry `Authorization: Bearer {token}` and use the relay's existing ranged `GET /v1/files/{id}`. A 401 or 404 stops playback with `Video unavailable`.
- **Kind:** a video's `AttachmentKind.VIDEO` comes with `durationMs`, `width`, `height` and a thumbnail when capture succeeds. If capture fails, it is sent without a thumbnail and the bubble shows a plain video tile.
- **Packaging:** the MSI build (`./gradlew :desktop:packageMsi` in the windows CI job) must include JavaFX. The installer must still build and launch.

## Review Focus
1. **A portrait or very tall video.** The bubble keeps the aspect ratio, capped at 320 px wide and 400 px tall, with no stretching. Tested in V1 (`portraitBubbleKeepsAspect`) and V2 (`portraitInlineKeepsAspect`).
2. **The chat is closed or scrolled away while a video plays.** The video pauses and releases its player, with no audio from an invisible bubble. Tested in V1 (`leavingChatStopsPlayback`) and V2 (`playerReleasedOnDispose`).
3. **The video's message is deleted during playback.** Playback stops and the bubble becomes `Message deleted`. Tested in V1/V2 (`deletedDuringPlaybackStops`).
4. **An unsupported codec on the PC** (for example HEVC or WebM). The bubble shows the fallback, and nothing crashes. Tested in V1 (`unsupportedCodecShowsFallback`, using a fake player that throws `MediaException`).
5. **The phone loses network mid-stream.** ExoPlayer shows its buffering state and resumes on reconnect; after its retry budget it shows `Video unavailable` with Retry. Tested in V2 (`streamErrorShowsRetry`).

---

### Task V1: PC video player
**Files:**
- Create:
  - `desktop/.../ui/VideoPlayer.kt` (Compose wrapper around the `JFXPanel` `MediaView`; behind an interface `VideoEngine` so tests can use a fake)
  - `desktop/.../media/JavaFxEngine.kt`
  - `desktop/.../media/DesktopVideoThumbnailer.kt` (implements `Thumbnailer` for `VIDEO`, capturing duration and a snapshot at 1 s)
- Modify:
  - `desktop/build.gradle.kts`: JavaFX dependencies for the Windows and Linux classifiers, and the jpackage config so the MSI includes them
  - `ui/ChatBubbles.kt`: the video bubble
  - `ChatController` send path: use the desktop thumbnailer for `VIDEO`
- Test: `desktop/src/test/.../ui/VideoBubbleTest.kt`, `media/VideoEngineTest.kt`

**Interfaces — Produces:**
- `interface VideoEngine { fun load(file: File); fun play(); fun pause(); fun seek(ms: Long); val positionMs: StateFlow<Long>; val durationMs: StateFlow<Long?>; val error: StateFlow<String?>; fun release() }`
- `object NowPlaying` (one active engine; starting another pauses the current one)

- [ ] **Step 1: Write the failing tests:**
  - `VideoBubbleTest`:
    - `thumbnailAndDurationBadge`
    - `downloadsThenPlays`
    - `portraitBubbleKeepsAspect`
    - `unsupportedCodecShowsFallback`
    - `leavingChatStopsPlayback`
    - `deletedDuringPlaybackStops`
    - `onlyOneVideoPlays`
  - `VideoEngineTest.timeTextFormats` (`0:16 / 0:42`, `1:02:03 / 1:10:00`)
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*Video*' --offline`. Expected: FAIL. (JavaFX may need to resolve online once, as qrcodegen did; retry without `--offline` once if needed.)
- [ ] **Step 3: Implement.** Initialise JavaFX once with `Platform.startup` and `Platform.setImplicitExit(false)`.
- [ ] **Step 4: Run** `./gradlew :desktop:test check --offline`. Expected: PASS. Then run `./gradlew :desktop:packageMsi` only if a Windows host is available; otherwise rely on the windows CI job and record that in the report.
- [ ] **Step 5: Commit** `feat(desktop): inline chat video`

### Task V2: Phone video player
**Files:**
- Create:
  - `android/.../ui/VideoBubble.kt` (ExoPlayer `PlayerView` in `AndroidView`)
  - `android/.../ui/VideoScreen.kt` (full screen, per the `PhoneVideo` mockup)
  - `android/.../chat/AndroidVideoThumbnailer.kt` (`MediaMetadataRetriever`: duration, size, frame at 1 s)
- Modify:
  - `android/build.gradle.kts` (Media3)
  - `ui/ChatMediaBubbles.kt` and `ChatScreen.kt` (video bubble; long-press Download / Share… stay as in M4)
  - `AndroidThumbnailer` (delegates `VIDEO`)
- Test: `android/src/test/.../ui/VideoPlayerStateTest.kt` (player-state logic extracted from the composables)

- [ ] **Step 1: Write the failing tests** in `VideoPlayerStateTest`:
  - `authHeaderOnDataSource`
  - `portraitInlineKeepsAspect`
  - `playerReleasedOnDispose`
  - `onlyOneVideoPlays`
  - `deletedDuringPlaybackStops`
  - `streamErrorShowsRetry`
  - `fullScreenKeepsPosition`
- [ ] **Step 2: Run** `./gradlew :android:testDebugUnitTest --tests '*Video*' --offline`. Expected: FAIL (allow one online dependency resolve).
- [ ] **Step 3: Implement.** Use the OkHttp data source with the session's token interceptor.
- [ ] **Step 4: Run** `./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): inline and full-screen chat video`

### Task V3: Docs and CI
- [ ] **Step 1:** Note in the README that the PC installer now bundles a video player and grows by the JavaFX size.
- [ ] **Step 2:** Add a "Chat video" section to the manual checklist:
  - An MP4 recorded on the phone plays inline on the PC and the phone, and full screen on the phone.
  - Download works on both.
  - An unsupported file on the PC shows `Open in player`.
  - Leaving the chat stops playback.
- [ ] **Step 3:** Confirm the windows CI job still produces `mjolnir-msi`, and fix the job if packaging needs the JavaFX jmods.
- [ ] **Step 4:** Run `./gradlew check --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `docs: chat video`
