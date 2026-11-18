package app.mjolnir.desktop

import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class DesktopConfigTest {
    // R15: a blank saveDir falls back to Downloads\Mjolnir
    @Test fun blankSaveDirMeansDefault(@TempDir home: File) {
        val dir = DesktopConfig(relayUrl = "", deviceName = "PC", saveDir = "  ").saveDirFile(home.path)
        assertEquals(File(File(home, "Downloads"), "Mjolnir"), dir); assertTrue(dir.isDirectory)
    }
    @Test fun configuredSaveDirIsUsed(@TempDir home: File) {
        val custom = File(home, "Elsewhere")
        assertEquals(custom, DesktopConfig(relayUrl = "", deviceName = "PC", saveDir = custom.path).saveDirFile(home.path))
    }
    // the Send to menu is on by default, also for a config.json written before the setting existed
    @Test fun sendToMenuDefaultsOn() {
        assertTrue(DesktopConfig(relayUrl = "", deviceName = "PC").sendToMenu)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertTrue(json.decodeFromString(DesktopConfig.serializer(), """{"relayUrl":"","deviceName":"PC"}""").sendToMenu)
    }
}
