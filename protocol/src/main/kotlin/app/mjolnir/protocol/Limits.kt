package app.mjolnir.protocol

object Limits {
    const val MAX_TABS_PER_SEND = 100
    const val MAX_URL_LENGTH = 8192
    const val MAX_TITLE_LENGTH = 300
    const val MAX_DEVICE_NAME_LENGTH = 64
    const val MAX_SPACE_NAME_LENGTH = 40
    const val CHAT_INVITE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    const val ADMIN_SILENT_TAKEOVER_MS = 2_592_000_000L
    const val PAIRING_TTL_MS = 300_000L
    const val DELIVERY_TTL_MS = 604_800_000L
    const val MAX_BODY_BYTES = 1_048_576L
    const val MAX_FILES_PER_SEND = 1000
    const val MAX_FILE_BYTES = 104_857_600L
    const val MAX_FILE_NAME_LENGTH = 200
    const val UNSENT_FILE_TTL_MS = 3_600_000L
    const val UPLOAD_CHUNK_BYTES = 8_388_608L
    const val INCOMPLETE_UPLOAD_TTL_MS = 86_400_000L
    const val MAX_PATH_DEPTH = 16
    const val GUEST_ROOM_TTL_MS = 3_600_000L
    const val DOWNLOAD_TICKET_TTL_MS = 60_000L
    const val MAX_RELATIVE_PATH_LENGTH = 1024
}
