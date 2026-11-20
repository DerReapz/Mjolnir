package app.mjolnir.desktop

import java.io.File
import kotlin.test.*

class LaunchArgsTest {
    @Test fun noFlagIsAForegroundStart() {
        val a = parseLaunchArgs(arrayOf("a.txt", " "))
        assertFalse(a.background)
        assertEquals(listOf(File("a.txt").absolutePath), a.paths)
    }

    @Test fun backgroundFlagIsRecognisedAndNotAPath() {
        val a = parseLaunchArgs(arrayOf(BACKGROUND_FLAG))
        assertTrue(a.background)
        assertTrue(a.paths.isEmpty())
    }

    @Test fun coldStartShowsTheWindowUnlessInBackground() {
        assertTrue(showWindowAtStart(background = false, needsRelay = false)) // Start menu
        assertFalse(showWindowAtStart(background = true, needsRelay = false)) // sign-in
        assertTrue(showWindowAtStart(background = true, needsRelay = true)) // nothing to run on yet: Settings
    }
}
