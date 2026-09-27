package com.mlmvpn.scanner.engines.github

import android.content.Context
import com.mlmvpn.scanner.data.CloudAuth
import com.mlmvpn.scanner.models.CloudAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * «سرویس شبکهٔ امن» — the relay Worker on the user's own Cloudflare account.
 *
 * The same script the Windows app deploys (assets/gt/broker_worker.js, copied from
 * cloudflare-worker/gt-broker by scripts/sync-gt-assets.js). Its /p/ route carries this app's
 * tunnel to the session's `*.trycloudflare.com` quick tunnels, which are blocked from Iran by name;
 * the Worker's own workers.dev name opens with a clean Cloudflare address.
 *
 * Under its OWN name, not the Windows app's `gt-relay-svc`: each installation signs its passes with
 * its own secret, so the two apps sharing one Worker would overwrite each other's secret on every
 * redeploy and break the other's sessions. A neutral name (no «vpn» in it — Cloudflare treats such
 * names as higher risk), made once and kept.
 */
object GtBroker {
    /** A Worker older than this lacks the /p/ passthrough the tunnel rides. */
    const val REQUIRED_VERSION = 3

    private val JSON_TYPE = "application/json".toMediaType()

    fun workerVersion(context: Context): Int = try {
        val m = Regex("WORKER_VERSION\\s*=\\s*(\\d+)").find(script(context))
        m?.groupValues?.get(1)?.toInt() ?: 0
    } catch (_: Exception) { 0 }

    private fun script(context: Context): String =
        com.mlmvpn.scanner.store.StoreFiles.open(context, "gt/broker_worker.js").bufferedReader().use { it.readText() }

    private fun api(account: CloudAccount, method: String, endpoint: String, body: JSONObject? = null): JSONObject {
        val req = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}$endpoint")
            .headers(CloudAuth.headers(account))
            .method(method, body?.toString()?.toRequestBody(JSON_TYPE) ?: if (method == "GET") null else "{}".toRequestBody(JSON_TYPE))
            .build()
        GtGithub.http.newCall(req).execute().use { res ->
            val d = try { JSONObject(res.body?.string().orEmpty()) } catch (_: Exception) { JSONObject() }
            if (!res.isSuccessful || !d.optBoolean("success")) {
                val msg = d.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
                throw IllegalStateException(msg.ifBlank { "Cloudflare API error (${res.code})" })
            }
            return d
        }
    }

    /**
     * Upload (or update) the Worker with this install's signing secret, make sure it is on
     * workers.dev, and return its URL. `log` gets one line per step.
     */
    fun deploy(context: Context, store: GtStore, account: CloudAccount, log: (String) -> Unit): GtBrokerState {
        require(account.accountId.isNotBlank()) { "Cloudflare account id is missing" }
        val prev = store.broker()
        val name = prev.workerName.ifBlank { com.mlmvpn.scanner.utils.AntiDpi.generateSafeWorkerName() }

        log("upload")
        val metadata = JSONObject()
            .put("main_module", "worker.js")
            .put("compatibility_date", "2024-11-01")
            .put("bindings", JSONArray().put(JSONObject()
                .put("type", "secret_text").put("name", "GT_SIGNING_SECRET").put("text", store.installSecret())))
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "metadata.json", metadata.toString().toRequestBody(JSON_TYPE))
            .addFormDataPart("worker.js", "worker.js", script(context).toRequestBody("application/javascript+module".toMediaType()))
            .build()
        val upload = Request.Builder()
            .url("https://api.cloudflare.com/client/v4/accounts/${account.accountId}/workers/scripts/$name")
            .headers(CloudAuth.headers(account).newBuilder().removeAll("Content-Type").build())
            .put(body)
            .build()
        GtGithub.http.newCall(upload).execute().use { res ->
            val d = try { JSONObject(res.body?.string().orEmpty()) } catch (_: Exception) { JSONObject() }
            if (!res.isSuccessful || !d.optBoolean("success")) {
                val msg = d.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
                throw IllegalStateException(msg.ifBlank { "Worker upload failed (${res.code})" })
            }
        }

        log("subdomain")
        var sub = try { api(account, "GET", "/workers/subdomain").optJSONObject("result")?.optString("subdomain").orEmpty() } catch (_: Exception) { "" }
        if (sub.isBlank()) {
            val fresh = com.mlmvpn.scanner.utils.AntiDpi.generateSafeSubdomain()
            api(account, "PUT", "/workers/subdomain", JSONObject().put("subdomain", fresh))
            sub = fresh
        }
        api(account, "POST", "/workers/scripts/$name/subdomain", JSONObject().put("enabled", true))

        val state = prev.copy(
            workerName = name,
            url = "https://$name.$sub.workers.dev",
            cloudAccountId = account.id,
            cloudAccountName = account.name.ifBlank { account.email },
            version = workerVersion(context),
            deployedAt = System.currentTimeMillis(),
        )
        store.saveBroker(state)
        log("done")
        return state
    }
}
