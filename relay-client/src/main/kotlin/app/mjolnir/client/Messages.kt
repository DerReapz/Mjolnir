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

    /** A message in a notification or the chat list: its text, else the label of its first attachment ("Photo"). */
    fun messagePreview(m: ChatMessage): String =
        m.text?.takeIf { it.isNotBlank() } ?: m.attachments.firstOrNull()?.let { mediaLabel(it.kind) } ?: m.text.orEmpty()

    /** A file bubble's detail line: "report.pdf · 2 MB". */
    fun attachmentDetail(name: String, bytes: Long) = "$name · ${size(bytes)}"
    /** A folder bubble's detail line: "Folder · 12 files · 3 MB". */
    fun folderDetail(files: Int, bytes: Long) = "Folder · ${files(files)} · ${size(bytes)}"

    /** Why a send with attachments failed for good, as the failed bubble shows it. */
    fun mediaNotSent(e: RelayException): String = notSent(
        e.userMessage ?: when {
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
