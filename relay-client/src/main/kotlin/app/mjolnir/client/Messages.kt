package app.mjolnir.client

import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** User-facing copy shared by the desktop and Android apps. */
object Messages {
    const val RESET = "This device was reset on the relay. Pair again."
    const val NO_LINKS = "No links found in what you shared"

    private fun tabs(n: Int) = if (n == 1) "1 tab" else "$n tabs"
    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

    fun items(tabs: Int, files: Int): String = when {
        tabs > 0 && files > 0 -> "${tabs(tabs)} and ${files(files)}"
        files > 0 -> files(files)
        else -> tabs(tabs)
    }

    private fun tagged(text: String, space: String?) = if (space == null) text else "$text · $space"

    fun caught(tabs: Int, files: Int, from: String, space: String? = null) = tagged("Caught ${items(tabs, files)} from $from", space)
    fun thrown(tabs: Int, files: Int, to: String, space: String? = null) = tagged("Thrown ${items(tabs, files)} to $to", space)
    fun notThrown(reason: String) = "Not thrown: $reason. Your tabs are still here."
    fun sent(tabs: Int, files: Int, to: String, space: String? = null) = tagged("Sent ${items(tabs, files)} to $to", space)
    fun tooBig(name: String) = "$name is too big (max 100 MB)"
    fun unreadable(name: String) = "Couldn't read $name"

    fun size(bytes: Long): String {
        val kb = 1024L; val mb = kb * 1024; val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.1f GB", bytes.toDouble() / gb)
            bytes >= mb -> "${Math.round(bytes.toDouble() / mb)} MB"
            bytes >= kb -> "${Math.round(bytes.toDouble() / kb)} KB"
            else -> "$bytes B"
        }
    }

    fun queued(files: Int, to: String) = "Throwing ${files(files)} to $to in the background"
    fun waitingToThrow(files: Int, bytes: Long, to: String, space: String? = null) = tagged("Waiting for Wi-Fi to throw ${files(files)} (${size(bytes)}) to $to", space)
    fun waitingToReceive(files: Int, bytes: Long, from: String) = "Waiting for Wi-Fi: ${files(files)} (${size(bytes)}) from $from"
    fun filesBack(reason: String) = "Not thrown: $reason. Your files are back in the tray."
    fun tooManyFiles(folder: String) = "$folder has too many files (max 1000)"
    fun emptyFolder(folder: String) = "$folder has no files"
    fun leftOut(n: Int) = if (n == 1) "1 file over 100 MB was left out" else "$n files over 100 MB were left out"
    fun notSent(reason: String) = "Not sent: $reason."

    // ---- Updates ----
    const val INSTALL = "Install"
    const val INSTALLING = "Installing…"
    /** A request the relay doesn't know (an older relay), or a relay older than this app: replaces the generic error. */
    const val RELAY_OUTDATED = "The relay needs updating. Ask whoever runs it to update."
    /** A relay that needs a newer app (HTTP 426, [RelayErrorKind.APP_OUTDATED]): replaces any other error copy. */
    const val APP_OUTDATED = "This relay needs a newer Mjolnir. Install the update."
    /** The banner while [Updater.relayTooOld]. */
    const val RELAY_TOO_OLD = "This version needs a newer relay."

    fun updateAvailable(version: String) = "Update available: $version"
    fun downloadingUpdate(percent: Int) = "Downloading update… $percent%"
    fun updateFailed(reason: String) = "Update failed: $reason. Try again."
    /** Phone only: Android refused an APK signed with another key; trying again can't help. */
    const val UPDATE_SIGNED_DIFFERENTLY = "Update failed: the update is signed differently. Reinstall once from the release page."
    /** Phone only: the app may not install packages yet; the system's setting for it has just been opened. */
    const val ALLOW_INSTALLS = "Allow Mjolnir to install updates, then tap Install again."

    // ---- Chats ----
    const val APPROVE = "Approve"
    const val DENY = "Deny"
    const val JOIN = "Join"
    const val DECLINE = "Decline"
    const val NEW_CHAT_PC = "New chat…"
    const val NEW_CHAT_PHONE = "New chat"
    const val INVITE = "Invite…"
    const val MAKE_OWNER = "Make owner"
    const val RENAME_CHAT = "Rename chat"
    const val LEAVE_CHAT = "Leave chat"
    const val DELETE_CHAT = "Delete chat"
    const val CHANNELS = "Channels"
    const val PRIVATE_CHATS = "Private chats"
    const val NEW_CHANNEL = "New channel"
    const val CHANNEL_NAME = "Channel name"
    const val MUTE = "Mute"
    const val UNMUTE = "Unmute"
    const val MUTED = "Muted"
    const val ONLINE = "Online"
    const val NOT_FOR_OPEN_CHANNELS = "Open channels can't be joined or left — everyone in the space is in them."
    const val EDITED = "(edited)"
    const val MESSAGE_DELETED = "Message deleted"
    const val DELETED_BY_ADMIN = "Deleted by the space admin"
    const val REMOVED_BY_ADMIN = "Removed by the space admin"
    const val ADMIN_VIEW = "admin view"
    const val COPY_LINK = "Copy link"
    const val SHARE = "Share…"
    const val EDIT = "Edit"
    const val DELETE = "Delete"
    const val INFO = "Info"
    const val READ = "Read"
    const val SEEN = "Seen"
    const val NOBODY_YET = "Nobody yet"
    const val DOWNLOAD = "Download"
    const val DISMISS = "Dismiss"
    /** On a send still uploading: stops it and drops what it had not posted. */
    const val CANCEL = "Cancel"
    /** The PC's video bubble when JavaFX can't decode the video (HEVC, WebM…), with [OPEN_IN_PLAYER]. */
    const val CANT_PLAY_VIDEO = "Can't play this video here"
    const val OPEN_IN_PLAYER = "Open in player"
    /** A video that can't be fetched (deleted, or this device lost access). */
    const val VIDEO_UNAVAILABLE = "Video unavailable"
    /** Under [VIDEO_UNAVAILABLE]: on the phone tries the stream again from where it stopped; on the PC fetches it again. */
    const val RETRY = "Retry"
    /** The phone's chat-sending notification channel, and its title when no send is going any more. */
    const val SENDING_TO_CHATS = "Sending to chats"

    /** The owner's approval prompt: "Sam wants to add Ann". */
    fun inviteApproval(inviter: String, name: String) = "$inviter wants to add $name"
    fun invitedTo(chat: String) = "You're invited to $chat"
    /** The composer's placeholder. */
    fun messageChat(chat: String) = "Message $chat"
    fun chatNotification(name: String, chat: String, text: String) = "$name in $chat: $text"
    fun mentionNotification(name: String, chat: String, text: String) = "$name mentioned you in $chat: $text"

    /** What a photo, GIF, video or file stands for in a notification and the chat list. */
    fun mediaLabel(kind: AttachmentKind): String = when (kind) {
        AttachmentKind.IMAGE -> "Photo"
        AttachmentKind.GIF -> "GIF"
        AttachmentKind.VIDEO -> "Video"
        AttachmentKind.FILE -> "File"
    }

    /**
     * A message in a notification or the chat list: its text, else the label of its first attachment ("Photo"); a video
     * with a known length adds it ("Video · 0:42").
     */
    fun messagePreview(m: ChatMessage): String =
        m.text?.takeIf { it.isNotBlank() } ?: m.attachments.firstOrNull()?.let { a ->
            val length = if (a.kind == AttachmentKind.VIDEO) validDurationMs(a.durationMs) else null
            if (length != null) "${mediaLabel(a.kind)} · ${duration(length)}" else mediaLabel(a.kind)
        } ?: m.text.orEmpty()

    /** A video's length or position: "0:42", "12:05", "1:02:03" (whole seconds, rounded down; negative counts as 0). */
    fun duration(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600; val m = total / 60 % 60; val sec = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** A playing video's time text: "0:16 / 0:42", "1:02:03 / 1:10:00". */
    fun videoTime(positionMs: Long, durationMs: Long): String = "${duration(positionMs)} / ${duration(durationMs)}"

    /** A file bubble's detail line: "report.pdf · 2 MB". */
    fun attachmentDetail(name: String, bytes: Long) = "$name · ${size(bytes)}"
    /** Where a PC download went: "Saved to {the save folder}". */
    fun savedTo(folder: String) = "Saved to $folder"
    /** A file bubble's second line, under its name (ruling CM-R4): "File · 220 KB", "Video · 18 MB". */
    fun fileBubbleDetail(kind: AttachmentKind, bytes: Long) = "${mediaLabel(kind)} · ${size(bytes)}"
    /** A folder bubble's detail line: "Folder · 12 files · 3 MB". */
    fun folderDetail(files: Int, bytes: Long) = "Folder · ${files(files)} · ${size(bytes)}"

    /** A file of a chat send the relay refused as too large; the rest of the send goes on without it (ruling CM-R7). */
    fun tooBigForRelay(name: String) = tooBigForRelay(listOf(name))
    fun tooBigForRelay(names: List<String>) =
        if (names.size == 1) "${names[0]} is too big for the relay" else "${names.joinToString(", ")} are too big for the relay"

    /** Why a send with attachments failed for good, as the failed bubble shows it. */
    fun mediaNotSent(e: RelayException): String {
        if (e.kind == RelayErrorKind.APP_OUTDATED) return APP_OUTDATED
        if (e.kind == RelayErrorKind.RELAY_OUTDATED) return RELAY_OUTDATED
        val reason = e.userMessage?.replaceFirstChar(Char::lowercaseChar) ?: when {
            e.kind == RelayErrorKind.TOO_LARGE -> "a file is too big for the relay"
            e.kind == RelayErrorKind.STORAGE_FULL -> failureReason(e.kind, "")
            e.kind == RelayErrorKind.UNAUTHORIZED -> failureReason(e.kind, "")
            e.message == "invalid_files" -> "a file expired on the relay"
            e.message == "not_member" -> "you're no longer in this chat"
            e.kind == RelayErrorKind.NOT_FOUND -> "that chat is no longer there"
            e.needsRelayUpdate -> return RELAY_OUTDATED
            else -> "the relay had a problem"
        }
        return notSent(reason)
    }

    /** The line under your latest message: who has read and who has only seen it; empty while nobody has. */
    fun receiptLine(read: List<String>, seen: List<String>, everyone: Boolean): String = when {
        everyone -> "Read by everyone"
        read.isNotEmpty() && seen.isNotEmpty() -> "Read by ${read.joinToString(", ")} · Seen by ${seen.joinToString(", ")}"
        read.isNotEmpty() -> "Read by ${read.joinToString(", ")}"
        seen.isNotEmpty() -> "Seen by ${seen.joinToString(", ")}"
        else -> ""
    }

    /** A system entry as the centred line shows it; [nameOf] turns a device id into its shown name. */
    fun systemLine(event: ChatEvent, nameOf: (String) -> String): String = when (event) {
        is ChatEvent.MemberJoined -> "${nameOf(event.deviceId)} joined"
        is ChatEvent.MemberLeft -> "${nameOf(event.deviceId)} left"
        is ChatEvent.MemberRemoved ->
            if (event.byAdmin) "${nameOf(event.deviceId)} · $REMOVED_BY_ADMIN" else "${nameOf(event.deviceId)} was removed"
        is ChatEvent.OwnerChanged -> "${nameOf(event.deviceId)} is now the owner"
        is ChatEvent.Renamed -> "Renamed to ${event.name}"
    }

    fun channelHint(space: String) = "Everyone in $space can see this channel."
    fun deleteChannelConfirm(name: String, space: String) = "Delete #$name? Everyone in $space loses its messages."

    /** An open channel's "Seen by" line: the names when [names] has at most 5, else their count. */
    fun seenBySummary(names: List<String>): String = if (names.size <= 5) names.joinToString(", ") else "Seen by ${names.size}"

    /** "Last seen ..." for a device last online at [lastSeenMs]; empty when that is unknown. */
    fun lastSeen(nowMs: Long, lastSeenMs: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
        if (lastSeenMs == null) return ""
        val ago = (nowMs - lastSeenMs).coerceAtLeast(0)
        val minutes = ago / 60_000
        val hours = ago / 3_600_000
        return when {
            minutes < 1 -> "Last seen just now"
            minutes < 60 -> "Last seen ${minutes}m ago"
            hours < 24 -> "Last seen ${hours}h ago"
            hours < 48 -> "Last seen yesterday"
            else -> "Last seen " + DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(zone).format(Instant.ofEpochMilli(lastSeenMs))
        }
    }

    /** A refused chat action as a sentence; the relay's code is the exception's message. */
    fun chatError(e: RelayException): String = when {
        e.kind == RelayErrorKind.APP_OUTDATED -> APP_OUTDATED
        e.kind == RelayErrorKind.RELAY_OUTDATED -> RELAY_OUTDATED
        e.message == "too_many_files" -> e.userMessage ?: "That folder has too many files (max 1000)."
        e.kind == RelayErrorKind.NETWORK -> "Can't reach the relay. Check your connection."
        e.kind == RelayErrorKind.RATE_LIMITED -> "Too many messages. Wait a moment."
        e.kind == RelayErrorKind.UNAUTHORIZED -> RESET
        e.message == "too_long" -> "That message is too long (4,000 characters at most)."
        e.message == "empty" -> "Write something first."
        e.message == "owner_must_transfer" -> "Make someone else the owner first."
        e.message == "already_invited" -> "Already invited."
        e.message == "already_member" -> "Already in this chat."
        e.message == "invalid_mention" -> "Only people in this chat can be mentioned."
        e.message == "deleted" -> "That message was deleted."
        e.message == "not_for_open_channels" -> NOT_FOR_OPEN_CHANNELS
        e.kind == RelayErrorKind.NOT_FOUND -> "That chat is no longer there."
        e.needsRelayUpdate -> RELAY_OUTDATED
        else -> "Something went wrong. Try again."
    }

    fun failureReason(kind: RelayErrorKind, peerName: String): String = when (kind) {
        RelayErrorKind.NETWORK -> "no connection"
        RelayErrorKind.NOT_PAIRED -> "$peerName is no longer paired"
        RelayErrorKind.RATE_LIMITED -> "too many throws, wait a minute"
        RelayErrorKind.UNAUTHORIZED -> "this device was reset on the relay"
        RelayErrorKind.TOO_LARGE -> "a file is too big for the relay"
        RelayErrorKind.STORAGE_FULL -> "the relay is out of space"
        RelayErrorKind.RELAY_OUTDATED -> "the relay needs updating"
        RelayErrorKind.APP_OUTDATED -> "the relay needs a newer Mjolnir"
        else -> "the relay had a problem"
    }

    /** [failureReason] for a failed call: an error from a relay too old for this app says the relay needs updating. */
    fun failureReason(e: RelayException, peerName: String): String = relayUpdateReason(e) ?: failureReason(e.kind, peerName)

    /** Kinds whose [failureReason] is specific whatever the relay's age: exactly those with a branch of their own there. */
    internal val SPECIFIC_FAILURE_KINDS = setOf(
        RelayErrorKind.NETWORK, RelayErrorKind.NOT_PAIRED, RelayErrorKind.RATE_LIMITED,
        RelayErrorKind.UNAUTHORIZED, RelayErrorKind.TOO_LARGE, RelayErrorKind.STORAGE_FULL,
        RelayErrorKind.RELAY_OUTDATED, RelayErrorKind.APP_OUTDATED,
    )

    /**
     * "the relay needs updating" when [e] needs a newer relay and its kind has no specific [failureReason]
     * (e.g. INVALID or SERVER from an older relay); else null.
     */
    fun relayUpdateReason(e: RelayException): String? =
        if (e.needsRelayUpdate && e.kind !in SPECIFIC_FAILURE_KINDS) failureReason(RelayErrorKind.RELAY_OUTDATED, "") else null

    // ---- Voice ([MUTE] and [UNMUTE] above serve voice too) ----
    const val VOICE = "Voice"
    const val JOIN_VOICE = "Join voice"
    const val LEAVE_VOICE = "Leave"
    const val PUSH_TO_TALK = "Push to talk"
    const val OPEN_MIC = "Open mic"
    const val HOLD_TO_TALK = "Hold ` to talk"
    const val NEW_VOICE_ROOM = "New voice room"
    const val VOICE_ROOM_NAME = "Voice room name"
    const val RECONNECTING = "Reconnecting…"
    const val USE_HEADPHONES = "Use headphones to avoid echo"
    const val MIC_PERMISSION = "Mjolnir needs the microphone for voice rooms."
    const val OPEN_SETTINGS = "Open settings"
    /** A voice session that could not open the microphone or speaker (no device, or another app has it). */
    const val VOICE_AUDIO_ERROR = "Can't use the microphone or speaker."

    fun inVoice(room: String) = "In voice: $room"
    /** The title of the phone's ongoing voice notification. */
    fun voiceNotificationTitle(room: String) = "Mjolnir voice — $room"
    fun deleteVoiceRoomConfirm(room: String) = "Delete $room? Anyone in it is disconnected."
    /** An unknown limit ([max] ≤ 0) reads as the relay's default of 8; an unknown count as the limit. Never "0/0". */
    fun voiceFull(room: String, n: Int, max: Int): String {
        val limit = if (max > 0) max else DEFAULT_VOICE_MAX
        return "$room is full (${if (n > 0) n else limit}/$limit)"
    }
    private const val DEFAULT_VOICE_MAX = 8

    /**
     * Why a voice session in [room] ended, from [VoiceState.Ended]'s reason, or null when there is nothing to say (the
     * user left). For `room_full`, [n] and [max] are the room's count and limit (unknown: 0; see [voiceFull]).
     * A relay that refused the voice socket itself says so with the app-wide copy.
     */
    fun voiceEnded(reason: String?, room: String, n: Int = 0, max: Int = 0): String? = when (reason) {
        VOICE_RELAY_OUTDATED -> RELAY_OUTDATED
        VOICE_APP_OUTDATED -> APP_OUTDATED
        "connection_lost" -> "You left $room (connection lost)"
        "room_full" -> voiceFull(room, n, max)
        "room_deleted" -> "$room was deleted"
        "removed" -> "You were removed from $room"
        "replaced" -> "You joined $room somewhere else"
        "not_found" -> "$room is gone"
        "audio_error" -> VOICE_AUDIO_ERROR
        else -> null
    }
}
