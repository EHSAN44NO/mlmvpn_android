# Embedded OpenVPN

The pristine source archives are pinned by URL and SHA-256 in `source-lock.json`.
OpenVPN is used under its MPL-2.0 option; upstream license notices remain with each
source tree. Mbed TLS is built statically under Apache-2.0, Asio under BSL-1.0,
the LZ4 library under BSD-2-Clause and fmt under MIT. The app's JNI integration is
in `app/src/main/cpp/openvpn`, outside the upstream source tree.

Build both supported ABIs using `scripts/build-openvpn.ps1`. The libraries have
static libc++, Android API 24 minimum, and 16 KiB ELF segment alignment. Normal
Gradle builds package the resulting prebuilt libraries, as with the other engines.
The offline parser check can additionally be built with `MLM_PROFILE_CHECK=ON`.

To update from the original source, review an upstream full commit SHA and run
`scripts/update-openvpn-source.ps1 -Commit <40-hex-sha>`. It downloads that exact
revision from OpenVPN's repository, builds the Android integration for both ABIs,
runs domain tests and prepares the OpenVPN entry in `store/channel.spec.json`.
It does not publish. Complete device connection, DNS/IPv6 and handoff testing,
then use the existing store-channel build/verify/publish workflow. Keep native
API version 1 backward compatible or increase the update's minimum app version.

The phone downloads a compatible signed Android build, never desktop binaries or
an arbitrary shared object from upstream. JNI updates activate on process restart.
The existing store loader quarantines a crashing update and falls back to the
bundled engine. Account and profile files are separate from the engine directory.
