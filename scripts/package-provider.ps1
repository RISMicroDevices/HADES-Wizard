param(
    [Parameter(Mandatory = $true)][string]$Descriptor,
    [switch]$SkipBuild,
    [string]$Python = 'python3'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $PSScriptRoot
$Descriptor = (Resolve-Path -LiteralPath $Descriptor).ProviderPath
$DescriptorDir = Split-Path -Parent $Descriptor
$Provider = Get-Content -Raw -LiteralPath $Descriptor | ConvertFrom-Json
$EntryPoint = [string]$Provider.backend.entryPoint
if (-not $EntryPoint -or [IO.Path]::IsPathRooted($EntryPoint) -or $EntryPoint.Contains('..')) {
    throw 'Backend entry point must remain a relative path inside the descriptor directory'
}
$Launcher = Join-Path $DescriptorDir $EntryPoint
$BackendPackage = Join-Path $DescriptorDir 'hades_sram_gen'
if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf) -or
        -not (Test-Path -LiteralPath $BackendPackage -PathType Container)) {
    throw 'Descriptor backend package is incomplete'
}

if (-not $SkipBuild) {
    if (-not $env:JAVA_HOME) {
        $env:JAVA_HOME = Join-Path $ProjectDir '.toolchain/jdk-21.0.9+10-windows-x64'
    }
    $env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
    Push-Location $ProjectDir
    try {
        & (Join-Path $ProjectDir 'mvnw.cmd') clean verify
        if ($LASTEXITCODE -ne 0) {
            throw "Native Windows Maven verification failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

$ProductRoot = Join-Path $ProjectDir 'products/cn.rismd.hades.wizard.product/target/products/cn.rismd.hades.wizard.product'
$LinuxDir = Join-Path $ProductRoot 'linux/gtk/x86_64'
$WindowsDir = Join-Path $ProductRoot 'win32/win32/x86_64'
foreach ($ProductDir in @($LinuxDir, $WindowsDir)) {
    if (-not (Test-Path -LiteralPath $ProductDir -PathType Container)) {
        throw "Materialized product not found: $ProductDir"
    }
    $ProviderDir = Join-Path $ProductDir 'provider'
    if (Test-Path -LiteralPath $ProviderDir) {
        throw "Provider is already staged; run a clean build first: $ProviderDir"
    }
    New-Item -ItemType Directory -Path $ProviderDir | Out-Null
    Copy-Item -LiteralPath $Descriptor -Destination (Join-Path $ProviderDir 'provider.json')
    Copy-Item -LiteralPath $Launcher -Destination (Join-Path $ProviderDir $EntryPoint)
    Copy-Item -LiteralPath $BackendPackage -Destination $ProviderDir -Recurse
    & $Python -c 'import sys; sys.path.insert(0, sys.argv[1]); from hades_sram_gen.payload import stage_payload; stage_payload(sys.argv[2])' $DescriptorDir (Join-Path $ProviderDir 'payload')
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not stage the HADES-SRAM integration payload'
    }
}

$ArchiveDir = Join-Path $ProjectDir 'products/cn.rismd.hades.wizard.product/target/provider-products'
New-Item -ItemType Directory -Force -Path $ArchiveDir | Out-Null
$LinuxBase = Join-Path $ArchiveDir 'hades-wizard-sram-linux.gtk.x86_64'
$WindowsBase = Join-Path $ArchiveDir 'hades-wizard-sram-win32.win32.x86_64'
$LinuxArchive = "$LinuxBase.tar.gz"
$WindowsArchive = "$WindowsBase.zip"
& $Python -c "import shutil,sys; shutil.make_archive(sys.argv[1], 'gztar', sys.argv[2])" $LinuxBase $LinuxDir
if ($LASTEXITCODE -ne 0) {
    throw 'Could not archive the staged Linux product'
}
& $Python -c "import shutil,sys; shutil.make_archive(sys.argv[1], 'zip', sys.argv[2])" $WindowsBase $WindowsDir
if ($LASTEXITCODE -ne 0) {
    throw 'Could not archive the staged Windows product'
}
$Checksums = @($LinuxArchive, $WindowsArchive) | ForEach-Object {
    $Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_).Hash.ToLowerInvariant()
    "$Hash  $([IO.Path]::GetFileName($_))"
}
[IO.File]::WriteAllLines((Join-Path $ArchiveDir 'SHA256SUMS'), $Checksums, [Text.Encoding]::ASCII)
Write-Output "Staged Linux product: $LinuxDir"
Write-Output "Staged Windows product: $WindowsDir"
Write-Output "Archives: $ArchiveDir"
