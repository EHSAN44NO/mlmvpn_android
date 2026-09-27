package com.mlmvpn.core.tunnel

/**
 * Single source of truth for "is a tunnel carrying traffic right now".
 *
 * Before badvpn this was just TunnelEngine.isRunning(), because the Rust core was
 * in every data path. In VPN mode the data path is now lwIP inside
 * libtun2socks.so and the Rust core is never started — so TunnelEngine.isRunning()
 * returns false while the device is fully tunnelled. Every UI surface that asked
 * TunnelEngine directly therefore reported "Not Connected" on a working tunnel.
 *
 * Ask this object instead of TunnelEngine.isRunning() anywhere the question is
 * "should the UI look connected". Ask TunnelEngine directly only when the question
 * is specifically about the Rust core (e.g. routing an HTTP check through its
 * local SOCKS listener in proxy mode).
 *
 * The app is single-process (no android:process in the manifest), so the service
 * and the activity see the same statics here.
 */
object TunnelStatus {

    /**
     * True when either data path is up: Rust core (proxy/other protocols) or tun2socks (VPN).
     *
     * The [TunnelEngine.isAvailable] guard is not defensive padding. `isRunning()` is a native
     * method, so on a device the core was not built for — or in any build where the pair of
     * libraries failed to load — calling it throws UnsatisfiedLinkError rather than returning
     * false. This is asked from `Application.onCreate`, which makes that throw fatal on launch:
     * the whole app dies before its first frame because one optional feature is unavailable.
     * With the guard, an absent core means "no tunnel is running", which is both true and the
     * only useful answer.
     */
    fun isActive(): Boolean =
        (TunnelEngine.isAvailable && TunnelEngine.isRunning()) || Tun2SocksManager.isRunning

    /** True when the whole device is being routed through tun2socks. */
    val isWholeDeviceRouting: Boolean
        get() = Tun2SocksManager.isRunning

    /**
     * True while the Rust core is driving an Android TUN directly.
     *
     * This is the WireGuard / MASQUE VPN-mode data path: the core's tun entry point
     * takes the `Some(fd)` branch in main.rs and spawns `tun::bridge`, so
     * `socks::serve` — which lives in the `else` arm — never runs and **no local
     * SOCKS listener exists**.
     *
     * Anything that wants to send a request "through the tunnel" must go direct
     * in this mode. Dialling 127.0.0.1:<socksPort> gets connection-refused, which
     * is what made the health check fail and paint "Connection degraded" over a
     * perfectly working WireGuard tunnel.
     */
    @Volatile
    var isNativeTunMode: Boolean = false
        internal set

    /**
     * The local SOCKS5 port the running transport publishes, or 0 when it publishes none.
     *
     * The only way for code in this process to reach the internet the way the tunnel does.
     * An ordinary socket cannot: the service excludes the app from its own tunnel so the
     * tunnel's traffic cannot re-enter the tun it is feeding, which means a plain request
     * from here leaves on the carrier's link and measures the phone, not the exit. Reading
     * the phone's own address and reporting it as the exit is not a small error — on an
     * Iranian line it is indistinguishable from a tunnel that genuinely exits in Iran.
     *
     * Non-zero exactly when [isNativeTunMode] is false, and the two are complements by
     * construction: a transport either hands the tun to the core (which then measures its own
     * exit and needs no probe) or publishes this port for tun2socks (and needs one).
     */
    @Volatile
    var activeSocksPort: Int = 0
        internal set
}
