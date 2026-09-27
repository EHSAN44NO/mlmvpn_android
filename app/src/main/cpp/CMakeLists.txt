cmake_minimum_required(VERSION 3.22.1)
project(tunneljni CXX C)

# The tunnel core is built separately by native/build-engine.ps1 and dropped
# into jniLibs; here it is only referenced so the shim can link against it.
# IMPORTED means "already built, do not try to compile it".
add_library(tunnelcore SHARED IMPORTED)
set_target_properties(tunnelcore PROPERTIES
    IMPORTED_LOCATION
    ${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libtunnelcore.so)

add_library(tunneljni SHARED tunnel_jni.cpp)

# log is not used yet but every NDK target links it in practice; keeping it here
# means adding a __android_log_print while debugging needs no build change.
find_library(log-lib log)

target_link_libraries(tunneljni tunnelcore ${log-lib})

# Android 15 can run with 16 KB memory pages. A shared library whose LOAD
# segments are aligned to the older 4 KB cannot be mapped on such a device, so
# every .so in the APK has to be linked with the larger alignment. NDK r28 does
# this by default; asking for it explicitly keeps the result the same whichever
# NDK Gradle picks.
target_link_options(tunneljni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")

# badvpn tun2socks (lwIP TCP/IP stack in userspace) -> libtun2socks.so.
#
# This is the path for transports that hand us a local SOCKS5 port rather than
# driving the tun themselves. lwIP terminates TCP in userspace and udpgw carries
# UDP, which is what makes QUIC and games work; a hand-written stack that only
# forwarded port 53 would not.
add_subdirectory(badvpn)
