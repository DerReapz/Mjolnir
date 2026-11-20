package app.mjolnir.android.ui

import app.mjolnir.android.data.CaughtItem
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.Nicknames
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

class CaughtFromTest {
    private val nicknames = Nicknames(
        JsonFileStore(File(createTempDirectory("caughtnick").toFile(), "n.json"), MapSerializer(String.serializer(), String.serializer())) { emptyMap() },
    )
    private val withId = CaughtItem(fromName = "DESKTOP-4F2K", caughtAtEpochMs = 0, fromDeviceId = "pc1")
    private val old = CaughtItem(fromName = "DESKTOP-4F2K", caughtAtEpochMs = 0)

    @Test fun caughtListUsesCurrentNickname() {
        assertEquals("DESKTOP-4F2K", caughtFrom(withId, nicknames.changes.value))
        nicknames.set("pc1", "Work PC")
        assertEquals("Work PC", caughtFrom(withId, nicknames.changes.value))
        assertEquals("DESKTOP-4F2K", caughtFrom(old, nicknames.changes.value)) // saved before ids were kept
        nicknames.remove("pc1")
        assertEquals("DESKTOP-4F2K", caughtFrom(withId, nicknames.changes.value))
    }
}
