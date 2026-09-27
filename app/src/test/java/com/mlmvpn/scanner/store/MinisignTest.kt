package com.mlmvpn.scanner.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trust chain in front of the Geph engine the store installs: BLAKE2b, minisign, and the one
 * manifest block it reads. The fixtures are a real `metadata.yaml` and its `.minisig`, fetched
 * together from Geph's mirror, so the check is the developer's own signature, not one made here.
 */
class MinisignTest {

    private val gephKey = "RWSzEWRCN0AaNpPj+yw0zbOI87jI8PNpnCoITCroKQxRAANAzUawpph7"

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("geph/$name")!!.use { it.readBytes() }

    @Test
    fun blake2bMatchesTheRfcVectors() {
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            hex(Blake2b.digest512(ByteArray(0))),
        )
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d17d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(Blake2b.digest512("abc".toByteArray())),
        )
    }

    @Test
    fun blake2bHandlesExactAndMultipleBlocks() {
        // Reference values from Node's crypto ('blake2b512').
        assertEquals(
            "2319e3789c47e2daa5fe807f61bec2a1a6537fa03f19ff32e87eecbfd64b7e0e8ccff439ac333b040f19b0c4ddd11a61e24ac1fe0f10a039806c5dcc0da3d115",
            hex(Blake2b.digest512(ByteArray(128) { it.toByte() })),
        )
        assertEquals(
            "d9cf5983dc6b34c0fa1f0226926855ad3eccd2bcdcd8f8053b9a80664d33b5afcc32fd21c70ea14f4ef50ca97c3203c4d1803159f0e01bb6cb1d1c83db52b63c",
            hex(Blake2b.digest512(ByteArray(300) { (it and 255).toByte() })),
        )
    }

    @Test
    fun gephsOwnManifestVerifies() {
        val yaml = fixture("metadata.yaml")
        val sig = String(fixture("metadata.yaml.minisig"), Charsets.UTF_8)
        assertTrue(Minisign.verify(yaml, sig, gephKey))
    }

    @Test
    fun oneChangedByteFailsIt() {
        val yaml = fixture("metadata.yaml").clone()
        yaml[yaml.size / 2] = (yaml[yaml.size / 2] + 1).toByte()
        val sig = String(fixture("metadata.yaml.minisig"), Charsets.UTF_8)
        assertFalse(Minisign.verify(yaml, sig, gephKey))
    }

    @Test
    fun aSwappedTrustedCommentFailsIt() {
        val yaml = fixture("metadata.yaml")
        val sig = String(fixture("metadata.yaml.minisig"), Charsets.UTF_8)
            .replace("trusted comment: timestamp:", "trusted comment: timestamp:9")
        assertFalse(Minisign.verify(yaml, sig, gephKey))
    }

    @Test
    fun theAndroidTrackIsRead() {
        val e = SignedManifestYaml.entry(String(fixture("metadata.yaml"), Charsets.UTF_8), "android-stable")
        assertNotNull(e)
        assertEquals("geph-android.apk", e!!.filename)
        assertEquals(64, e.sha256.length)
        assertTrue(e.version.matches(Regex("""\d+\.\d+.*""")))
    }

    @Test
    fun base64DecodesLikeTheStandard() {
        assertEquals("hello world", String(Minisign.decode("aGVsbG8gd29ybGQ="), Charsets.UTF_8))
        assertEquals(42, Minisign.decode(gephKey).size)
    }
}
