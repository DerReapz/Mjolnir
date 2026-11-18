package app.mjolnir.android.throwing

import kotlin.math.hypot
import kotlin.test.assertTrue
import org.junit.Test

class GravityRemoverTest {
    @Test fun steadyGravityIsRemoved() { val g = GravityRemover(); repeat(50) { g.linear(0f, 0f, 9.81f) }
        assertTrue(g.linear(0f, 0f, 9.81f).let { hypot(hypot(it[0], it[1]), it[2]) } < 0.5f) }
    @Test fun suddenMotionPassesThrough() { val g = GravityRemover(); repeat(50) { g.linear(0f, 0f, 9.81f) }
        assertTrue(g.linear(20f, 0f, 9.81f)[0] in 15f..17f) }
}
