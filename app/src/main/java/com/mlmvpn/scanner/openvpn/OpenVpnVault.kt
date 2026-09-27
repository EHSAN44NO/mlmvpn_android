package com.mlmvpn.scanner.openvpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Fail closed. Files are outside Android backup; filenames bind GCM ciphertext to its owner. */
class OpenVpnVault(context: Context) {
    private val directory = File(context.noBackupFilesDir, "openvpn").apply { mkdirs() }
    private val alias = "mlmvpn_openvpn_v1"

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun file(name: String): AtomicFile {
        require(name.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        return AtomicFile(File(directory, name))
    }
    fun read(name: String): String? {
        val target = file(name)
        if (!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) return null
        val data = target.openRead().use { it.readBytes() }
        require(data.size in 29..16_777_216 && data[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, 12))
        cipher.updateAAD(name.toByteArray())
        return cipher.doFinal(data, 13, data.size - 13).toString(Charsets.UTF_8)
    }
    fun write(name: String, plaintext: String) {
        require(plaintext.toByteArray().size <= 16_777_187) { "Encrypted store size limit reached" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray())
        val data = byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext.toByteArray())
        require(data.size <= 16_777_216)
        val target = file(name)
        val stream = target.startWrite()
        try { stream.write(data); target.finishWrite(stream) }
        catch (e: Exception) { target.failWrite(stream); throw e }
    }
    fun remove(name: String) { file(name).delete() }
}
