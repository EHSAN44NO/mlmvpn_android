package com.mlmvpn.scanner.crash

import com.mlmvpn.scanner.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words that decide how crash reports group and what their issues are called. Two occurrences
 * of one bug must come out the same, and two different bugs must not.
 */
class CrashTextTest {

    @Test
    fun `the header says what kind of report it is`() {
        assertEquals(CrashKind.JVM, CrashText.kindOf("=== MLMVPN crash ===\ntime: x"))
        assertEquals(CrashKind.NATIVE, CrashText.kindOf("=== MLMVPN native crash ===\nerror: native crash"))
        assertEquals(CrashKind.ANR, CrashText.kindOf("=== MLMVPN ANR ===\nerror: ANR (main)"))
        assertEquals(CrashKind.JVM, CrashText.kindOf("something older"))
    }

    @Test
    fun `an ANR is one bug however long it waited and whatever window it was`() {
        val a = CrashText.anrHeadline(
            "com.mlmvpn.scanner",
            "Input dispatching timed out (8a0e2d1 com.mlmvpn.scanner/com.mlmvpn.scanner.MainActivity (server) is not responding. Waited 5001ms for FocusEvent(hasFocus=false))",
        )
        val b = CrashText.anrHeadline(
            "com.mlmvpn.scanner",
            "Input dispatching timed out (f31c09a com.mlmvpn.scanner/com.mlmvpn.scanner.MainActivity (server) is not responding. Waited 5012ms for FocusEvent(hasFocus=false))",
        )
        assertEquals(a, b)
        assertTrue(a, a.startsWith("error: ANR (main): Input dispatching timed out"))
        assertTrue(a, "MainActivity" in a)
    }

    @Test
    fun `a native abort is one bug whatever descriptor and pointer it hit`() {
        fun tombstone(fd: Int, owner: String) = """
            com.mlmvpn.scanner:tun
            SIGABRT
            SI_TKILL
            fdsan: attempted to close file descriptor $fd, expected to be unowned, actually owned by unique_fd $owner
            /apex/com.android.runtime/lib64/bionic/libc.so
            /data/app/~~Xy12==/com.mlmvpn.scanner-Ab3==/lib/arm64/libtun2proxy.so
            tun2proxy::run
        """.trimIndent()
        val a = CrashText.nativeHeadline("com.mlmvpn.scanner:tun", tombstone(87, "0x7a3c2b10"))
        val b = CrashText.nativeHeadline("com.mlmvpn.scanner:tun", tombstone(112, "0x7b00ff28"))
        assertEquals(a, b)
        assertTrue(a, a.startsWith("error: native crash SIGABRT in libtun2proxy.so (:tun): fdsan: attempted to close file descriptor #"))
    }

    @Test
    fun `different libraries are different bugs`() {
        val xray = CrashText.nativeHeadline("com.mlmvpn.scanner", "SIGSEGV\n/data/app/~~a/com.mlmvpn.scanner-b/lib/arm64/libxray.so")
        val wg = CrashText.nativeHeadline("com.mlmvpn.scanner", "SIGSEGV\n/data/app/~~a/com.mlmvpn.scanner-b/lib/arm64/libwg-go.so")
        assertFalse(xray == wg)
        assertEquals("error: native crash SIGSEGV in libxray.so (main)", xray)
    }

    @Test
    fun `a text tombstone reads as well as a binary one`() {
        val text = """
            signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000010
                #00 pc 00000000000a1b2c  /apex/com.android.runtime/lib64/bionic/libc.so (memcpy+12)
                #01 pc 0000000000123456  /data/app/~~Q1==/com.mlmvpn.scanner-Z9==/lib/arm64/libxray.so (Run+88)
        """.trimIndent()
        assertEquals("error: native crash SIGSEGV in libxray.so (main)", CrashText.nativeHeadline("com.mlmvpn.scanner", text))
    }

    @Test
    fun `a binary tombstone is reduced to its readable runs`() {
        val bytes = byteArrayOf(0, 1, 2) + "SIGABRT".toByteArray() + byteArrayOf(0x12, 0) +
            "Abort message: 'boom'".toByteArray() + byteArrayOf(-1, 3) + "ab".toByteArray() + byteArrayOf(0)
        assertEquals("SIGABRT\nAbort message: 'boom'\n", CrashText.readableRuns(bytes))
    }

    @Test
    fun `a JVM report is named by its exception even when a breadcrumb says Error`() {
        val report = """
            === MLMVPN crash ===
            time      : today
            --- breadcrumbs (most recent last) ---
            10:00:00.000  openTab home -> ErrorLog
            --- stack ---
            java.lang.IllegalStateException: no route
            	at androidx.compose.runtime.Composer.crash(Composer.kt:1)
            	at com.mlmvpn.scanner.ui.mae.MaeScreen.render(MaeScreen.kt:42)
        """.trimIndent()
        assertEquals(
            "java.lang.IllegalStateException: no route  |  at com.mlmvpn.scanner.ui.mae.MaeScreen.render(MaeScreen.kt:42)",
            CrashReporter.signatureOf(report),
        )
    }

    @Test
    fun `a native report is named by its headline`() {
        val report = "=== MLMVPN native crash ===\nerror: native crash SIGSEGV in libxray.so (main)\nlast step : openTab home -> mae\n"
        assertEquals("error: native crash SIGSEGV in libxray.so (main)", CrashReporter.signatureOf(report))
    }

    @Test
    fun `older reports keep the signature they always had`() {
        val old = "=== MLMVPN crash ===\njava.lang.NullPointerException: x\n\tat com.mlmvpn.scanner.A.b(A.kt:3)\n"
        assertEquals("java.lang.NullPointerException: x  |  at com.mlmvpn.scanner.A.b(A.kt:3)", CrashReporter.signatureOf(old))
    }
}
