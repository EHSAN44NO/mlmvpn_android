package com.mlmvpn.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUsageTest {

    @Test
    fun `the header's figures are read, in bytes and milliseconds`() {
        val u = SubscriptionUsage.parse("upload=0; download=157286400; total=1073741824; expire=1790000000")!!
        assertEquals(157286400L, u.usedBytes)
        assertEquals(1073741824L, u.totalBytes)
        assertEquals(1790000000L * 1000L, u.expireAt)
        assertEquals(1073741824L - 157286400L, u.leftBytes)
    }

    @Test
    fun `zero means unlimited and never, as the panels mean it`() {
        val u = SubscriptionUsage.parse("upload=10; download=20; total=0; expire=0")!!
        assertEquals(30L, u.usedBytes)
        assertNull(u.totalBytes)
        assertNull(u.expireAt)
        assertNull(u.leftBytes)
    }

    @Test
    fun `a used-up volume is zero left, never negative`() {
        assertEquals(0L, SubscriptionUsage.parse("download=2000; total=1000")!!.leftBytes)
    }

    @Test
    fun `nothing usable is no usage`() {
        assertNull(SubscriptionUsage.parse(null))
        assertNull(SubscriptionUsage.parse(""))
        assertNull(SubscriptionUsage.parse("hello"))
    }

    @Test
    fun `Config Studio's info entries are told apart from servers`() {
        val info = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:1?encryption=none&security=none&type=tcp#" +
            java.net.URLEncoder.encode("حجم باقی‌مانده: ۸۷۴ مگابایت از ۱ گیگابایت", "UTF-8").replace("+", "%20")
        assertTrue(SubscriptionUsage.isInfoEntry(info))
        assertEquals("حجم باقی‌مانده: ۸۷۴ مگابایت از ۱ گیگابایت", SubscriptionUsage.infoText(info))
        assertFalse(SubscriptionUsage.isInfoEntry("vless://0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0@1.2.3.4:443?type=ws#x"))
        assertFalse(SubscriptionUsage.isInfoEntry("vless://00000000-0000-0000-0000-000000000000@1.2.3.4:443?type=ws#x"))
    }
}
