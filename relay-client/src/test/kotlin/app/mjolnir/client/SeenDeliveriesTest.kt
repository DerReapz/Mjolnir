package app.mjolnir.client

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeenDeliveriesTest {
    @Test fun persistsAcrossInstancesAndEvictsOldest() {
        val f = File(createTempDirectory("seen").toFile(), "seen.json")
        val a = FileSeenDeliveries(f, capacity = 3)
        listOf("a", "b", "c", "d").forEach { a.add(it) }
        val b = FileSeenDeliveries(f, capacity = 3)
        assertFalse(b.contains("a")); assertTrue(b.contains("d"))
    }
}
