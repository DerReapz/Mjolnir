package app.mjolnir.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LicencesTest {
    @Test fun extractsTheBundledFontLicences(@TempDir dir: File) {
        val out = extractLicences(File(dir, "licences"))
        assertEquals(listOf("OFL-Inter.txt", "OFL-SpaceGrotesk.txt"), out.list()!!.sorted())
        assertTrue(File(out, "OFL-Inter.txt").readText().contains("SIL OPEN FONT LICENSE"))
    }
}
