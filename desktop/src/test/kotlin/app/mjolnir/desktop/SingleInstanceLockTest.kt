package app.mjolnir.desktop

import java.io.File
import kotlin.test.*
import org.junit.jupiter.api.io.TempDir

class SingleInstanceLockTest {
    // second acquire in the same JVM fails until the first lock is closed
    @Test fun secondAcquireFailsUntilFirstCloses(@TempDir dir: File) {
        val first = assertNotNull(SingleInstanceLock.tryAcquire(dir)); assertNull(SingleInstanceLock.tryAcquire(dir))
        first.close(); assertNotNull(SingleInstanceLock.tryAcquire(dir)).close()
    }
}
