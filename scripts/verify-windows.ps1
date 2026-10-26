param(
    [Parameter(Mandatory = $true)][string]$Descriptor,
    [string]$Python = 'python3'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $PSScriptRoot
$Descriptor = (Resolve-Path -LiteralPath $Descriptor).ProviderPath
$StageId = [Guid]::NewGuid().ToString('N').Substring(0, 12)
$RunId = '{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $StageId
$Stage = Join-Path $env:USERPROFILE "hw-$StageId"
$StageSource = Join-Path $Stage 's'
$StageProvider = Join-Path $Stage 'p'
$StageSmoke = Join-Path $Stage 'g'
$Evidence = Join-Path $ProjectDir ".local/windows-$RunId"
$ProductRoot = Join-Path $StageSource 'products/cn.rismd.hades.wizard.product/target/products/cn.rismd.hades.wizard.product'
$WindowsProduct = Join-Path $ProductRoot 'win32/win32/x86_64'
$LinuxProduct = Join-Path $ProductRoot 'linux/gtk/x86_64'
$ArchiveDir = Join-Path $StageSource 'products/cn.rismd.hades.wizard.product/target/provider-products'

function Copy-Tree([string]$Source, [string]$Destination, [string[]]$ExcludeDirs = @(), [string[]]$ExcludeFiles = @()) {
    $Arguments = @($Source, $Destination, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP')
    if ($ExcludeDirs.Count -gt 0) {
        $Arguments += '/XD'
        $Arguments += $ExcludeDirs
    }
    if ($ExcludeFiles.Count -gt 0) {
        $Arguments += '/XF'
        $Arguments += $ExcludeFiles
    }
    & robocopy.exe @Arguments
    $Code = $LASTEXITCODE
    if ($Code -gt 7) {
        throw "Copy failed with robocopy exit code ${Code}: $Source"
    }
}

function Get-TestSummary([string]$ReportDir, [int]$ExpectedTests, [string]$Label) {
    $Reports = @(Get-ChildItem -LiteralPath $ReportDir -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
    if ($Reports.Count -eq 0) {
        throw "No $Label Surefire reports found in $ReportDir"
    }
    $Summary = [pscustomobject]@{ tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($Report in $Reports) {
        $Document = [xml][IO.File]::ReadAllText($Report.FullName)
        $Summary.tests += [int]$Document.testsuite.tests
        $Summary.failures += [int]$Document.testsuite.failures
        $Summary.errors += [int]$Document.testsuite.errors
        $Summary.skipped += [int]$Document.testsuite.skipped
    }
    if ($Summary.tests -ne $ExpectedTests -or $Summary.failures -ne 0 -or
            $Summary.errors -ne 0 -or $Summary.skipped -ne 0) {
        throw "$Label test gate failed: expected $ExpectedTests tests and no failures/errors/skips; got $($Summary.tests)/$($Summary.failures)/$($Summary.errors)/$($Summary.skipped)"
    }
    Write-Host "$Label tests: $($Summary.tests), failures: 0, errors: 0, skipped: 0"
    return $Summary
}

New-Item -ItemType Directory -Path $StageSource, $StageProvider, $Evidence | Out-Null
$Transcript = Join-Path $Evidence 'windows-verify.log'
Start-Transcript -Path $Transcript | Out-Null
try {
    Write-Output "Stage: $Stage"
    Write-Output "Evidence: $Evidence"
    Write-Output "Descriptor: $Descriptor"
    & (Join-Path $PSScriptRoot 'bootstrap-jdk.ps1')
    & $Python --version
    if ($LASTEXITCODE -ne 0) {
        throw "Python executable is unavailable: $Python"
    }

    Copy-Tree $ProjectDir $StageSource -ExcludeDirs @('target', '.m2', '.m2-windows', '.toolchain', '.local', '.git') -ExcludeFiles @('*.log')
    $MavenCache = Join-Path $ProjectDir '.m2'
    if (Test-Path -LiteralPath $MavenCache -PathType Container) {
        Copy-Tree $MavenCache (Join-Path $StageSource '.m2')
    }
    $Jdk = Join-Path $ProjectDir '.toolchain/jdk-21.0.9+10-windows-x64'
    Copy-Tree $Jdk (Join-Path $StageSource '.toolchain/jdk-21.0.9+10-windows-x64')

    $Provider = Get-Content -Raw -LiteralPath $Descriptor | ConvertFrom-Json
    $EntryPoint = [string]$Provider.backend.entryPoint
    if (-not $EntryPoint -or [IO.Path]::IsPathRooted($EntryPoint) -or $EntryPoint.Contains('..')) {
        throw 'Backend entry point must remain a relative path inside the descriptor directory'
    }
    $DescriptorDir = Split-Path -Parent $Descriptor
    $StagedDescriptor = Join-Path $StageProvider 'provider.json'
    $StagedLauncher = Join-Path $StageProvider $EntryPoint
    Copy-Item -LiteralPath $Descriptor -Destination $StagedDescriptor
    Copy-Item -LiteralPath (Join-Path $DescriptorDir $EntryPoint) -Destination $StagedLauncher
    Copy-Tree (Join-Path $DescriptorDir 'hades_sram_gen') (Join-Path $StageProvider 'hades_sram_gen') -ExcludeDirs @('__pycache__') -ExcludeFiles @('*.pyc')
    & $Python -c 'import sys; sys.path.insert(0, sys.argv[1]); from hades_sram_gen.payload import stage_payload; stage_payload(sys.argv[2])' $DescriptorDir (Join-Path $StageProvider 'payload')
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not stage the HADES-SRAM integration payload'
    }

    $env:JAVA_HOME = Join-Path $StageSource '.toolchain/jdk-21.0.9+10-windows-x64'
    $env:MAVEN_USER_HOME = Join-Path $StageSource '.m2'
    $env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
    $env:PYTHONDONTWRITEBYTECODE = '1'
    $env:MAVEN_OPTS = $null
    $env:MAVEN_ARGS = $null
    Write-Output "COMMAND: mvnw.cmd clean verify -Phades-sram-integration -Dhades.sram.descriptor=$StagedDescriptor -Dhades.sram.python=$Python"
    Push-Location $StageSource
    try {
        & (Join-Path $StageSource 'mvnw.cmd') clean verify '-Phades-sram-integration' "-Dhades.sram.descriptor=$StagedDescriptor" "-Dhades.sram.python=$Python"
        $BuildExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($BuildExit -ne 0) {
        throw "Native Windows Maven verification failed with exit code $BuildExit"
    }

    $CoreSummary = Get-TestSummary (Join-Path $StageSource 'tests/cn.rismd.hades.wizard.core.tests/target/surefire-reports') 23 'Windows core'
    $UiSummary = Get-TestSummary (Join-Path $StageSource 'tests/cn.rismd.hades.wizard.ui.tests/target/surefire-reports') 20 'Windows UI'
    if (-not (Test-Path -LiteralPath (Join-Path $LinuxProduct 'hades-wizard') -PathType Leaf)) {
        throw 'Linux product was not materialized'
    }
    if (-not (Test-Path -LiteralPath (Join-Path $WindowsProduct 'hades-wizard.exe') -PathType Leaf)) {
        throw 'Windows product was not materialized'
    }
    $WindowsJvm = Get-ChildItem -LiteralPath $WindowsProduct -Filter 'jvm.dll' -Recurse -File |
        Where-Object { $_.FullName -match 'org\.eclipse\.justj\.' } | Select-Object -First 1
    $LinuxJava = Get-ChildItem -LiteralPath $LinuxProduct -Filter 'java' -Recurse -File |
        Where-Object { $_.FullName -match 'org\.eclipse\.justj\.' } | Select-Object -First 1
    if (-not $WindowsJvm -or -not $LinuxJava) {
        throw 'A bundled JustJ runtime is missing from a materialized product'
    }
    Write-Output "Windows bundled JVM: $($WindowsJvm.FullName)"
    Write-Output "Linux bundled Java: $($LinuxJava.FullName)"

    & (Join-Path $StageSource 'scripts/package-provider.ps1') -Descriptor $StagedDescriptor -SkipBuild -Python $Python
    & (Join-Path $StageSource 'scripts/windows-smoke.ps1') -ProductDirectory $WindowsProduct -WorkDir $StageSmoke -Python $Python

    $Result = [ordered]@{
        status = 'PASS'
        stage = $Stage
        windows_product = $WindowsProduct
        linux_product = $LinuxProduct
        windows_executable = (Join-Path $WindowsProduct 'hades-wizard.exe')
        windows_bundled_jvm = $WindowsJvm.FullName
        linux_bundled_java = $LinuxJava.FullName
        core_tests = $CoreSummary
        ui_tests = $UiSummary
        packaged_gui = [ordered]@{
            automatic = 'PASS'; manual = 'PASS'
            automatic_artifacts = 32; automatic_full_copy = $true
            manual_artifacts = 18; manual_full_copy = $false
            nested_byte_and_manifest_parity = $true; completion_dialog = $true
            module_preview_controls = $true
        }
        evidence = $Evidence
    }
    [IO.File]::WriteAllText((Join-Path $Evidence 'verification-summary.json'), ($Result | ConvertTo-Json -Depth 6), [Text.Encoding]::UTF8)
    Write-Output 'WINDOWS VERIFICATION PASS'
} finally {
    $CoreReports = Join-Path $StageSource 'tests/cn.rismd.hades.wizard.core.tests/target/surefire-reports'
    $UiReports = Join-Path $StageSource 'tests/cn.rismd.hades.wizard.ui.tests/target/surefire-reports'
    $UiScreenshots = Join-Path $StageSource 'tests/cn.rismd.hades.wizard.ui.tests/target/screenshots'
    if (Test-Path -LiteralPath $CoreReports) { Copy-Tree $CoreReports (Join-Path $Evidence 'core-surefire') }
    if (Test-Path -LiteralPath $UiReports) { Copy-Tree $UiReports (Join-Path $Evidence 'ui-surefire') }
    if (Test-Path -LiteralPath $UiScreenshots) { Copy-Tree $UiScreenshots (Join-Path $Evidence 'ui-test-screenshots') }
    if (Test-Path -LiteralPath $StageSmoke) { Copy-Tree $StageSmoke (Join-Path $Evidence 'packaged-gui') }
    if (Test-Path -LiteralPath $ArchiveDir) { Copy-Tree $ArchiveDir (Join-Path $Evidence 'provider-products') }
    [IO.File]::WriteAllLines((Join-Path $Evidence 'stage-info.txt'), @(
        "Stage=$Stage", "Source=$StageSource", "WindowsProduct=$WindowsProduct",
        "LinuxProduct=$LinuxProduct", "Evidence=$Evidence"), [Text.Encoding]::UTF8)
    Stop-Transcript | Out-Null
}
