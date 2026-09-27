package com.mlmvpn.scanner.openvpn

import org.junit.Assert.*
import org.junit.Test

class OpenVpnPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun aPassedResetMakesOldDepletionUnknownRatherThanInventingNewQuota() {
        val a = Account("a", "a@x", usage = ProviderUsage(100, 100, now - 86_400_000, now - 1000))
        assertTrue(a.usable(now))
        assertEquals(AccountState.USAGE_UNAVAILABLE, a.state(now))
        assertEquals(0L, a.usage!!.remaining)
        assertFalse(AccountPolicy.confirmedUnusable(a, now))
    }

    @Test fun staleZeroWithoutAResetRemainsExcludedUntilProviderEvidenceChanges() {
        val a = Account("a", "a@x", usage = ProviderUsage(100, 100, now - 86_400_000))
        assertFalse(a.usable(now))
    }

    @Test fun depletedAccountIsRetainedButNeverSelected() {
        val empty = Account("a", "empty@example.com", usage = ProviderUsage(100, 100, now))
        val unknown = Account("b", "new@example.com")
        assertFalse(empty.usable(now))
        assertEquals("b", AccountPolicy.best(listOf(empty, unknown), now)?.id)
    }

    @Test fun staleUsageDoesNotAuthorizeAutomaticSwitch() {
        val a = Account("a", "a@example.com", usage = ProviderUsage(100, 100, now - 86_400_000))
        assertFalse(AccountPolicy.confirmedUnusable(a, now))
    }

    @Test fun authenticationFailureIsNotInferredAsQuotaDepleted() {
        val a = Account("a", "a@example.com", auth = AuthState.FAILED)
        assertFalse(a.usable(now))
        assertNull(a.usage)
        assertEquals(AccountState.AUTH_FAILED, a.state(now))
    }

    @Test fun recentlyVerifiedAccountOutranksUnknownLargeStaleQuota() {
        val stale = Account("a", "a@example.com", usage = ProviderUsage(10000, 0, now - 86_400_000))
        val recent = Account("b", "b@example.com", auth = AuthState.ACCEPTED, lastConnected = now - 1000)
        assertEquals("b", AccountPolicy.best(listOf(stale, recent), now)?.id)
    }

    @Test fun switchBudgetNeverRevisitsAnAccountInTheSameChain() {
        val pool = listOf(Account("a", "a@x"), Account("b", "b@x"), Account("c", "c@x"))
        assertEquals("c", AccountPolicy.best(pool, now, setOf("a", "b"))?.id)
        assertNull(AccountPolicy.best(pool, now, setOf("a", "b", "c")))
    }

    @Test fun invalidProviderNumbersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ProviderUsage(10, 11, now) }
        assertThrows(IllegalArgumentException::class.java) { ProviderUsage(-1, 0, now) }
    }

    @Test fun udpReplyMustMatchTheRandomSessionAndPacketId() {
        val session = byteArrayOf(1,2,3,4,5,6,7,8)
        val request = OpenVpnProbePacket.request(session)
        assertEquals(14, request.size)
        val reply = byteArrayOf(0x40,9,8,7,6,5,4,3,2,1,0,0,0,0) + session + byteArrayOf(0,0,0,0)
        assertTrue(OpenVpnProbePacket.accepts(reply, session))
        assertFalse(OpenVpnProbePacket.accepts(reply, byteArrayOf(8,7,6,5,4,3,2,1)))
        assertFalse(OpenVpnProbePacket.accepts(byteArrayOf(0x40), session))
    }
}
