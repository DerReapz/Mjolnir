package app.mjolnir.desktop

import app.mjolnir.client.JsonFileStore
import app.mjolnir.client.RelayApi
import app.mjolnir.protocol.DeviceKind

suspend fun ensureRegistered(store: JsonFileStore<DesktopConfig>, api: RelayApi): DesktopConfig {
    val config = store.read()
    if (config.token != null && config.deviceId != null) return config
    val r = api.register(config.deviceName, DeviceKind.PC)
    return store.update { it.copy(deviceId = r.deviceId, token = r.token) }
}

/** Forgets this device's identity and pairing, then registers anew. */
suspend fun reRegister(store: JsonFileStore<DesktopConfig>, api: RelayApi): DesktopConfig {
    store.update { it.copy(token = null, deviceId = null, lastPhoneId = null) }
    return ensureRegistered(store, api)
}
