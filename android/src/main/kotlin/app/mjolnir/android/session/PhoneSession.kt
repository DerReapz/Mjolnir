package app.mjolnir.android.session

import app.mjolnir.android.data.PhoneConfig
import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.RelayApi
import app.mjolnir.protocol.DeviceKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Registers this phone with the relay (once) and re-registers after a reset. */
class PhoneSession(private val api: RelayApi, private val config: JsonFileStore<PhoneConfig>) {
    private val lock = Mutex()

    suspend fun ensureRegistered(): PhoneConfig = lock.withLock {
        val current = config.read()
        if (current.token != null && current.deviceId != null) current else registerLocked()
    }

    /** Forgets the credentials and the chosen PC, then registers again. */
    suspend fun reset(): PhoneConfig = lock.withLock {
        config.update { it.copy(deviceId = null, token = null, targetPcId = null) }
        registerLocked()
    }

    private suspend fun registerLocked(): PhoneConfig {
        val name = config.read().deviceName
        val response = api.register(name, DeviceKind.PHONE)
        return config.update { it.copy(deviceId = response.deviceId, token = response.token) }
    }
}
