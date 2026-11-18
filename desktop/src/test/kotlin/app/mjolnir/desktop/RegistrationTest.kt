package app.mjolnir.desktop

import app.mjolnir.client.*
import app.mjolnir.protocol.*
import java.io.File
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class RegistrationTest {
    private fun store(dir: File, c: DesktopConfig) = JsonFileStore(File(dir, "config.json"), DesktopConfig.serializer()) { c }

    // no token: registers as a PC and stores the token
    @Test fun registersWhenNoToken(@TempDir dir: File) = runTest {
        val api = FakeRelayApi(); val s = store(dir, DesktopConfig("https://r", deviceName = "DESKTOP-4F2K"))
        val c = ensureRegistered(s, api)
        assertEquals(listOf("DESKTOP-4F2K" to DeviceKind.PC), api.registerCalls)
        assertEquals("tok-1", c.token); assertEquals("tok-1", s.read().token)
    }
    // existing token: nothing is registered
    @Test fun skipsRegistrationWhenTokenPresent(@TempDir dir: File) = runTest {
        val api = FakeRelayApi(); val s = store(dir, DesktopConfig("https://r", "d", "t", "PC"))
        assertEquals("t", ensureRegistered(s, api).token)
        assertTrue(api.registerCalls.isEmpty())
    }
    // re-registering drops the phone and swaps the token
    @Test fun reRegisterClearsPairingStateAndReplacesToken(@TempDir dir: File) = runTest {
        val api = FakeRelayApi().apply { registerResult = RegisterResponse("dev-2", "tok-2") }
        val s = store(dir, DesktopConfig("https://r", "d", "old", "PC", lastPhoneId = "ph"))
        val c = reRegister(s, api)
        assertNull(c.lastPhoneId); assertEquals("tok-2", c.token); assertEquals("dev-2", c.deviceId)
    }
}
