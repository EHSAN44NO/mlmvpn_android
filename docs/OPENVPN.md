# Internal OpenVPN client

## Integration decisions

The Android application uses Compose (`ui/home/HomeDestinations.kt`, `ui/AppScreen.kt`).
Xray-family connections use `MyVpnService`; other transports use `TunnelVpnService`.
OpenVPN has a dedicated foreground `VpnService`, with mutual exclusion at the same
handoff boundary. Accounts, profiles and connection lifetime are independent of the UI.

The supplied folder contains 47 TunnelBear profiles (including three `.ovpn.txt`
files) and `openvpn-server-ca.crt`. All use UDP/443, username/password authentication,
`remote-cert-tls server`, AES-256-CBC data encryption, SHA256 packet authentication,
redirect-gateway and two DNS addresses. The CA is an EC certificate valid from
2024 to 2034. No client certificate, private key, tls-auth or tls-crypt was supplied.
Do not replace this CA, weaken certificate checks or silently change the cipher.

The selected engine is the official OpenVPN 3 C++ core, embedded through a small
JNI adapter and built for arm64-v8a and armeabi-v7a, Android API 24. The pinned core
offers MPL-2.0 as one of its licensing options. Vendored source and license files
are retained with the dependency versions. It does not need another VPN app.

Profiles imported through the Android document picker are bounded, normalized,
deduplicated by content, and made self-contained with explicitly selected companion
files. No scripts, plugins, arbitrary filesystem access or external credential files
are accepted. Unsupported directives fail clearly. Private profile content and
account credentials belong in encrypted, app-private storage excluded from backup.
Encryption failure must fail the save, never fall back to plaintext.

## Usage and account selection

No documented public TunnelBear quota API has been established. Provider quota is
unknown until supplied by a verified provider adapter, never inferred from local
session bytes or an authentication failure. The UI links to the official account
page and reports usage unavailable. Refresh records a check attempt independently
of the timestamp of the last successful provider snapshot. No fabricated API or
100 GB allowance is used.

Fresh confirmed depletion and authentication/verification failures exclude accounts
from selection. Unknown usage is shown as unknown; a successful VPN authentication
does not establish email verification or remaining quota. Selection considers fresh
quota, recent successful connection and authentication, with bounded switching that
does not revisit a failed account. A quota change cannot be detected in real time
without a supported provider adapter; automatic quota switching must not pretend
otherwise.

## Measurements

TCP profiles use TCP connect timing, labelled accordingly. For the supplied UDP
profiles without tls-auth/tls-crypt, send an OpenVPN control reset with a random
session ID and validate the replying server reset's acknowledgement. Its elapsed
time measures OpenVPN endpoint response, not authenticated connection quality.
Authenticated/encrypted control-channel profiles need a different probe and must
report unsupported instead of a fake result. Tests are bounded and cancellable;
only recent comparable successful measurements qualify for fastest selection.

## Verification still required on a real device

Authenticated TunnelBear connection with a user-owned account; DNS and IPv6 leak
checks; switching from and to both existing VPN stacks; VPN permission denial and
revocation; background process death; network changes; reconnect and disconnect;
credential edits and deletion; import with missing/duplicate/malformed companions.
Build and unit tests do not establish live server compatibility by themselves.

Sources: https://github.com/OpenVPN/openvpn3 and
https://www.tunnelbear.com/blog/setting-up-tunnelbear-on-linux/ .
