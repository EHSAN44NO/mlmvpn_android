package com.mlmvpn.scanner.data

/**
 * What the user is optimising for, and what that actually changes.
 *
 * These are not three labels on one algorithm. Each one probes differently, admits candidates
 * differently, and verifies differently, so the three genuinely produce different IPs:
 *
 * | | probe | admission | verify |
 * |---|---|---|---|
 * | [FAST_SCAN] | 1 ping, 600ms, 256 wide | first-come, straight through | 8 at once, 1 pass |
 * | [FAST_CONFIG] | 3 pings, 1000ms, 192 wide | buffered, lowest latency first | 5 at once, 1 pass |
 * | [STABLE] | 5 pings, 1200ms, 128 wide | zero loss and low jitter only | 3 at once, 2 passes, worst kept |
 *
 * The trade is real in both directions: FAST_SCAN finishes first and hands back whatever answered
 * first; STABLE takes several times as long and returns fewer IPs, but the ones it returns held up
 * across five probes and two full proxied requests.
 */
enum class ScanStrategy {
    /** Finish as soon as possible. Anything that opens goes straight to verification. */
    FAST_SCAN,

    /** Find the lowest-latency exits. Candidates queue and the best-known one is verified next. */
    FAST_CONFIG,

    /** Find exits that do not flap. Loss and jitter are disqualifying; every winner is tested twice. */
    STABLE;

    val tuning: ScanTuning
        get() = when (this) {
            FAST_SCAN -> ScanTuning(
                pingTimes = 1,
                tcpTimeoutMs = 600,
                probeConcurrency = 256,
                verifyConcurrency = 8,
                verifyPasses = 1,
                // No admission bar at all: on a blocked network almost nothing opens, and refusing
                // the few that do would turn "fast" into "empty".
                maxLossRate = 1f,
                maxJitterMs = Int.MAX_VALUE,
                // Straight through. Nothing is held back to be sorted.
                admissionWindowMs = 0,
                admissionBatch = 1,
            )

            FAST_CONFIG -> ScanTuning(
                pingTimes = 3,
                tcpTimeoutMs = 1000,
                probeConcurrency = 192,
                // Fewer at once than FAST_SCAN on purpose: a real delay test is a full proxied
                // request, and running eight of them against the same edge inflates every reading.
                // When the number IS the point, the number has to be trustworthy.
                verifyConcurrency = 5,
                verifyPasses = 1,
                maxLossRate = 0.5f,
                maxJitterMs = Int.MAX_VALUE,
                // Hold candidates briefly so the verifier can always take the best one known so
                // far rather than whichever happened to answer first.
                admissionWindowMs = 1200,
                admissionBatch = 16,
            )

            STABLE -> ScanTuning(
                pingTimes = 5,
                tcpTimeoutMs = 1200,
                probeConcurrency = 128,
                verifyConcurrency = 3,
                // Two full passes, and the WORST of them is what gets recorded. One good reading
                // proves nothing about an exit that alternates.
                verifyPasses = 2,
                maxLossRate = 0f,
                maxJitterMs = 60,
                admissionWindowMs = 1500,
                admissionBatch = 12,
            )
        }
}

/** The knobs a strategy sets. Nothing here is cosmetic; every field changes what the scan does. */
data class ScanTuning(
    /** TCP probes per IP. More probes measure jitter and loss; one measures neither. */
    val pingTimes: Int,
    val tcpTimeoutMs: Int,
    /** How many TCP probes run at once. */
    val probeConcurrency: Int,
    /** How many real proxied delay tests run at once. */
    val verifyConcurrency: Int,
    /** How many times each candidate is verified before it is believed. */
    val verifyPasses: Int,
    /** Candidates losing more than this fraction of probes never reach verification. */
    val maxLossRate: Float,
    /** Spread between fastest and slowest probe, above which the exit counts as unstable. */
    val maxJitterMs: Int,
    /** How long the admission queue may hold candidates before releasing the best of them. */
    val admissionWindowMs: Long,
    /** How many candidates the queue gathers before releasing, whichever comes first. */
    val admissionBatch: Int,
)
