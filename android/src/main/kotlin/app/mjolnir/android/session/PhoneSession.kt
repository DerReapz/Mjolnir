package app.mjolnir.android.session

import kotlinx.coroutines.CancellationException
import java.io.IOException
import app.mjolnir.client.RelayException
import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.RelayApi
import app.mjolnir.protocol.DeviceKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [PhoneSession.reset] after the relay rejected this device's token, never throwing: a failure to register again
 * (relay or local config I/O) is left for the next use. Cancellation still propagates.
 */
suspend fun resetQuietly(session: PhoneSession) {
    try {
        session.reset()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RelayException) {
    } catch (e: IOException) {
    }
}

/** Registers this phone with the relay (once) and re-registers after a reset. */
class PhoneSession(private val api: RelayApi, private val config: JsonFileStore<PhoneConfig>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Runs when the registration is reset (relay change, or the relay forgot this phone): its pairings are gone. */
    private val onReset: () -> Unit = {},
) {
    private val lock = Mutex()

    suspend fun ensureRegistered(): PhoneConfig = lock.withLock {
        val current = withContext(io) { config.read() }
        if (current.token != null && current.deviceId != null) current else registerLocked()
    }

    /** Forgets the credentials and the chosen PC, then registers again. */
    suspend fun reset(): PhoneConfig = lock.withLock {
        withContext(io) { config.update { it.copy(deviceId = null, token = null, targetPcId = null) } }
        onReset()
        registerLocked()
    }

    private suspend fun registerLocked(): PhoneConfig {
        val name = withContext(io) { config.read().deviceName }
        val response = api.register(name, DeviceKind.PHONE)
        return withContext(io) { config.update { it.copy(deviceId = response.deviceId, token = response.token) } }
    }
}
