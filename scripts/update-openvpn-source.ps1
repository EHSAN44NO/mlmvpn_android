param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$Commit,
    [string]$Ndk = 'I:/Android/ndk/android-ndk-r27d',
    [string]$Cmake = 'C:/Program Files/CMake/bin/cmake.exe'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$deps = Join-Path $root 'third_party/openvpn3'
$Commit = $Commit.ToLowerInvariant()
$url = "https://codeload.github.com/OpenVPN/openvpn3/zip/$Commit"
$archive = Join-Path $deps "openvpn3-$Commit.zip"
Invoke-WebRequest -Uri $url -OutFile $archive
$hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
Expand-Archive -LiteralPath $archive -DestinationPath $deps -Force
$source = Join-Path $deps "openvpn3-$Commit"
$header = Get-Content -LiteralPath "$source/client/ovpncli.hpp" -Raw
if ($header -notmatch 'MPL-2\.0') { throw 'Upstream licensing changed; review it before updating.' }
& "$PSScriptRoot/test-openvpn-domain.ps1"
if ($LASTEXITCODE) { throw 'Domain regression tests failed; update not prepared.' }
& "$PSScriptRoot/build-openvpn.ps1" -Ndk $Ndk -Cmake $Cmake -CoreDirectory $source
if ($LASTEXITCODE) { throw 'Native build failed; update not prepared.' }

$lockPath = Join-Path $deps 'source-lock.json'
$lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
$previous = $lock.storeVersion
$lock.coreCommit = $Commit
$lock.storeRevision = [int]$lock.storeRevision + 1
$lock.storeVersion = "1.0.$($lock.storeRevision)"
$lock.sources[0].url = $url
$lock.sources[0].sha256 = $hash
$lock | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $lockPath -Encoding utf8NoBOM
Copy-Item -LiteralPath $lockPath -Destination "$root/app/src/main/assets/openvpn-licenses/sources.json" -Force
$catalog = Join-Path $root 'app/src/main/java/com/mlmvpn/scanner/store/StoreCatalog.kt'
$text = Get-Content -LiteralPath $catalog -Raw
$text.Replace("shipped = `"$previous`"", "shipped = `"$($lock.storeVersion)`"") | Set-Content -LiteralPath $catalog -Encoding utf8NoBOM -NoNewline

$specPath = Join-Path $root 'store/channel.spec.json'
$spec = Get-Content -LiteralPath $specPath -Raw | ConvertFrom-Json
$artifacts = foreach ($abi in @('arm64-v8a','armeabi-v7a')) {
    @{ abi = $abi; name = "openvpn-$abi.so"; format = 'raw'; extract = @{ '' = 'libmlmopenvpn.so' };
       from = @{ file = "app/src/main/jniLibs/$abi/libmlmopenvpn.so" } }
}
$item = @{ id = 'openvpn'; version = $lock.storeVersion; minApp = '1.2.36';
    notes = "Official OpenVPN source $Commit; MLMVPN JNI API 1; Mbed TLS 3.6.7. Restart the app after updating.";
    artifacts = @($artifacts) }
$spec.items = @($spec.items | Where-Object id -ne 'openvpn') + $item
$spec | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $specPath -Encoding utf8NoBOM
Write-Output 'Build prepared. Run device connection/leak/handoff tests, then review and sign the store channel. Nothing was published.'
