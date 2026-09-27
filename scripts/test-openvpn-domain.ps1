param([string]$Java = 'C:/Program Files/Android/Android Studio/jbr/bin/java.exe')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Find-Jar([string]$group, [string]$artifact, [string]$version) {
    $path = Join-Path $cache "$group/$artifact/$version"
    $jar = Get-ChildItem -LiteralPath $path -Recurse -Filter "$artifact-$version.jar" | Select-Object -First 1
    if (!$jar) { throw "Missing cached dependency: $artifact $version" }
    return $jar.FullName
}
$stdlib = Find-Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '1.9.22'
$junit = Find-Jar 'junit' 'junit' '4.13.2'
$hamcrest = Find-Jar 'org.hamcrest' 'hamcrest-core' '1.3'
$compiler = @(
    (Find-Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '1.9.22'),
    $stdlib,
    (Find-Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '1.9.22'),
    (Find-Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (Find-Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (Find-Jar 'org.jetbrains' 'annotations' '13.0')
)
$output = Join-Path $root 'build/openvpn-domain-tests'
New-Item -ItemType Directory -Force $output | Out-Null
$source = Join-Path $root 'app/src/main/java/com/mlmvpn/scanner/openvpn'
$tests = Join-Path $root 'app/src/test/java/com/mlmvpn/scanner/openvpn'
& $Java -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$stdlib;$junit;$hamcrest" -d $output `
    "$source/OpenVpnModels.kt" "$source/ProfileImporter.kt" "$source/OpenVpnProbePacket.kt" `
    "$tests/OpenVpnPolicyTest.kt" "$tests/OpenVpnProfileTest.kt"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& $Java -cp "$output;$root/app/src/test/resources;$stdlib;$junit;$hamcrest" org.junit.runner.JUnitCore `
    com.mlmvpn.scanner.openvpn.OpenVpnPolicyTest com.mlmvpn.scanner.openvpn.OpenVpnProfileTest
exit $LASTEXITCODE
