package com.mlmvpn.scanner.openvpn

import org.junit.Assert.*
import org.junit.Test

class OpenVpnProfileTest {
    private fun fixture(name: String) = javaClass.getResource("/openvpn/$name")!!.readText()

    @Test fun quotedCertificateNamesSurviveSavingAndReloading() {
        val p = ProfileImporter.parse("named.ovpn", fixture("Canada.ovpn") + "\nverify-x509-name \"O'Brien\" name", mapOf("openvpn-server-ca.crt" to fixture("ca.crt")))
        assertEquals(p.id, ProfileImporter.parse(p.name, p.config, emptyMap()).id)
    }

    @Test fun invalidCompanionCannotPoisonTheStoredCatalog() {
        assertThrows(ProfileImportException::class.java) {
            ProfileImporter.parse("bad.ovpn", fixture("Canada.ovpn") + "\nkey bad.key", mapOf("openvpn-server-ca.crt" to fixture("ca.crt"), "bad.key" to "abc\u0000def"))
        }
    }

    @Test fun importedWindowsCertificateKeepsTheSameIdAfterReload() {
        val p = ProfileImporter.parse("Canada.ovpn", fixture("Canada.ovpn"), mapOf("openvpn-server-ca.crt" to fixture("ca.crt").replace("\r\n", "\n").replace("\n", "\r\n")))
        assertEquals(p.id, ProfileImporter.parse(p.name, p.config, emptyMap()).id)
    }

    @Test fun expandedProfileCannotExceedItsReloadLimit() {
        val largeKey = "A".repeat(ProfileImporter.MAX_BYTES - 50)
        assertThrows(ProfileImportException::class.java) {
            ProfileImporter.parse("large.ovpn", fixture("Canada.ovpn") + "\nkey private.key", mapOf("openvpn-server-ca.crt" to fixture("ca.crt"), "private.key" to largeKey))
        }
    }

    @Test fun importsTheActualTunnelBearProfileAndItsCa() {
        val p = ProfileImporter.parse("TunnelBear Canada.ovpn", fixture("Canada.ovpn"), mapOf("openvpn-server-ca.crt" to fixture("ca.crt")))
        assertEquals("ca.lazerpenguin.com", p.remotes.single().host)
        assertEquals(443, p.remotes.single().port)
        assertEquals("udp", p.remotes.single().protocol)
        assertTrue(p.config.contains("<ca>"))
        assertTrue(p.config.contains("data-ciphers AES-256-CBC"))
        assertFalse(p.config.contains("ca openvpn-server-ca.crt"))
    }

    @Test fun missingCertificateExplainsDependencyWithoutInventingACa() {
        val e = assertThrows(ProfileImportException::class.java) {
            ProfileImporter.parse("Canada.ovpn", fixture("Canada.ovpn"), emptyMap())
        }
        assertEquals("openvpn-server-ca.crt", e.dependency)
    }

    @Test fun rejectsScriptExecutionAndExternalCredentialFiles() {
        for (line in listOf("up malware.sh", "plugin evil.so", "auth-user-pass passwords.txt", "config other.conf")) {
            assertThrows(ProfileImportException::class.java) {
                ProfileImporter.parse("evil.ovpn", fixture("Canada.ovpn") + "\n$line", mapOf("openvpn-server-ca.crt" to fixture("ca.crt")))
            }
        }
    }

    @Test fun commentsAndLineEndingsDoNotDefeatDuplicateDetection() {
        val ca = mapOf("openvpn-server-ca.crt" to fixture("ca.crt"))
        val p = ProfileImporter.parse("a.ovpn", fixture("Canada.ovpn"), ca)
        val same = ProfileImporter.parse("b.ovpn.txt", "# renamed\n" + fixture("Canada.ovpn").replace("\n", "\r\n"), ca)
        assertEquals(p.id, same.id)
    }

    @Test fun multipleRemotesRetainTheirTransportAndDefaultPort() {
        val cfg = fixture("Canada.ovpn").replace("remote ca.lazerpenguin.com 443", "remote ca.lazerpenguin.com 443\nremote example.com 1194 tcp-client")
        val p = ProfileImporter.parse("two.ovpn", cfg, mapOf("openvpn-server-ca.crt" to fixture("ca.crt")))
        assertEquals(2, p.remotes.size)
        assertEquals("tcp-client", p.remotes[1].protocol)
    }

    @Test fun inlinePrivateKeyNeverAppearsInProfileDescription() {
        val p = ProfileImporter.parse("p.ovpn", fixture("Canada.ovpn") + "\n<key>\nSECRET_PRIVATE_KEY\n</key>", mapOf("openvpn-server-ca.crt" to fixture("ca.crt")))
        assertFalse(p.toString().contains("SECRET_PRIVATE_KEY"))
    }
}
