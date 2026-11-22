package app.mjolnir.client

import app.mjolnir.protocol.AttachmentKind
import app.mjolnir.protocol.ChatEvent
import app.mjolnir.protocol.ChatMessage
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
    /** Under [VIDEO_UNAVAILABLE] on the phone: tries the stream again from where it stopped. */
    const val RETRY = "Retry"

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
    fun mediaNotSent(e: RelayException): String = notSent(
        e.userMessage?.replaceFirstChar(Char::lowercaseChar) ?: when {
            e.kind == RelayErrorKind.TOO_LARGE -> "a file is too big for the relay"
            e.kind == RelayErrorKind.STORAGE_FULL -> failureReason(e.kind, "")
            e.kind == RelayErrorKind.UNAUTHORIZED -> failureReason(e.kind, "")
            e.message == "invalid_files" -> "a file expired on the relay"
            e.message == "not_member" -> "you're no longer in this chat"
            e.kind == RelayErrorKind.NOT_FOUND -> "that chat is no longer there"
            else -> "the relay had a problem"
        },
    )

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

    /** A refused chat action as a sentence; the relay's code is the exception's message. */
    fun chatError(e: RelayException): String = when {
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
        e.kind == RelayErrorKind.NOT_FOUND -> "That chat is no longer there."
        else -> "Something went wrong. Try again."
    }

    fun failureReason(kind: RelayErrorKind, peerName: String): String = when (kind) {
        RelayErrorKind.NETWORK -> "no connection"
        RelayErrorKind.NOT_PAIRED -> "$peerName is no longer paired"
        RelayErrorKind.RATE_LIMITED -> "too many throws, wait a minute"
        RelayErrorKind.UNAUTHORIZED -> "this device was reset on the relay"
        RelayErrorKind.TOO_LARGE -> "a file is too big for the relay"
        RelayErrorKind.STORAGE_FULL -> "the relay is out of space"
        else -> "the relay had a problem"
    }
}
