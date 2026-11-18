package app.mjolnir.android.data

import app.mjolnir.android.throwing.Sensitivity
import kotlinx.serialization.Serializable

@Serializable
enum class LargeFilePolicy { WAIT_FOR_WIFI, USE_MOBILE_DATA }

@Serializable
data class PhoneConfig(
    val relayUrl: String,
    val deviceId: String? = null,
    val token: String? = null,
    val deviceName: String,
    val targetPcId: String? = null,
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val largeFilePolicy: LargeFilePolicy = LargeFilePolicy.WAIT_FOR_WIFI,
    val largeFileThresholdMb: Int = 25,
)
