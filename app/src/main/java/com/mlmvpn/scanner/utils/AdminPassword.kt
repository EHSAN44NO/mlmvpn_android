package com.mlmvpn.scanner.utils

import com.mlmvpn.scanner.models.CloudAccount
import java.security.SecureRandom

/**
 * The password the legacy panel binding (`ADMIN_PASSWORD`) is deployed with.
 *
 * It used to default to `admin`, and a worker whose panel answered to `admin` was taken over by the
 * bots that sweep workers.dev for default panels -- they made users and ran their own traffic through
 * the account, which is how an abuse report could arrive minutes after a deploy with nobody using it.
 * Now an empty or default password is replaced by a random one and stored on the account, so the
 * app's own legacy screen keeps working and nobody else can guess it.
 */
object AdminPassword {
    private val random = SecureRandom()
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun ensure(account: CloudAccount): String {
        val current = account.mlmAdminPassword
        if (!current.isNullOrEmpty() && current != "admin") return current
        val fresh = (1..24).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        account.mlmAdminPassword = fresh
        return fresh
    }
}
