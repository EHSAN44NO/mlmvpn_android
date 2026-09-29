package com.mlmvpn.scanner.engines.arena

import android.content.Context
import com.mlmvpn.scanner.data.CloudManager
import com.mlmvpn.scanner.engines.gozargah.GozargahPanel
import com.mlmvpn.scanner.engines.netra.NetraPanel
import com.mlmvpn.scanner.engines.spider.SpiderPanel
import com.mlmvpn.scanner.models.CloudAccount

/**
 * A competitor: one of the user's cloud panels, seen through the functions the Cloud tab already
 * uses to install it and to get its configs. The arena adds no install or update path of its own;
 * a new panel joins by adding one adapter to [ArenaPanels.ALL].
 */
interface ArenaPanel {
    /** The engine code configs of this panel carry (`VpnNode.engineType`), also its colour key. */
    val id: String
    val name: String
    /** The Store item that updates this panel's Worker, when there is one. */
    val storeId: String?
    fun installed(context: Context, account: CloudAccount): Boolean
    suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Unit>
    /** Configs this panel serves right now; the arena qualifies a few and races the fastest. */
    suspend fun candidates(context: Context, account: CloudAccount): List<String>
}

object ArenaPanels {

    /** The user the arena makes in panels that hand configs out per user. */
    const val ARENA_USER = "mlmvpn-arena"

    private fun deployed(s: String?) = s.equals("DEPLOYED", ignoreCase = true)

    private fun save(context: Context) = CloudManager(context).saveAccounts()

    val BPB = object : ArenaPanel {
        override val id = "BPB"; override val name = "BPB"; override val storeId = "bpb"
        override fun installed(context: Context, account: CloudAccount) = deployed(account.status)
        override suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Unit> {
            val (ok, msg) = CloudManager(context).deployWorker(account) { _, s -> onStep(s) }
            if (!ok) return Result.failure(IllegalStateException(msg))
            account.status = "DEPLOYED"; save(context)
            return Result.success(Unit)
        }
        override suspend fun candidates(context: Context, account: CloudAccount) = CloudManager(context).fetchCloudConfigs(account).second
    }

    val EDG = object : ArenaPanel {
        override val id = "EDG"; override val name = "Edge"; override val storeId = "edg"
        override fun installed(context: Context, account: CloudAccount) = deployed(account.edgStatus)
        override suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Unit> {
            val (ok, msg) = CloudManager(context).deployEdgWorker(account) { _, s -> onStep(s) }
            if (!ok) return Result.failure(IllegalStateException(msg))
            account.edgStatus = "DEPLOYED"; save(context)
            return Result.success(Unit)
        }
        override suspend fun candidates(context: Context, account: CloudAccount) = CloudManager(context).fetchEdgConfigs(account).second
    }

    val NAHAN = object : ArenaPanel {
        override val id = "NHN"; override val name = "Nahan"; override val storeId = "nahan"
        override fun installed(context: Context, account: CloudAccount) = deployed(account.nahanStatus)
        override suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Unit> {
            val (ok, msg) = com.mlmvpn.scanner.engines.nahan.NahanDeployer(context).deployNahan(account) { _, s -> onStep(s) }
            if (!ok) return Result.failure(IllegalStateException(msg))
            save(context)
            return Result.success(Unit)
        }
        override suspend fun candidates(context: Context, account: CloudAccount) =
            com.mlmvpn.scanner.engines.nahan.NahanDeployer(context).fetchNahanNodes(account)
    }

    val MLM = object : ArenaPanel {
        override val id = "MLM"; override val name = "MLM"; override val storeId = "studio"
        override fun installed(context: Context, account: CloudAccount) = deployed(account.mlmStatus)
        override suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit): Result<Unit> {
            val (ok, msg) = com.mlmvpn.scanner.engines.mlm.MlmDeployer(context).deployMlm(account) { _, s -> onStep(s) }
            if (!ok) return Result.failure(IllegalStateException(msg))
            account.mlmStatus = "DEPLOYED"; save(context)
            return Result.success(Unit)
        }
        override suspend fun candidates(context: Context, account: CloudAccount): List<String> =
            if (account.isStudioManaged) ArenaStudio.links(context, account) else ArenaStudio.legacyLinks(context, account)
    }

    val SPIDER = object : ArenaPanel {
        override val id = "SPD"; override val name = "Spider"; override val storeId = "spider"
        override fun installed(context: Context, account: CloudAccount) = SpiderPanel.install(context, account.accountId) != null
        override suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit) =
            SpiderPanel.deploy(context, account, onStep).map { }
        override suspend fun candidates(context: Context, account: CloudAccount): List<String> {
            val inst = SpiderPanel.install(context, account.accountId) ?: return emptyList()
            val user = SpiderPanel.users(context, inst).firstOrNull { it.remark == ARENA_USER }
                ?: SpiderPanel.addUser(context, inst, ARENA_USER, 0.0, 0, 0)
            return listOf(SpiderPanel.link(inst, user))
        }
    }

    val NETRA = object : ArenaPanel {
        override val id = "NTR"; override val name = "Netra"; override val storeId = "netra"
        override fun installed(context: Context, account: CloudAccount) = NetraPanel.install(context, account.accountId) != null
        override suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit) =
            NetraPanel.deploy(context, account, onStep).map { }
        override suspend fun candidates(context: Context, account: CloudAccount): List<String> =
            NetraPanel.install(context, account.accountId)?.let { NetraPanel.configs(context, it) }.orEmpty()
    }

    val GOZARGAH = object : ArenaPanel {
        override val id = "GZG"; override val name = "Gozargah"; override val storeId = "gozargah"
        override fun installed(context: Context, account: CloudAccount) = GozargahPanel.install(context, account.accountId) != null
        override suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit) =
            GozargahPanel.deploy(context, account, onStep).map { }
        override suspend fun candidates(context: Context, account: CloudAccount): List<String> =
            GozargahPanel.install(context, account.accountId)?.let { GozargahPanel.configs(context, it) }.orEmpty()
    }

    val NOVA = object : ArenaPanel {
        override val id = "NVA"; override val name = "Nova"; override val storeId = "nova"
        override fun installed(context: Context, account: CloudAccount) = com.mlmvpn.scanner.engines.nova.NovaPanel.install(context, account.accountId) != null
        override suspend fun install(context: Context, account: CloudAccount, onStep: (String) -> Unit) =
            com.mlmvpn.scanner.engines.nova.NovaPanel.deploy(context, account, onStep).map { }
        override suspend fun candidates(context: Context, account: CloudAccount): List<String> =
            com.mlmvpn.scanner.engines.nova.NovaPanel.install(context, account.accountId)?.let { com.mlmvpn.scanner.engines.nova.NovaPanel.configs(context, it) }.orEmpty()
    }

    /** The lineup, in grid order. A new panel is one adapter above and one entry here. */
    val ALL: List<ArenaPanel> = listOf(BPB, EDG, NAHAN, MLM, SPIDER, NETRA, GOZARGAH, NOVA)

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}
