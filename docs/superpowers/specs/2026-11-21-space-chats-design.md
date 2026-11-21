# Space chats: design

## Context
Spaces (admin-controlled groups of devices that can all send to each other) are built and merged. The owner now wants chat inside a space. These choices are confirmed:

- **Several private chats per space.** Anyone in the space can start a named chat. Space members who aren't in a chat can't see that it exists. The one exception is the space admin (below).
- **Joining takes three steps:**
  1. The chat creator, or any chat member, invites a member of the space.
  2. If someone other than the creator sent the invite, the creator approves it.
  3. The invitee accepts with Join, or declines with Decline.

  Until all of that is done, the invitee sees no messages.
- **The creator is the chat's owner.** To leave, the creator must first hand ownership to another member (*Make owner*) or delete the chat. If the creator is removed from the space, ownership passes to the longest-standing member.
- **Space admin oversight:** the space admin can see and read every chat in their space and moderate it, without being a member.
  - "Revise" is read here as moderation: rename the chat, remove members, delete any message, and delete the chat.
  - The admin cannot edit other people's message text.
  - To post, the admin joins the chat directly, with no approval needed.
- **Messages are kept until the chat is deleted.** Someone who joins later sees the full history.
- **Who can delete a chat:** the creator (owner) or the space admin.
- **Message content:** text, links, files, images, GIFs and videos.
  - Images and GIFs show inline, and GIFs animate.
  - Videos play inline on both the phone and the PC, not as downloadable files.
  - A long-press (right-click or a ⋯ menu on the PC) offers **Download**.
  - The PC bundles a video player.
- **Message authors can edit and delete their own messages.** Edited messages show `(edited)`. Deleting a message frees its files on the relay.
- **Mockups come first:** chat screens go on the existing design board, and the owner approves them before any building starts.

People in Mjolnir are devices (there are no accounts). A message's author is a device, shown by nickname, with the space label where relevant.

## Approach
The relay stores chats as their own records next to spaces, and the client syncs each chat with a revision cursor, so edits, deletes and offline catch-up all go through one path. Live updates arrive over the existing WebSocket, and phones also get the existing FCM "doorbell". Media files reuse the existing resumable uploads and ranged downloads, but they are owned by a message rather than a delivery, so the 1-hour and 7-day purges never touch them. The sender attaches a small thumbnail and metadata (dimensions, duration), so every client can draw media bubbles immediately, before downloading anything.

## Step 0: Mockups (first, before any code)
Add artboards to the design board at https://claude.ai/artifact/1icxaRRXueAkghY1UxXpa3, in the Forge style:
- **PC:** a Chats tab with a chat list grouped by space, a conversation pane, and bubbles for text with links, an image, an animated GIF, an inline video with controls, a file, and an `(edited)` message. Also show the ⋯ menu (Download / Edit / Delete) and the invite dialog.
- **Phone:** the chat list, a conversation, the long-press sheet (Download / Edit / Delete), and a full-screen video.

The owner approves these before the build plan is written.

## Behaviour (binding rules)
- **Starting a chat:** a chat belongs to exactly one space. Any space member can create one, with a name of at most 40 characters, and the creator is its first member.
- **Inviting:**
  - Any chat member can invite a space member who isn't already in the chat. An invite from the creator skips approval.
  - Every other invite waits for the creator's approval: the creator sees `{inviter} wants to add {name}` with Approve / Deny.
  - Once approved, the invitee sees `You're invited to {chat}` with Join / Decline. Joining is what grants access.
  - Pending invites expire after 7 days, or when the invitee or the inviter leaves the space.
- **Ownership:** *Make owner* hands the creator role to another member. Removal from the space passes ownership to the longest-standing member.
- **Leaving:**
  - A member can leave at any time. The owner must hand over or delete first.
  - Removal from the space removes the device from all of that space's chats, and the relay enforces this.
  - When the last member leaves, the chat is deleted.
- **Deleting a chat:** the owner or the space admin can delete it. Deleting the space deletes its chats. Either way, all messages and files go.
- **Who sees what:**
  - Chat names, members and messages are visible only to that chat's members and to the space admin. This includes the history from before a member joined.
  - Other space members never see that a chat exists.
  - A device that leaves loses access.
- **Admin moderation:**
  - The admin can rename the chat, remove a member, delete any message and delete the chat.
  - Moderation by a non-member admin shows in the chat as `Removed by the space admin` or `Deleted by the space admin`.
  - Admin access is checked on the relay with the existing `adminSpace` check.
- **Editing and deleting messages:** an author can edit their own text, and the message shows `(edited)`. An author can delete their own message, which becomes `Message deleted` for everyone; the relay drops its body and files.
- **Guests:** guest devices can never be in chats. This already holds, because guests can't join spaces.
- **Storage:** chat files count against the relay's global storage cap. Add `MJOLNIR_MAX_CHAT_FILE_MB`, defaulting to the per-file limit (videos may need more, so the operator can raise it). When storage is full, the existing "relay full" message is shown.
- **Notifications:** a new message notifies the other members' devices unless that chat is open on screen. The text reads `{name} in {chat}: {text}`, or a media label such as `Photo`, `Video` or `File`. The Chats list shows an unread count per chat.

## Relay (`server/`)
- **Schema** (`Store.kt`, additive and upgraded in place as before with `addColumnIfMissing` / `CREATE TABLE IF NOT EXISTS`):
  - `chats(id, space_id, name, owner_id, created_at, rev)`
  - `chat_members(chat_id, device_id, joined_at, read_rev)`
  - `chat_invites(id, chat_id, invitee_id, inviter_id, state PENDING_APPROVAL|APPROVED|DECLINED|DENIED, created_at)`
  - `chat_messages(id, chat_id, author_id, rev, sent_at, edited_at NULL, deleted INTEGER, body_json)`
  - Add `files.chat_message_id NULL`.
  - `rev` is a per-chat counter, bumped on every new message, edit and delete.
- **Purge:** `purgeExpired` excludes files with a `chat_message_id`. These files are deleted only with their message or chat.
- **Downloads:** `fileForDownload` also allows a file whose message's chat has the caller as a member. Tickets are unchanged.
- **Routes** (new `ChatRoutes.kt`, using the `memberSpace` and admin checks pattern from `SpaceRoutes.kt`):
  - `GET/POST /v1/spaces/{id}/chats`
  - `PATCH/DELETE /v1/chats/{id}`
  - `POST /v1/chats/{id}/invites`: a member invites. An invite from the owner starts as APPROVED.
  - `POST /v1/chat-invites/{id}/approve|deny`: owner only.
  - `POST /v1/chat-invites/{id}/join|decline`: invitee only, and only once the invite is APPROVED.
  - `GET /v1/chat-invites`: the caller's actionable invites, both approvals and invitations.
  - `POST /v1/chats/{id}/owner {deviceId}`
  - `DELETE /v1/chats/{id}/members/{deviceId}`: self (leave), or the space admin (remove).
  - `POST /v1/chats/{id}/join`: space admin only, no approval.
  - Listing:
    - `GET /v1/spaces/{id}/chats` returns only the chats the caller is a member of.
    - For the space admin it returns every chat, each flagged `member: Boolean`.
  - `GET /v1/chats/{id}/sync?sinceRev=` returns the messages changed after that rev, plus the current members.
  - `POST /v1/chats/{id}/messages` takes `{text?, fileIds[], media[]}`.
  - `PATCH/DELETE /v1/chats/{id}/messages/{msgId}`, author only.
  - `POST /v1/chats/{id}/read {rev}`.
- **Events:** `RelayEvent.ChatChanged(chatId, rev)` is sent to members over `SessionHub`, and `push.ping` goes to members with push tokens. Space removal and deletion also emit `ChatChanged` / `ChatDeleted`.
- **Body format:** `ChatBody(text: String?, attachments: List<Attachment>)`, where `Attachment(fileId, kind: IMAGE|GIF|VIDEO|FILE, name, sizeBytes, mime, width?, height?, durationMs?, thumbFileId?)`.

## Shared client (`relay-client/`)
- Add the chat calls to `RelayApi` / `RelayClient` / `FakeRelayApi`.
- `ChatStore` keeps a local cache per chat: a `JsonFileStore` of messages plus `lastRev`, in app data.
- `ChatController` exposes:
  - chat lists per space
  - `sync(chatId)`, called on `ChatChanged`, `Connected` or opening a chat
  - `send(text, files)`, which uploads through the existing `FileTransfer` / `Outbox` path with the chat as the target, then posts the message
  - `edit`, `delete`, `markRead`, and unread counts
- `MediaInfo` classifies a file as image, GIF, video or other and produces the thumbnail and metadata on the sending side. There is a platform hook for video thumbnails and duration: `MediaMetadataRetriever` on Android, the player's snapshot on the PC.
- Message copy goes into `Messages.kt`.

## PC app (`desktop/`)
- **Chats tab:** add `MainTab.CHATS` with a `Chats` sidebar item and unread badge. The tab has a list grouped by space on the left and the conversation on the right, in the Forge style, matching the approved mockup.
- **Composer:** a text field plus Attach (files and folders), and drag-and-drop or paste into a chat sends there.
- **Bubbles:**
  - Text, with linkified links that open in the browser.
  - Images, decoded with Skia, plus the existing thumbnail cache.
  - Animated GIFs, using `org.jetbrains.skia.Codec` to animate the frames.
  - Videos, played inline with a bundled player. The player is JavaFX Media (`org.openjfx:javafx-media`/`javafx-swing`) embedded with `SwingPanel` + `JFXPanel`. If a codec isn't supported, it falls back to "Open in player", using the system player. The MSI build adds the JavaFX jars.
  - Files, shown with Download.
  - The ⋯ menu offers Download / Edit / Delete; Edit and Delete appear for the author only.
- **Chat management:** the Devices tab's space card gets *New chat…*, plus chat settings with members, *Invite…* (a picker of space members), *Leave chat* and *Delete chat* (creator or admin).
- **Notifications:** tray notifications for new messages.

## Phone app (`android/`)
- **Navigation:** a `Chats` bottom-nav item with an unread badge, then a chat list, then the conversation screen. These match the approved mockup.
- **Composer:** text, Attach (system picker for photos, videos and files), and the share sheet can target a chat.
- **Bubbles:**
  - Linkified text.
  - Images and GIFs via `ImageDecoder` / `AnimatedImageDrawable` (API 28+, and minSdk is 29, so no new image library).
  - Videos play inline with Media3 ExoPlayer (new dependency `androidx.media3:media3-exoplayer` and `media3-ui`), streaming over the ticket or authorization header with ranged requests, with tap for full screen.
  - Files.
  - A long-press sheet offers Download (saved through the existing `MediaStoreFilePublisher`), and Edit / Delete for the author.
- **Background updates:** the FCM doorbell triggers `InboxWorker`, which also syncs chats with unread changes and posts notifications. This works without a persistent collector, so it fits the earlier Android rulings. While a chat is open, the app collects events for live updates.

## Build split
This is too large for one plan, so it comes after Step 0 as three plans, executed in order with the same review process as before:
1. **Chat core:** relay schema and routes, client sync and cache, text and links, invite, leave and delete, edit and delete messages, notifications, plus the PC and phone chat UI for text.
2. **Chat media:** attachments, thumbnails and metadata, image and GIF rendering, file bubbles, Download on both apps.
3. **Video:** the PC JavaFX player and MSI packaging, Android ExoPlayer, and video thumbnails.

## Files to touch (representative)
- **Relay:** `server/.../Store.kt`, new `ChatRoutes.kt`, `FileRoutes.kt` (download access), `RelayModule.kt`, `Main.kt` (env)
- **Shared:** `protocol/.../Models.kt`
- **Client:** `relay-client/.../RelayApi.kt`, `RelayClient.kt`, new `Chat*.kt`, `FakeRelayApi.kt`
- **PC:** `desktop/.../AppController.kt`, `CatchService.kt`, `ui/MainWindow.kt`, new `ui/ChatsTab.kt` / `ui/ChatBubbles.kt` / `ui/VideoPlayer.kt`, `ui/SpacesSection.kt`, `desktop/build.gradle.kts`
- **Phone:** `android/.../ui/MjolnirNav.kt`, new `ui/Chat*Screen.kt` / `ChatViewModel.kt`, `receive/InboxWorker.kt`, `Notifications.kt`, `android/build.gradle.kts`
- **Docs:** `README.md`, `docs/manual-verification.md`

## Verification
- **Relay tests**, one per rule:
  - Non-members can't read, post or sync.
  - Non-member, non-admin space members can't see that a chat exists in any listing, event or error.
  - Invitees must be space members.
  - A non-owner invite needs owner approval, then the invitee's Join, before any access.
  - Deny, decline and expiry all leave no access.
  - The owner can't leave without handing over.
  - Ownership passes on removal from the space.
  - The admin can read and moderate without being a member.
  - The admin can't edit other people's text.
  - Leaving or removal from the space revokes access.
  - Chat delete is limited to the creator or admin.
  - Edit and delete are limited to the author.
  - Chat files survive the purges and are freed on message or chat delete.
  - Sync returns edits and deletes after the given rev.
  - The DB upgrade works from an older database.
- **Client tests** for `ChatController` sync, the cache, unread counts and the send with upload path, run against `FakeRelayApi`.
- **App tests:** view-model and controller tests on both apps, headless renders of the PC Chats tab compared with the mockup, and `./gradlew check :android:lintDebug :android:assembleDebug`. The Windows MSI CI job must still build with JavaFX.
- **Manual checklist** in `docs/manual-verification.md`, using two PCs and a phone in one space:
  - Create a chat, invite someone, and send text, a link, a photo, a GIF, a video and a file.
  - Video plays inline on both apps.
  - Long-press (phone) or ⋯ (PC) → Download saves the file.
  - Edit and delete messages.
  - Leave the chat; a member removed from the space loses the chat.
  - Delete the chat as the admin.
  - Offline catch-up after a phone was off.
- **Reminder for the owner:** the Synology relay must be updated to the new relay jar, which also fixes their current spaces and guest room errors.

## Status
Approved 2026-11-21. Mockups approved (design board artboards DesktopChats, DesktopChatInvite, PhoneChatList, PhoneChat, PhoneVideo); they include Share… in the phone long-press sheet and Copy link in the PC ⋯ menu.

## Addition (approved 2026-11-21): @mentions and seen/read indicators
- **@mentions:**
  - In the composer, typing `@` opens a picker of the chat's members, filtered as you type. Picking one inserts `@{shown name}` and records the device id in the message's `mentions`.
  - Only chat members can be mentioned, and there is no `@everyone`.
  - Mention chips render in amber. A message mentioning you gets an amber left edge.
  - The mentioned device gets a separate, higher-priority notification: `{name} mentioned you in {chat}: {text}`. It arrives even if that chat is open on another of your devices, but not if it is open on the device itself.
  - The Chats list shows an `@` marker for chats with unread mentions.
- **Seen and read:**
  - **Seen** means the message reached that member's device: the relay records it when the device syncs, including in the background on the phone.
  - **Read** means the chat was on screen with the message visible.
  - The relay keeps `seen_rev` and `read_rev` per member per chat.
  - Under your latest message, a line shows `Read by {names} · Seen by {names}`, or `Read by everyone` once all members have read it.
  - Long-press (phone) or ⋯ → `Info` (PC) on your own message lists the members under Read and Seen, with times.
  - A space admin reading a chat as a non-member is never counted or shown.
  - There is no switch to turn read indicators off.
- **Profile recovery is out of scope.** The owner's answer: a user joins on the new device, the admin removes the old one, and the user renames the new one.
