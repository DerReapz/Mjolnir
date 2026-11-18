package app.mjolnir.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionHubTest {
    @Test fun concurrentAddAndRemoveNeverDetachTheLiveSession() = runBlocking {
        val r = Registry<Any>()
        repeat(2000) {
            val old = Any(); val live = Any()
            r.add("d", old)
            val a = launch(Dispatchers.Default) { r.remove("d", old) }
            val b = launch(Dispatchers.Default) { r.add("d", live) }
            a.join(); b.join()
            assertTrue(r.has("d")); assertTrue(live in r.all("d"))
            r.remove("d", live); assertFalse(r.has("d"))
        }
    }
}
