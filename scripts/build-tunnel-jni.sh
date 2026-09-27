#!/usr/bin/env bash
# Build the JNI shim over the tunnel core -> app/src/main/jniLibs/<abi>/libtunneljni.so
#
# WHY THIS IS A SCRIPT AND NOT AN externalNativeBuild BLOCK
# ---------------------------------------------------------
# Wiring CMake into app/build.gradle would demand an NDK *and* CMake on every machine that
# builds this app, and would then emit the same file name the packager already takes from
# jniLibs -- where libtunnelcore.so, libaether.so, libtor.so and every other core in this APK
# also live, prebuilt. This keeps the shim on the same footing as the rest of them: checked in
# built, rebuilt by hand on the rare occasion its source changes.
#
# It changes for exactly one reason. The JVM binds native methods by the mangled name
# Java_<package>_<class>_<method>, so the exports in tunnel_jni.cpp encode the Kotlin package
# of TunnelEngine. Move that class and the shim must be rebuilt, or every native call fails
# with UnsatisfiedLinkError -- during Application.onCreate, so the app dies on launch rather
# than degrading.
#
# CMake is deliberately not used: the NDK on this machine ships the toolchain but not CMake,
# and the shim is one translation unit with one dependency. clang++ is enough.
#
# Usage:  ANDROID_NDK=/path/to/ndk scripts/build-tunnel-jni.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp/tunnel_jni.cpp"
JNILIBS="$ROOT/app/src/main/jniLibs"

NDK="${ANDROID_NDK:-I:/Android/ndk/android-ndk-r27d}"
[ -d "$NDK" ] || { echo "NDK not found at $NDK; set ANDROID_NDK" >&2; exit 1; }

# API 24 to match the app's minSdk. Building against a higher one links symbols the oldest
# supported device does not have, and the failure is a dlopen error on that device only.
API=24
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
[ -d "$BIN" ] || BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

build() {
    local abi="$1" triple="$2"
    local out="$JNILIBS/$abi/libtunneljni.so"
    local core="$JNILIBS/$abi/libtunnelcore.so"
    [ -f "$core" ] || { echo "skip $abi: no libtunnelcore.so"; return; }

    "$BIN/${triple}${API}-clang++" \
        -std=c++17 -fPIC -O2 -shared \
        -static-libstdc++ \
        -o "$out" "$CPP" \
        -L"$JNILIBS/$abi" -ltunnelcore -llog \
        -Wl,-z,max-page-size=16384 \
        -Wl,-z,common-page-size=16384 \
        -Wl,--build-id=sha1
    # -static-libstdc++ is REQUIRED, not an optimisation. The NDK's clang++ driver defaults to
    # the SHARED libc++, which makes the result depend on libc++_shared.so -- a file that is not
    # in this APK and is not going to be. The library then fails to dlopen, `System.loadLibrary`
    # throws, TunnelEngine.isAvailable goes false, and the connect button says "the core was not
    # bundled for this device" on a device the core is sitting on. CMake did not have this
    # problem because AGP defaults ANDROID_STL to c++_static.
    #
    # -Wl,-z,max-page-size=16384: Android 15 can run with 16 KB memory pages, and a library whose
    # LOAD segments are aligned to the older 4 KB cannot be mapped there at all.
    echo "built $abi  $(stat -c%s "$out") bytes"
}

build arm64-v8a   aarch64-linux-android
build armeabi-v7a armv7a-linux-androideabi

echo
echo "exported JNI symbols (arm64):"
"$BIN/llvm-nm" -D --defined-only "$JNILIBS/arm64-v8a/libtunneljni.so" \
    | grep -o 'Java_[A-Za-z0-9_]*' | sort

# Verified rather than assumed: every NEEDED entry has to be either a platform library or a
# file that is actually in jniLibs. Anything else dlopens to nothing on the device, and the
# only symptom is a feature that reports itself unavailable.
echo
echo "dependencies (must all be present in the APK or on the platform):"
for abi in arm64-v8a armeabi-v7a; do
    [ -f "$JNILIBS/$abi/libtunneljni.so" ] || continue
    "$BIN/llvm-readelf" -d "$JNILIBS/$abi/libtunneljni.so" | grep NEEDED | sed 's/.*\[\(.*\)\]/\1/' |
    while read -r dep; do
        case "$dep" in
            libc.so|libm.so|libdl.so|liblog.so) verdict="platform" ;;
            *) [ -f "$JNILIBS/$abi/$dep" ] && verdict="in jniLibs" || verdict="!! MISSING" ;;
        esac
        printf '  %-14s %-22s %s\n' "$abi" "$dep" "$verdict"
    done
done
