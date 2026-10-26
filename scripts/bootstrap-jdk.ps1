param()

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $PSScriptRoot
$Version = '21.0.9+10'
$ArchiveName = 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.9_10.zip'
$Url = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.9%2B10/OpenJDK21U-jdk_x64_windows_hotspot_21.0.9_10.zip'
$ExpectedSha256 = '1c67df516e9795c0b09f5714bfe151da2e3cc988082042f5bbb60d75e4e63fb5'
$DownloadDir = Join-Path $ProjectDir '.toolchain/downloads'
$JdkDir = Join-Path $ProjectDir ".toolchain/jdk-$Version-windows-x64"
$Archive = Join-Path $DownloadDir $ArchiveName

New-Item -ItemType Directory -Force -Path $DownloadDir | Out-Null
if (-not (Test-Path -LiteralPath $Archive)) {
    Invoke-WebRequest -Uri $Url -OutFile $Archive -UseBasicParsing
}
$ActualSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Archive).Hash.ToLowerInvariant()
if ($ActualSha256 -ne $ExpectedSha256) {
    throw "Windows JDK SHA256 mismatch: expected $ExpectedSha256, got $ActualSha256"
}
if (-not (Test-Path -LiteralPath (Join-Path $JdkDir 'bin/java.exe'))) {
    if (Test-Path -LiteralPath $JdkDir) {
        Remove-Item -Recurse -Force -LiteralPath $JdkDir
    }
    $ExtractDir = Join-Path $ProjectDir ".toolchain/jdk-$Version"
    if (Test-Path -LiteralPath $ExtractDir) {
        Remove-Item -Recurse -Force -LiteralPath $ExtractDir
    }
    Expand-Archive -LiteralPath $Archive -DestinationPath (Join-Path $ProjectDir '.toolchain') -Force
    Move-Item -LiteralPath $ExtractDir -Destination $JdkDir
}
& (Join-Path $JdkDir 'bin/java.exe') -version
Write-Output "JAVA_HOME=$JdkDir"
