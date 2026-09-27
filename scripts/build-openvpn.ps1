param(
    [string]$Ndk = 'I:/Android/ndk/android-ndk-r27d',
    [string]$Cmake = 'C:/Program Files/CMake/bin/cmake.exe',
    [string[]]$Abis = @('arm64-v8a', 'armeabi-v7a'),
    [string]$CoreDirectory = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lock = Get-Content -LiteralPath "$root/third_party/openvpn3/source-lock.json" -Raw | ConvertFrom-Json
if (!$CoreDirectory) { $CoreDirectory = "$root/third_party/openvpn3/openvpn3-$($lock.coreCommit)" }
$built = @()
foreach ($abi in $Abis) {
    if ($abi -notin @('arm64-v8a', 'armeabi-v7a')) { throw "Unsupported ABI: $abi" }
    $build = Join-Path $root "build/openvpn/$abi"
    & $Cmake -S "$root/app/src/main/cpp/openvpn" -B $build -G 'MinGW Makefiles' `
        "-DCMAKE_MAKE_PROGRAM=$Ndk/prebuilt/windows-x86_64/bin/make.exe" `
        "-DCMAKE_TOOLCHAIN_FILE=$Ndk/build/cmake/android.toolchain.cmake" `
        "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static `
        "-DOPENVPN_CORE_DIR=$CoreDirectory" `
        -DCMAKE_BUILD_TYPE=Release '-DCMAKE_POLICY_VERSION_MINIMUM=3.5'
    if ($LASTEXITCODE) { throw 'OpenVPN configuration failed' }
    & $Cmake --build $build --parallel 4
    if ($LASTEXITCODE) { throw 'OpenVPN build failed' }
    $target = "$build/libmlmopenvpn.so"
    & "$Ndk/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe" $target
    if ($LASTEXITCODE) { throw 'OpenVPN strip failed' }
    $built += @{ Abi = $abi; File = $target }
}
foreach ($entry in $built) {
    $target = Join-Path $root "app/src/main/jniLibs/$($entry.Abi)/libmlmopenvpn.so"
    Copy-Item -LiteralPath $entry.File -Destination $target -Force
    Get-FileHash -LiteralPath $target -Algorithm SHA256
}
