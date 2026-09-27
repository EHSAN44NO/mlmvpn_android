package com.mlmvpn.scanner.lan

// =================================================================================================
// Which devices are using the shared proxy.
//
// This used to read `/proc/net/tcp` and `/proc/net/tcp6`, on the understanding that Android 10
// restricted those tables to the caller's own uid -- which would have been exactly right here,
// since the proxy's sockets are ours. On a real device they are not restricted, they are refused:
//
//     $ run-as com.mlmvpn.scanner cat /proc/net/tcp6
//     cat: /proc/net/tcp6: Permission denied
//
// (Samsung SM-A505F, Android 11.) The read failed, the failure was swallowed by design, and the
// result was a permanent, silent "no devices connected" while a laptop was browsing through the
// share -- with the setup page reading the same zero and telling the user their settings were
// wrong. There is no version of that file, and no permission, that gives this back.
//
// So the app owns the listening socket now and counts at the door. Everything below is a join of
// two sources that know rather than infer: [LanRelay], which accepted the connection, and
// [LanSetupServer], which served the instructions. Neither needs a permission.
// =================================================================================================

object LanClients {

    /**
     * Every device the share knows about: connected, previously connected, or only ever having
     * read the instructions.
     *
     * [seenSetupPage] is what separates "the client's proxy settings are wrong" from "the client
     * cannot reach this phone at all" -- two failures that look identical from the phone and have
     * completely different fixes. A device in both sets keeps its relay figures and gains the
     * page flag.
     */
    fun snapshot(seenSetupPage: Set<String> = emptySet()): List<LanClient> {
        val relayed = LanRelay.peers().associateBy { it.address }
        val all = LinkedHashSet<String>().apply {
            addAll(relayed.keys)
            addAll(seenSetupPage)
        }
        return all.map { address ->
            relayed[address]?.copy(openedSetupPage = address in seenSetupPage)
                ?: LanClient(
                    address = address,
                    connections = 0,
                    openedSetupPage = true,
                )
        }.sortedWith(
            compareByDescending<LanClient> { it.connections }
                .thenByDescending { it.everConnected }
                .thenBy { it.address }
        )
    }

    /** True when anything at all is pushing traffic through the share right now. */
    fun anyConnected(): Boolean = LanRelay.peers().any { it.connections > 0 }
}
