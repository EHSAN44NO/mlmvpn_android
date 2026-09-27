package com.mlmvpn.scanner.data.studio.config

import com.mlmvpn.scanner.data.studio.api.StudioError
import com.mlmvpn.scanner.data.studio.api.StudioHttpApi
import com.mlmvpn.scanner.data.studio.api.StudioResult
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.data.studio.domain.ExitCountries
import com.mlmvpn.scanner.data.studio.domain.StudioConfig
import com.mlmvpn.scanner.data.studio.domain.StudioUser

/**
 * The configs one person is given: every chosen protocol, once leaving directly and once per chosen
 * country («لوکیشن»).
 *
 * Written once for every screen that makes people -- new user, bulk, edit locations, and «کانفیگ
 * جدید» on a person's page -- so they cannot come to disagree about what "a user with VLESS and Trojan
 * in Germany and the Netherlands" means.
 *
 * **The operator chooses the protocols** (build 18). Until then the shape came silently from a plan's
 * template -- so an operator whose default template was the XHTTP preset got nothing but XHTTP, and
 * nothing on screen said so. The result of a create is now the list of what was made, which the
 * screen shows.
 *
 * **Every config except the person's first VLESS one carries a credential of its own.** The engine
 * finds a config -- and so its country -- by the credential on the wire, so two configs sharing one
 * would be one config as far as it can tell. The first VLESS config keeps the person's own uuid, which
 * is what every link and client that predates configs already carries.
 */
object LocationConfigs {

    /**
     * The protocols offered, in the order they are shown and created.
     *
     * XHTTP is not among them (2026-09-26). On a workers.dev address it cannot carry a connection:
     * Cloudflare answers stream-one's gRPC-typed request with 403 and holds any other request body
     * until it is complete, and in packet-up the upload POSTs sent while the download is open land in
     * other instances of the Worker and are lost -- measured against a live installation. Making it
     * work needs a Durable Object for each connection's requests to meet in, which is a separate
     * piece of work. Until then no screen makes an XHTTP config, and the engine leaves existing ones
     * out of the subscription (04c-studio-sub.js).
     */
    val OFFERED = listOf(ConfigShape.VLESS_WS, ConfigShape.TROJAN_WS)

    /** Whether a shape can be made now. Existing configs of any shape are still listed and editable. */
    fun isOffered(shape: ConfigShape): Boolean = shape in OFFERED

    /** What is ticked before the operator touches anything (agreed with the operator, 2026-09-26). */
    val DEFAULT: Set<ConfigShape> = setOf(ConfigShape.VLESS_WS, ConfigShape.TROJAN_WS)

    /** One config that was made: which protocol, which country (null = direct), and the row. */
    data class Made(val shape: ConfigShape, val country: String?, val config: StudioConfig)

    /** What a create did. Partial success is reported as such rather than as a failure. */
    data class Outcome(
        val made: List<Made>,
        val failed: List<Pair<String?, StudioError>>,
        /** Countries XHTTP was asked for and could not carry, on an engine older than build 18. */
        val skippedXhttpCountries: List<String> = emptyList(),
    ) {
        val firstError: StudioError? get() = failed.firstOrNull()?.second
        val isComplete: Boolean get() = failed.isEmpty() && made.isNotEmpty()
    }

    /**
     * Makes the configs and reports each one.
     *
     * [includeDirect] adds the configs with no country; with no countries they are always made -- a
     * user with nothing to connect through is not a user. [xhttpCarriesCountry] is the installation's
     * `xhttp.v2` capability: before build 18 the XHTTP path could neither read a config's own
     * credential nor carry a country, so there XHTTP stays on the person's uuid and direct-only.
     */
    suspend fun create(
        api: StudioHttpApi,
        user: StudioUser,
        shapes: Collection<ConfigShape>,
        template: ConfigTemplate?,
        countries: List<String>,
        includeDirect: Boolean,
        xhttpCarriesCountry: Boolean,
    ): Outcome {
        val ordered = OFFERED.filter { it in shapes }.ifEmpty { listOf(ConfigShape.VLESS_WS) }
        val targets: List<String?> =
            (if (includeDirect || countries.isEmpty()) listOf<String?>(null) else emptyList()) + countries.distinct()

        val made = mutableListOf<Made>()
        val failed = mutableListOf<Pair<String?, StudioError>>()
        val skipped = mutableListOf<String>()
        var ownUuidUsed = false

        for (cc in targets) {
            for (shape in ordered) {
                if (cc != null && shape == ConfigShape.VLESS_XHTTP && !xhttpCarriesCountry) {
                    skipped += cc
                    continue
                }
                val draft = draftFor(shape, template)
                val credential = when {
                    shape == ConfigShape.VLESS_XHTTP && !xhttpCarriesCountry ->
                        user.credential ?: StudioCredentials.newCredential(shape)
                    cc == null && shape == ConfigShape.VLESS_WS && !ownUuidUsed && !user.credential.isNullOrEmpty() -> {
                        ownUuidUsed = true
                        user.credential!!
                    }
                    else -> StudioCredentials.newCredential(shape)
                }
                val r = api.createConfig(
                    userId = user.id,
                    uriTemplate = StudioTemplates.templateFor(draft),
                    label = labelFor(shape, cc).take(60),
                    protocol = shape.protocol.wire.take(1),
                    transportType = shape.transport.wire,
                    credential = credential,
                    // Every config on the root: the engine tells them apart by credential, and a
                    // path of its own would cost a lookup on every connection (13a › route keys).
                    routeKey = null,
                    authHash = StudioCredentials.authHash(shape, credential),
                    nodeIds = template?.takeIf { it.toDraft().shape == shape }?.nodeIds.orEmpty(),
                    exitCc = cc,
                )
                when (r) {
                    is StudioResult.Ok -> made += Made(shape, cc, r.value)
                    is StudioResult.Err -> failed += cc to r.error
                }
            }
        }
        return Outcome(made, failed, skipped.distinct())
    }

    /** The draft for one protocol: the plan's template where it is that protocol, the defaults otherwise. */
    private fun draftFor(shape: ConfigShape, template: ConfigTemplate?): ConfigDraft {
        val fromTemplate = template?.toDraft()?.takeIf { it.shape == shape }
        return (fromTemplate ?: ConfigDraft(shape = shape)).copy(useRootPath = true)
    }

    /** What the operator reads in a person's config list. No flag glyph: the screens draw a badge. */
    fun labelFor(shape: ConfigShape, cc: String?): String {
        val proto = shortName(shape)
        return if (cc == null) proto else "${ExitCountries.name(cc)} · $proto"
    }

    /** «VLESS», «Trojan», «XHTTP»: how a protocol is named on every screen and in every result. */
    fun shortName(shape: ConfigShape): String = when (shape) {
        ConfigShape.VLESS_WS -> "VLESS"
        ConfigShape.TROJAN_WS -> "Trojan"
        ConfigShape.VLESS_XHTTP -> "XHTTP"
    }

    /** How many links a person's subscription will carry: configs × endpoints × ports. */
    fun linkCount(shapes: Int, countries: Int, includeDirect: Boolean, ports: Int, endpoints: Int = 1): Int {
        val targets = (if (includeDirect || countries == 0) 1 else 0) + countries
        return targets * shapes.coerceAtLeast(1) * ports.coerceAtLeast(1) * endpoints.coerceAtLeast(1)
    }

    /** The countries this installation can actually send somebody out of. */
    suspend fun availableCountries(api: StudioHttpApi): List<String> =
        (ownCountries(api) + poolCountries(api)).distinct().sorted()

    /**
     * Countries with an enabled, not-down exit the operator brought themselves -- by the country a
     * test MEASURED where there is one, which is also how the engine routes since build 18.
     */
    suspend fun ownCountries(api: StudioHttpApi): List<String> =
        when (val r = api.listExits()) {
            is StudioResult.Ok -> r.value.filter { it.enabled && !it.isDown }
                .map { it.exitCc ?: it.cc }.filter { it != "ZZ" }.distinct().sorted()
            is StudioResult.Err -> emptyList()
        }

    /**
     * Countries the public pool offers — only when the operator turned it on (build 17). An engine
     * older than 17 answers 404 here, which reads as "none", exactly as it should.
     */
    suspend fun poolCountries(api: StudioHttpApi): List<String> =
        when (val r = api.getPool()) {
            is StudioResult.Ok -> if (r.value.enabled) r.value.countries else emptyList()
            is StudioResult.Err -> emptyList()
        }

    /** True for the engine's "that country has no working server" refusal, so a screen can say it in words. */
    fun isNoExit(error: StudioError): Boolean = error.code == "no_exit"
}
