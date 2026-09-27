package com.mlmvpn.core.geph

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A Geph account, in the two shapes the network accepts.
 *
 * Geph 5 accounts are a 24-digit "account code" (the network calls it a secret) made by a
 * proof-of-work puzzle, with no e-mail or password behind it. Accounts from Geph 4 are a username
 * and password; the network still lets those log in, and still has them, so they are kept rather
 * than turned away.
 */
sealed class GephCredential {
    data class Secret(val secret: String) : GephCredential()
    data class Legacy(val username: String, val password: String) : GephCredential()

    /** `credentials` exactly as geph5-client's config and the broker's RPCs spell it. */
    fun toJson(): JSONObject = when (this) {
        is Secret -> JSONObject().put("secret", secret)
        is Legacy -> JSONObject().put(
            "legacy_username_password",
            JSONObject().put("username", username).put("password", password),
        )
    }

    /**
     * Names this account's cache file. Two accounts must never share one: the engine keeps its
     * auth token under a fixed key inside it (geph5-app supervisor.rs keys it per secret too).
     */
    fun tag(): String = sha256Hex(toJson().toString()).take(32)
}

/** Where the exit is chosen: automatic, a country, or one city in it. */
data class GephExitChoice(val country: String?, val city: String?) {
    val isAuto: Boolean get() = country.isNullOrBlank()

    companion object {
        val AUTO = GephExitChoice(null, null)
    }
}

/** A local TCP listener the engine forwards to [connect] through the tunnel. */
data class GephForward(val listen: String, val connect: String)

/** Everything one engine process is asked to do. */
data class GephRun(
    /** Null runs with the network's dummy credential -- the query engine, which has no account. */
    val credential: GephCredential?,
    val exit: GephExitChoice,
    /** Only the control socket, no tunnel: for account work and the exit list. */
    val dryRun: Boolean,
    val controlSocket: String,
    /** A FILE, handed straight to SQLite. A directory makes the engine exit a second in. */
    val cacheFile: String,
    val socks5: String? = null,
    val http: String? = null,
    val pac: String? = null,
    val allowDirect: Boolean = false,
    val allowLan: Boolean = true,
    val spoofDns: Boolean = false,
    val blockAds: Boolean = false,
    val blockAdult: Boolean = false,
    val forwards: List<GephForward> = emptyList(),
)

/**
 * The geph5-client config, built field by field.
 *
 * The engine's `Config` is `#[serde(deny_unknown_fields)]` (libraries/geph5-misc-rpc/src/
 * client_config.rs), so a stray key is a fatal startup error rather than something ignored --
 * every key written here is one of that struct's, and a test pins the set. It is written as JSON,
 * which is valid YAML, to avoid any quoting trap.
 */
object GephConfig {

    /** The keys geph5-client's Config accepts that this app writes. Pinned by GephConfigTest. */
    val KEYS = listOf(
        "socks5_listen", "http_proxy_listen", "pac_listen", "control_listen", "control_listen_unix",
        "exit_constraint", "allow_direct", "cache", "broker", "tunneled_broker", "broker_keys",
        "port_forward", "spoof_dns", "passthrough_china", "allow_lan", "dry_run", "credentials",
        "sess_metadata", "task_limit",
    )

    fun build(run: GephRun): JSONObject = JSONObject().apply {
        put("socks5_listen", run.socks5 ?: JSONObject.NULL)
        put("http_proxy_listen", run.http ?: JSONObject.NULL)
        put("pac_listen", run.pac ?: JSONObject.NULL)
        // Never a TCP control port: it is unauthenticated, and any app on the phone could then
        // call `stop` or read `recent_logs`. A unix socket in our own data directory cannot be
        // reached by anything but this app.
        put("control_listen", JSONObject.NULL)
        put("control_listen_unix", run.controlSocket)
        put("exit_constraint", exitConstraint(run.exit))
        put("allow_direct", run.allowDirect)
        put("cache", run.cacheFile)
        put("broker", broker())
        put("tunneled_broker", JSONObject().put("direct", "https://broker.geph.io"))
        put("broker_keys", brokerKeys())
        put("port_forward", JSONArray().apply {
            run.forwards.forEach { put(JSONObject().put("listen", it.listen).put("connect", it.connect)) }
        })
        put("spoof_dns", run.spoofDns)
        // China's own sites direct -- meaningless here, and it would also switch spoof_dns on in
        // the official apps. Off.
        put("passthrough_china", false)
        put("allow_lan", run.allowLan)
        put("dry_run", run.dryRun)
        put("credentials", run.credential?.toJson() ?: JSONObject().put("secret", ""))
        // The exit reads only `filter` from the session metadata (geph5-exit proxy.rs); the
        // official GUI sends exactly this shape (gephgui user.ts, startDaemonArgs).
        put(
            "sess_metadata",
            JSONObject().put("filter", JSONObject().put("nsfw", run.blockAdult).put("ads", run.blockAds)),
        )
        // A task limit CANCELS the oldest connections when exceeded (taskpool.rs) -- it exists for
        // iOS memory limits. On a phone with room to spare it would only cut live downloads.
        put("task_limit", JSONObject.NULL)
    }

    fun exitConstraint(exit: GephExitChoice): Any = when {
        exit.country.isNullOrBlank() -> "auto"
        exit.city.isNullOrBlank() -> JSONObject().put("country", exit.country.uppercase())
        else -> JSONObject().put("country_city", JSONArray().put(exit.country.uppercase()).put(exit.city))
    }

    /**
     * How the broker is reached, verbatim from geph5's binaries/geph5-app/default-config.yaml --
     * the block every official app embeds. Four paths raced at once, each keyed by how many
     * milliseconds after the start it joins: an AWS Lambda bouncer (the binary is built with the
     * aws_lambda feature for it), two routes fronted behind kubernetes.io, one behind cdn77.com.
     * There is no single address to block. If the network ever stops answering, diff this against
     * upstream first.
     */
    fun broker(): JSONObject = JSONObject().put(
        "priority_race",
        JSONObject()
            .put(
                "2500",
                JSONObject().put(
                    "aws_lambda",
                    JSONObject()
                        .put("function_name", "geph-lambda-bouncer")
                        .put("region", "us-east-1")
                        .put(
                            "obfs_key",
                            "855MJGAMB58MCPJBB97NADJ36D64WM2T:C4TN2M1H68VNMRVCCH57GDV2C5VN6V3RB8QMWP235D0P4RT2ACV7GVTRCHX3EC37",
                        ),
                ),
            )
            .put(
                "1500",
                JSONObject().put(
                    "fronted",
                    JSONObject()
                        .put("front", "https://kubernetes.io/")
                        .put("host", "svitania-naidallszei-2.netlify.app")
                        .put("override_dns", JSONArray().put("75.2.60.5:443")),
                ),
            )
            .put(
                "500",
                JSONObject().put(
                    "fronted",
                    JSONObject()
                        .put("front", "https://kubernetes.io/")
                        .put("host", "svitania-naidallszei-2.netlify.app"),
                ),
            )
            .put(
                "0",
                JSONObject().put(
                    "fronted",
                    JSONObject()
                        .put("front", "https://www.cdn77.com/")
                        .put("host", "1826209743.rsc.cdn77.org"),
                ),
            ),
    )

    /** The keys that verify what the broker signs -- same source as [broker]. */
    fun brokerKeys(): JSONObject = JSONObject()
        .put("master", "88c1d2d4197bed815b01a22cadfc6c35aa246dddb553682037a118aebfaa3954")
        .put("mizaru_free", "0558216cbab7a9c46f298f4c26e171add9af87d0694988b8a8fe52ee932aa754")
        .put("mizaru_plus", "cf6f58868c6d9459b3a63bc2bd86165631b3e916bad7f62b578cd9614e0bcb3b")
        .put(
            "mizaru_bw",
            "3082010a0282010100d0ae53a794ea37bf2e100cb3a872177ec6c11e8375fdcbf92960ce0293465674eb1426a1841b7622a58979a5ff3f8aa2301a621545e9b90bb39d1a6bfda19d6ca1aae74a3192ddfd2b9558eb652c3c2c22f42bdde272852fb67d93cae5846213512c474bf799844aee019bf718f6fa64223be06364459fc8dec66796b141d450d730c4fffe1cac7df8f05591560afa44bcf274f6c0e2303b39c21ab09d19b459ee594512b8341f3d407c026e2509f42c6d89f82f6a3a36fd5c05ad423cd99ad39089403eb9122ea60ef6648afff65438e8e26ce41fa55b9b18741965c77a627bae947bd38fc345e9adab42d6c458f6e194e4232cfd3f04924d5a5e932fe769610203010001",
        )
}

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
