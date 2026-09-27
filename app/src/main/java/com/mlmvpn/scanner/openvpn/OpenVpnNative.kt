package com.mlmvpn.scanner.openvpn

import androidx.annotation.Keep
import android.content.Context
import com.mlmvpn.scanner.store.StoreEngines
import com.mlmvpn.scanner.store.StoreFiles

@Keep
object OpenVpnNative {
    private var checked: Boolean? = null
    @Synchronized fun available(context: Context): Boolean {
        checked?.let { return it }
        val ok = try { StoreEngines.loadLibrary("openvpn", "mlmopenvpn"); apiVersion() == 1 }
            catch (_: LinkageError) { false }
            catch (_: Exception) { false }
        if (!ok) runCatching { StoreFiles.quarantine(context, "openvpn", "Incompatible OpenVPN native interface; restart required") }
        checked = ok
        return ok
    }
    external fun apiVersion(): Int
    external fun evaluate(config: String): String
    external fun create(): Long
    external fun run(handle: Long, config: String, username: String, password: String, callback: Any): String
    external fun stop(handle: Long)
    external fun release(handle: Long)
}
