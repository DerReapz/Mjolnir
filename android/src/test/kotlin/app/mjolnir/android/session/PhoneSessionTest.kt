package app.mjolnir.android.session

import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.client.FakeRelayApi
import app.mjolnir.client.JsonFileStore
import app.mjolnir.protocol.DeviceKind
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhoneSessionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val api = FakeRelayApi()

    private fun config(initial: PhoneConfig = PhoneConfig("https://relay.example", deviceName = "Pixel 8")) =
        JsonFileStore(File(tmp.root, "config.json"), serializer<PhoneConfig>()) { initial }

    @Test fun registersOnceWithDeviceName() = runTest {
        val config = config()
        val session = PhoneSession(api, config)
        val first = session.ensureRegistered()
        session.ensureRegistered()
        assertEquals(listOf("Pixel 8" to DeviceKind.PHONE), api.registerCalls)
        assertEquals(api.registerResult.token, first.token)
        assertEquals(api.registerResult.deviceId, config.read().deviceId)
    }

    @Test fun resetQuietlySwallowsRelayAndIoFailures() = runTest {
        api.failAllWith = app.mjolnir.client.RelayException(app.mjolnir.client.RelayErrorKind.NETWORK, "down")
        resetQuietly(PhoneSession(api, config())) // relay failure: no throw
        val unreadable = File(tmp.root, "dir-config.json").apply { mkdirs() } // reading a directory is an IOException
        resetQuietly(PhoneSession(api, JsonFileStore(unreadable, serializer<PhoneConfig>()) { PhoneConfig("https://relay.example", deviceName = "P") }))
    }

    @Test fun resetClearsTargetAndReRegisters() = runTest {
        val config = config(PhoneConfig("https://relay.example", "old", "old-tok", "Pixel 8", targetPcId = "pc1"))
        val reset = PhoneSession(api, config).reset()
        assertNull(reset.targetPcId)
        assertEquals(api.registerResult.token, reset.token)
        assertEquals(api.registerResult.token, config.read().token)
        assertEquals(1, api.registerCalls.size)
    }
}
