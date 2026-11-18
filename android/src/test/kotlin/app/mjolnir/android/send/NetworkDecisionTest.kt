package app.mjolnir.android.send

import app.mjolnir.android.data.LargeFilePolicy
import kotlin.test.assertEquals
import org.junit.Test

class NetworkDecisionTest {
    @Test fun smallTransfersAlwaysGo() = assertEquals(NetDecision.GO, networkDecision(5_000_000, true, LargeFilePolicy.WAIT_FOR_WIFI, 25, false))
    @Test fun largeOnMobileDataWaits() = assertEquals(NetDecision.WAIT_FOR_WIFI, networkDecision(80_000_000, true, LargeFilePolicy.WAIT_FOR_WIFI, 25, false))
    @Test fun largeOnWifiGoes() = assertEquals(NetDecision.GO, networkDecision(80_000_000, false, LargeFilePolicy.WAIT_FOR_WIFI, 25, false))
    @Test fun mobileDataPolicyGoes() = assertEquals(NetDecision.GO, networkDecision(80_000_000, true, LargeFilePolicy.USE_MOBILE_DATA, 25, false))
    @Test fun oneOffOverrideGoes() = assertEquals(NetDecision.GO, networkDecision(80_000_000, true, LargeFilePolicy.WAIT_FOR_WIFI, 25, true))
    @Test fun exactlyAtThresholdGoes() = assertEquals(NetDecision.GO, networkDecision(25L * 1_048_576, true, LargeFilePolicy.WAIT_FOR_WIFI, 25, false))
    @Test fun oneByteOverThresholdWaits() = assertEquals(NetDecision.WAIT_FOR_WIFI, networkDecision(25L * 1_048_576 + 1, true, LargeFilePolicy.WAIT_FOR_WIFI, 25, false))
}
