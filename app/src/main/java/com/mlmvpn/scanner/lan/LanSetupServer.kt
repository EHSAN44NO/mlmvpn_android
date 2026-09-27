package com.mlmvpn.scanner.lan

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.utils.AppLocaleManager
import com.mlmvpn.scanner.utils.S
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

// =================================================================================================
// A tiny web server on the LAN, whose entire job is that nobody has to type an IP address.
//
// Setting a proxy by hand means moving three values -- host, port, and which kind -- from a phone
// screen to another device, correctly, through a settings dialog the user has probably never
// opened. Every step of that is a place to get it wrong, and getting it wrong looks exactly like
// the feature being broken.
//
// So the phone serves the instructions instead. The other device opens one URL and gets: the
// values with copy buttons, the steps for ITS OWN operating system (from the User-Agent, so the
// six it isn't running stay out of the way), and a live line saying whether the phone can actually
// see it connecting yet. The PAC file goes further -- Windows, macOS, iOS and Android all take a
// single "automatic configuration URL", so on those four there is one field to fill instead of
// three, and the PAC keeps the LAN itself off the tunnel as a bonus.
//
// It binds 0.0.0.0 and speaks plain HTTP, which is correct rather than lazy: the only clients are
// on the same physical network, the content is a page of instructions and a proxy address that is
// already being broadcast to that network, and TLS would mean a self-signed certificate and a full
// browser warning page standing between the user and the thing that is supposed to be easy.
// =================================================================================================

object LanSetupServer {

    private const val TAG = "LanSetupServer"

    /** How long a device stays in the "opened the page" list after its last request. */
    private const val SEEN_TTL_MS = 30 * 60 * 1000L

    private val running = AtomicBoolean(false)

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    /** Last time each client address asked for anything, for the TTL above. */
    private val seen = Collections.synchronizedMap(mutableMapOf<String, Long>())

    /** Addresses that have loaded the setup page recently. */
    fun seenClients(): Set<String> {
        val cutoff = System.currentTimeMillis() - SEEN_TTL_MS
        synchronized(seen) {
            seen.entries.removeAll { it.value < cutoff }
            return seen.keys.toSet()
        }
    }

    val isRunning: Boolean get() = running.get()

    /**
     * Bring the server up or down to match what sharing currently needs.
     *
     * Called from the screen's own poll rather than run on a timer of its own: the conditions it
     * depends on -- an address exists, sharing is on -- are already being recomputed there, and a
     * second scheduler watching the same facts is a second thing to get out of step.
     */
    fun sync(context: Context, status: LanStatus) {
        val wanted = status.lanEnabled && status.address != null
        if (wanted && !running.get()) {
            start(context.applicationContext, status.setupPort)
        } else if (!wanted && running.get()) {
            stop()
        } else if (wanted && boundPort != status.setupPort) {
            // The user changed Local Port while sharing was up. Rebind rather than keep serving
            // a page that advertises a port nothing is listening on any more.
            stop()
            start(context.applicationContext, status.setupPort)
        }
    }

    fun start(context: Context, port: Int) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        val server = try {
            // Bound here rather than inside the thread. When [sync] rebinds -- stop() then
            // start() in one tick -- the old accept thread is still winding down, and its
            // `finally` used to clear `running`, `socket` and `boundPort` a moment AFTER the new
            // thread had set them. The new server then saw its own `running` flag false, served
            // one more request and quit, and every start() after that failed to bind a port its
            // own predecessor was still holding. Owning the socket as a local means the loop
            // below never consults shared state it could lose a race for.
            ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"))
        } catch (e: Exception) {
            Log.w(TAG, "setup server could not bind $port: ${e.message}")
            running.set(false)
            return
        }
        socket = server
        boundPort = port
        Thread({
            try {
                server.use {
                    Log.i(TAG, "setup server listening on 0.0.0.0:$port")
                    while (!server.isClosed) {
                        val client = try {
                            server.accept()
                        } catch (e: Exception) {
                            if (running.get() && !server.isClosed) {
                                Log.w(TAG, "accept failed: ${e.message}")
                            }
                            break
                        }
                        // Thread per request, not a pool: every response here is a few kilobytes
                        // built and written in one pass, and the client count is however many
                        // devices are on one hotspot.
                        Thread({ serve(app, client) }, "lan-setup-conn").apply {
                            isDaemon = true
                        }.start()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "setup server stopped: ${e.message}")
            } finally {
                // Only if this thread is still the current server. A rebind has already
                // installed its successor by now, and clearing the fields here would be
                // clearing that one's.
                if (socket === server) {
                    running.set(false)
                    socket = null
                    boundPort = 0
                }
            }
        }, "lan-setup").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        boundPort = 0
        // `seen` deliberately survives. [sync] stops and restarts this server whenever the
        // phone's address changes, and wiping the list there made the screen forget a device
        // that was still sitting on the instructions page. Only an explicit stop forgets, and
        // otherwise SEEN_TTL_MS does.
    }

    /** Stop and forget, for the notification's and tile's "stop sharing" buttons. */
    fun shutdown() {
        stop()
        seen.clear()
        LanRelay.stop()
        LanRelay.reset()
    }

    // ---------------------------------------------------------------------------------------
    // Request handling
    // ---------------------------------------------------------------------------------------

    private fun serve(context: Context, client: Socket) {
        client.use { sock ->
            sock.soTimeout = 10_000
            val input = sock.getInputStream().bufferedReader()
            val requestLine = runCatching { input.readLine() }.getOrNull() ?: return
            val path = requestLine.split(' ').getOrNull(1) ?: "/"

            var userAgent = ""
            while (true) {
                val header = runCatching { input.readLine() }.getOrNull() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("User-Agent:", ignoreCase = true)) {
                    userAgent = header.substringAfter(':').trim()
                }
            }

            val remote = sock.inetAddress?.hostAddress.orEmpty()
            val out = sock.getOutputStream()
            val status = LanShare.status(context)

            when {
                path.startsWith("/proxy.pac") -> {
                    // Not marked as seen: a PAC fetch is the client's proxy subsystem, not a
                    // person reading instructions, and the two mean different things on screen.
                    send(out, "application/x-ns-proxy-autoconfig", pac(status).toByteArray())
                }

                path.startsWith("/status") -> {
                    if (remote.isNotEmpty()) touch(remote)
                    // Two facts, not one. "Open right now" is the wrong question on its own: a
                    // browser parked on this page has no proxy connection open at that instant
                    // and will not until the user navigates somewhere, so a page that asks only
                    // that tells a correctly configured device its settings are wrong -- which
                    // is precisely what it used to do. `ever` is what answers "did it take".
                    val open = LanRelay.connectionsFrom(remote)
                    val ever = LanRelay.everConnected(remote)
                    send(
                        out,
                        "application/json",
                        """{"connections":$open,"ever":$ever}""".toByteArray(),
                    )
                }

                path.startsWith("/qr") -> {
                    val data = param(path, "d").ifEmpty { status.setupUrl.orEmpty() }
                    val png = qrPng(data)
                    if (png == null) send(out, "text/plain", "no qr".toByteArray(), code = 500)
                    else send(out, "image/png", png)
                }

                path.startsWith("/favicon.ico") ->
                    send(out, "text/plain", ByteArray(0), code = 404)

                else -> {
                    if (remote.isNotEmpty()) touch(remote)
                    val os = detectOs(userAgent, param(path, "os"))
                    send(out, "text/html; charset=utf-8", page(status, os).toByteArray())
                }
            }
        }
    }

    private fun touch(address: String) {
        seen[address] = System.currentTimeMillis()
    }

    private fun param(path: String, name: String): String {
        val query = path.substringAfter('?', "")
        for (pair in query.split('&')) {
            val (k, v) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k == name) return runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        return ""
    }

    private fun send(out: OutputStream, type: String, body: ByteArray, code: Int = 200) {
        val reason = if (code == 200) "OK" else "Error"
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: ${body.size}\r\n")
            // No caching: every value on this page can change while the page is open -- the
            // address moves when the user switches from Wi-Fi to a hotspot, and the status line
            // is the whole point of the page.
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        }
    }

    // ---------------------------------------------------------------------------------------
    // The PAC file
    // ---------------------------------------------------------------------------------------

    /**
     * The proxy auto-config script the four desktop and mobile platforms all accept.
     *
     * Two things it does that a hand-typed host and port cannot:
     *
     *  - **Keeps the local network off the tunnel.** Without this, the client's own router, NAS,
     *    printer and this very setup page would all be dialled through the phone and back, which
     *    is slow at best and a loop at worst.
     *  - **Offers both protocols in one line.** The Xray inbound is `mixed`, so the same port
     *    answers HTTP and SOCKS5, and a client that prefers one gets it without a second field.
     *
     * There is deliberately no `DIRECT` at the end of the return list. A PAC that falls back to
     * DIRECT when the proxy is unreachable sends the user's traffic out in the clear at exactly
     * the moment the tunnel died -- which is the one moment protection mattered. Failing closed
     * shows as "no internet", which is honest and recoverable; failing open shows as nothing at
     * all and is neither.
     */
    fun pac(status: LanStatus): String {
        val host = status.address ?: return "function FindProxyForURL(u, h) { return \"DIRECT\"; }"
        // [LanStatus.sharePort] rather than the engine's own listener, so the connection lands on
        // [LanRelay] and is counted. The relay only fronts a `mixed` inbound (see its sync), so
        // when it is up one port answers both protocols; without it the two-port split of the
        // NO_AUTH engines is still the only thing that works.
        val http = when {
            status.relayPort != null -> status.sharePort
            status.engine == EngineShare.NO_AUTH -> status.httpPort
            else -> status.socksPort
        }
        val socks = status.sharePort
        return """
            function FindProxyForURL(url, host) {
              if (isPlainHostName(host)) return "DIRECT";
              if (host == "localhost" || host == "127.0.0.1" || host == "::1") return "DIRECT";
              if (shExpMatch(host, "*.local")) return "DIRECT";
              if (isInNet(host, "10.0.0.0", "255.0.0.0")) return "DIRECT";
              if (isInNet(host, "172.16.0.0", "255.240.0.0")) return "DIRECT";
              if (isInNet(host, "192.168.0.0", "255.255.0.0")) return "DIRECT";
              if (isInNet(host, "169.254.0.0", "255.255.0.0")) return "DIRECT";
              return "PROXY $host:$http; SOCKS5 $host:$socks; SOCKS $host:$socks";
            }
        """.trimIndent()
    }

    // ---------------------------------------------------------------------------------------
    // QR
    // ---------------------------------------------------------------------------------------

    /** A QR PNG for [data], or null when ZXing cannot encode it. */
    fun qrPng(data: String, size: Int = 420): ByteArray? {
        if (data.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val row = y * size
                for (x in 0 until size) {
                    pixels[row + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                bitmap.recycle()
            }.toByteArray()
        }.getOrNull()
    }

    // ---------------------------------------------------------------------------------------
    // The page
    // ---------------------------------------------------------------------------------------

    /** The operating systems the guide has steps for. */
    enum class ClientOs { WINDOWS, MACOS, IOS, ANDROID, LINUX, OTHER }

    /**
     * Which OS the requesting device is running.
     *
     * An explicit `?os=` always wins, so the "another system" links work regardless of what the
     * browser claims. iOS is tested before macOS because an iPad's user agent says "Macintosh"
     * on the desktop-site setting, and Android before Linux for the same reason in reverse.
     */
    fun detectOs(userAgent: String, override: String = ""): ClientOs {
        override.uppercase().takeIf { it.isNotEmpty() }?.let { name ->
            ClientOs.values().firstOrNull { it.name == name }?.let { return it }
        }
        val ua = userAgent.lowercase()
        return when {
            ua.contains("android") -> ClientOs.ANDROID
            ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod") -> ClientOs.IOS
            ua.contains("windows") -> ClientOs.WINDOWS
            ua.contains("mac os") || ua.contains("macintosh") -> ClientOs.MACOS
            ua.contains("linux") || ua.contains("x11") -> ClientOs.LINUX
            else -> ClientOs.OTHER
        }
    }

    private fun stepsFor(os: ClientOs): Int = when (os) {
        ClientOs.WINDOWS -> R.string.lanweb_steps_windows
        ClientOs.MACOS -> R.string.lanweb_steps_macos
        ClientOs.IOS -> R.string.lanweb_steps_ios
        ClientOs.ANDROID -> R.string.lanweb_steps_android
        ClientOs.LINUX -> R.string.lanweb_steps_linux
        ClientOs.OTHER -> R.string.lanweb_steps_other
    }

    private fun nameFor(os: ClientOs): Int = when (os) {
        ClientOs.WINDOWS -> R.string.lanweb_os_windows
        ClientOs.MACOS -> R.string.lanweb_os_macos
        ClientOs.IOS -> R.string.lanweb_os_ios
        ClientOs.ANDROID -> R.string.lanweb_os_android
        ClientOs.LINUX -> R.string.lanweb_os_linux
        ClientOs.OTHER -> R.string.lanweb_os_other
    }

    /**
     * The whole page, inline, in the language the app is set to.
     *
     * Self-contained on purpose: the client has no working internet yet -- that is why it is
     * here -- so a stylesheet or font from a CDN would be a blank page. Everything is one
     * response.
     *
     * Numbers stay left-to-right even in the Persian layout. An IP rendered in a right-to-left
     * run reads back as a different address, and one rendered with Persian digits cannot be
     * typed into anything at all.
     */
    fun page(status: LanStatus, os: ClientOs): String {
        val rtl = AppLocaleManager.isFarsi()
        val pacUrl = status.pacUrl.orEmpty()
        val endpoint = status.proxyEndpoint.orEmpty()
        val host = status.address.orEmpty()
        val port = when {
            status.relayPort != null -> status.sharePort
            status.engine == EngineShare.NO_AUTH -> status.httpPort
            else -> status.socksPort
        }
        val usesPac = os != ClientOs.OTHER
        val steps = S(stepsFor(os)).split('\n').filter { it.isNotBlank() }

        val others = ClientOs.values().filter { it != os }.joinToString(" · ") {
            """<a href="/?os=${it.name.lowercase()}">${esc(S(nameFor(it)))}</a>"""
        }

        return buildString {
            append("<!doctype html><html lang=\"")
            append(if (rtl) "fa" else "en")
            append("\" dir=\"")
            append(if (rtl) "rtl" else "ltr")
            append("\"><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<title>").append(esc(S(R.string.lanweb_title))).append("</title>")
            append(STYLE)
            append("</head><body><main>")

            append("<h1>").append(esc(S(R.string.lanweb_title))).append("</h1>")
            append("<p class=\"lead\">").append(esc(S(R.string.lanweb_lead))).append("</p>")

            // The two states the poll can move to ride along as data, so the script stays a
            // constant and the strings stay in strings.xml.
            append("<div id=\"live\" class=\"live wait\" data-texts=\"")
            append(
                esc(
                    "{\"ok\":\"" + json(S(R.string.lanweb_status_ok)) + "\"," +
                        "\"done\":\"" + json(S(R.string.lanweb_status_done)) + "\"," +
                        "\"seen\":\"" + json(S(R.string.lanweb_status_seen)) + "\"}"
                )
            )
            append("\">")
            append(esc(S(R.string.lanweb_status_waiting)))
            append("</div>")

            append("<h2>").append(esc(S(nameFor(os)))).append("</h2>")

            if (usesPac) {
                field(S(R.string.lanweb_pac_label), pacUrl)
            } else {
                field(S(R.string.lanweb_host_label), host)
                field(S(R.string.lanweb_port_label), port.toString())
            }

            append("<ol>")
            steps.forEach { append("<li>").append(esc(it)).append("</li>") }
            append("</ol>")

            // Android only, and stated plainly rather than left to be discovered. A Wi-Fi proxy
            // on Android is advisory: the browser and apps built on the platform HTTP stack
            // honour it, apps with their own networking ignore it, and UDP never sees it at all.
            // A field test showed exactly that shape -- background Google and Samsung services
            // going through the tunnel while the rest of the phone did not -- and with nothing on
            // screen to explain it, a partly shared phone reads as a broken feature.
            if (os == ClientOs.ANDROID) {
                append("<p class=\"warn\">").append(esc(S(R.string.lanweb_android_caveat)))
                append("</p>")
            }

            if (os == ClientOs.WINDOWS) {
                append("<p class=\"alt\">").append(esc(S(R.string.lanweb_powershell_label)))
                append("</p>")
                field(
                    "PowerShell",
                    "Set-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\" +
                        "CurrentVersion\\Internet Settings' -Name AutoConfigURL -Value '$pacUrl'",
                )
            }

            if (usesPac) {
                append("<p class=\"alt\">").append(esc(S(R.string.lanweb_manual_label)))
                append("</p>")
                field(S(R.string.lanweb_endpoint_label), endpoint)
            }

            append("<p class=\"note\">").append(esc(S(R.string.lanweb_note_names))).append("</p>")
            append("<p class=\"others\">").append(esc(S(R.string.lanweb_other_os)))
            append(" ").append(others).append("</p>")

            append("</main>").append(SCRIPT).append("</body></html>")
        }
    }

    /** A labelled value with a copy button. Values are monospace and always left-to-right. */
    private fun StringBuilder.field(label: String, value: String) {
        append("<div class=\"field\"><div class=\"label\">").append(esc(label))
        append("</div><div class=\"row\"><code dir=\"ltr\">").append(esc(value))
        append("</code><button data-v=\"").append(esc(value))
        append("\" data-done=\"").append(esc(S(R.string.lanweb_copied))).append("\">")
        append(esc(S(R.string.lanweb_copy))).append("</button></div></div>")
    }

    /** Escapes a translated string for embedding inside the JSON that rides in a data- attribute. */
    private fun json(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun esc(v: String): String = v
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private val STYLE = """
        <style>
        :root{--bg:#0f1513;--card:#18211f;--ink:#e8efed;--dim:#9aa8a5;--accent:#4ecdc4;
        --ok:#63c795;--warn:#d6a544;--line:#26332f}
        @media(prefers-color-scheme:light){:root{--bg:#f3f6f6;--card:#fff;--ink:#16211f;
        --dim:#5b6a68;--accent:#0e7c7b;--ok:#2e7d5b;--warn:#8a5a06;--line:#dde5e4}}
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.7 system-ui,
        -apple-system,"Segoe UI",Tahoma,sans-serif}
        main{max-width:640px;margin:0 auto;padding:28px 20px 64px}
        h1{font-size:24px;margin:0 0 8px}
        h2{font-size:17px;margin:34px 0 12px;color:var(--accent)}
        .lead{color:var(--dim);margin:0 0 22px}
        .live{padding:12px 16px;border-radius:10px;font-weight:700;font-size:15px;
        border:1px solid var(--line);background:var(--card)}
        .live.wait{color:var(--dim)}
        .live.seen{color:var(--warn)}
        .live.ok{color:var(--ok)}
        .field{background:var(--card);border:1px solid var(--line);border-radius:10px;
        padding:12px 14px;margin:0 0 12px}
        .label{font-size:12px;color:var(--dim);margin-bottom:6px}
        .row{display:flex;gap:10px;align-items:center}
        code{flex:1;font-family:ui-monospace,Menlo,Consolas,monospace;font-size:14px;
        word-break:break-all;text-align:left}
        button{flex:none;border:0;border-radius:8px;background:var(--accent);color:#03211f;
        font-weight:700;font-size:13px;padding:8px 14px;cursor:pointer;font-family:inherit}
        ol{padding-inline-start:22px;margin:0 0 18px}
        li{margin-bottom:7px}
        .alt,.note{color:var(--dim);font-size:14px;margin:20px 0 10px}
        .warn{color:var(--warn);font-size:14px;line-height:1.65;margin:0 0 18px;
        background:var(--card);border:1px solid var(--line);border-inline-start:3px solid
        var(--warn);border-radius:10px;padding:12px 14px}
        .note{border-top:1px solid var(--line);padding-top:16px;margin-top:28px}
        .others{font-size:13px;color:var(--dim)}
        .others a{color:var(--accent);text-decoration:none;white-space:nowrap}
        </style>
    """.trimIndent()

    /**
     * Copy buttons, and the poll that answers "did it work".
     *
     * The status line is the reason this page beats a printed instruction: the phone is the only
     * party that can see whether this device has actually opened a proxy connection, so it says
     * so, every two seconds, without the user having to go and test a website and come back.
     */
    private val SCRIPT = """
        <script>
        document.addEventListener('click', function (e) {
          var b = e.target.closest('button[data-v]');
          if (!b) return;
          var t = b.textContent;
          function done() { b.textContent = b.dataset.done || 'OK';
            setTimeout(function () { b.textContent = t; }, 1400); }
          if (navigator.clipboard) { navigator.clipboard.writeText(b.dataset.v).then(done, sel); }
          else sel();
          function sel() {
            var r = document.createRange();
            r.selectNodeContents(b.parentElement.querySelector('code'));
            var s = getSelection(); s.removeAllRanges(); s.addRange(r);
            try { document.execCommand('copy'); done(); } catch (_) {}
          }
        });
        (function () {
          var el = document.getElementById('live');
          var texts = JSON.parse(el.dataset.texts || '{}');
          // Three states, because "connections open right now" is not the same question as "did
          // my settings take". A browser sitting on this page opens no proxy connection until
          // the user navigates away, so treating an idle moment as a failure -- which the two
          // state version did -- sends a correctly configured user back to change settings that
          // were right. `ever` is sticky and answers the question actually being asked.
          function tick() {
            fetch('/status', { cache: 'no-store' }).then(function (r) { return r.json(); })
              .then(function (j) {
                if (j.connections > 0) { el.className = 'live ok'; el.textContent = texts.ok; }
                else if (j.ever) { el.className = 'live ok'; el.textContent = texts.done; }
                else { el.className = 'live seen'; el.textContent = texts.seen; }
              }).catch(function () {});
          }
          setInterval(tick, 2000); tick();
        })();
        </script>
    """.trimIndent()
}
