package app.mjolnir.protocol

import kotlin.test.*

class FilesTest {
    @Test fun keepsOnlyTheLastPathSegment() {
        assertEquals("evil.bat", safeFileName("..\\..\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\evil.bat"))
        assertEquals("passwd", safeFileName("../../etc/passwd"))
    }
    @Test fun neutralizesWindowsHazards() {
        assertEquals("_CON.txt", safeFileName("CON.txt")); assertEquals("_lpt1", safeFileName("lpt1"))
        assertEquals("a_b_c.txt", safeFileName("a<b>c.txt")); assertEquals("report", safeFileName("report. . "))
        assertEquals("file", safeFileName("")); assertEquals("file", safeFileName("..."))
        val long = safeFileName("x".repeat(300) + ".pdf"); assertEquals(200, long.length); assertTrue(long.endsWith(".pdf"))
    }
    @Test fun relativePathsStayRelativeAndSafe() {
        assertEquals("Photos/2024/a.jpg", safeRelativePath("Photos/2024/a.jpg"))
        assertEquals("Windows/System32/x.dll", safeRelativePath("..\\..\\Windows\\System32\\x.dll"))
        assertEquals("etc/passwd", safeRelativePath("/etc/passwd")); assertEquals("x/y.txt", safeRelativePath("./x/../y.txt"))
        assertEquals("a/_CON/b.txt", safeRelativePath("a/CON/b.txt")); assertEquals("file", safeRelativePath(""))
        assertEquals(16, safeRelativePath((1..20).joinToString("/") { "d$it" }).split("/").size)
    }
    @Test fun topFolderIsFirstSegment() { assertEquals("Photos", topFolder("Photos/a.jpg")); assertNull(topFolder("a.jpg")) }
}
