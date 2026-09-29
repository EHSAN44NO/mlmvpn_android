package com.mlmvpn.scanner.engines.mae.egress

import android.content.Context
import com.mlmvpn.scanner.data.SecureStore
import com.mlmvpn.scanner.engines.cloud.CfWorkers
import com.mlmvpn.scanner.engines.mae.store.EgressWorker
import com.mlmvpn.scanner.models.CloudAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Puts the MAE egress Worker on the user's OWN Cloudflare account -- MLMVPN runs no server for it.
 *
 * Idempotent (a fixed script name: re-running updates in place), versioned (the version the app
 * ships is compared with the one deployed and upgraded when newer), removable, and it keeps the
 * access UUID sealed with the Keystore. The Cloudflare credential itself never leaves CloudManager
 * and is only sent to api.cloudflare.com by CfWorkers.
 */
object MaeEgressDeployer {
    const val SCRIPT = "mlm-mae-egress"
    const val VERSION = 1
    private const val ASSET = "mae_egress_worker.js"

    fun needsUpgrade(w: EgressWorker?) = w == null || w.version < VERSION

    /** Deploys or upgrades; keeps the existing UUID on upgrade so nothing else changes. */
    suspend fun deploy(context: Context, account: CloudAccount, existing: EgressWorker?): EgressWorker = withContext(Dispatchers.IO) {
        val uuid = existing?.takeIf { it.accountId == account.accountId }?.let { SecureStore.open(it.uuidSealed) }
            ?: UUID.randomUUID().toString()
        val sealed = SecureStore.seal(uuid) ?: error("secure storage is unavailable on this device")
        val code = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        CfWorkers.upload(account, SCRIPT, code, listOf(CfWorkers.secret("MAE_UUID", uuid)))
        CfWorkers.enableWorkersDev(account, SCRIPT)
        val sub = CfWorkers.subdomain(account)
        EgressWorker(account.accountId, SCRIPT, "$SCRIPT.$sub.workers.dev", sealed, VERSION)
    }

    suspend fun remove(account: CloudAccount) = withContext(Dispatchers.IO) {
        CfWorkers.remove(account, SCRIPT)
    }

    /** The plain UUID for building the outbound, or null when the sealed copy cannot be opened. */
    fun uuidOf(w: EgressWorker): String? = SecureStore.open(w.uuidSealed)
}
