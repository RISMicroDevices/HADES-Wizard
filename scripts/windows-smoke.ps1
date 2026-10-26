param(
    [string]$ProductDirectory,
    [string]$WorkDir,
    [string]$Python = 'python3'
)

$ErrorActionPreference = 'Stop'
$ProjectDir = Split-Path -Parent $PSScriptRoot
if (-not $ProductDirectory) {
    $ProductDirectory = Join-Path $ProjectDir 'products/cn.rismd.hades.wizard.product/target/products/cn.rismd.hades.wizard.product/win32/win32/x86_64'
}
if (-not $WorkDir) {
    $WorkDir = Join-Path $ProjectDir '.local/windows-smoke'
}
$ProductDirectory = (Resolve-Path -LiteralPath $ProductDirectory).Path
$Executable = Join-Path $ProductDirectory 'hades-wizard.exe'
$ProviderLauncher = Join-Path $ProductDirectory 'provider/hades-sram-gen'
if (-not (Test-Path -LiteralPath $Executable -PathType Leaf) -or
        -not (Test-Path -LiteralPath $ProviderLauncher -PathType Leaf)) {
    throw 'The Windows product or its default staged provider is missing'
}
$ScreenshotDir = Join-Path $WorkDir 'screenshots'
$HelperDir = Join-Path $WorkDir 'helpers'
New-Item -ItemType Directory -Force -Path $WorkDir, $ScreenshotDir, $HelperDir | Out-Null
$env:PYTHONDONTWRITEBYTECODE = '1'

Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Drawing
Add-Type -Namespace Hades -Name Native -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll", EntryPoint="SendMessageW")]
public static extern System.IntPtr SendMessage(System.IntPtr hWnd, uint msg, System.IntPtr wParam, System.IntPtr lParam);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern bool PostMessage(System.IntPtr hWnd, uint msg, System.IntPtr wParam, System.IntPtr lParam);
[System.Runtime.InteropServices.DllImport("user32.dll", EntryPoint="SendMessageW", CharSet=System.Runtime.InteropServices.CharSet.Unicode)]
public static extern System.IntPtr SendMessageText(System.IntPtr hWnd, uint msg, System.IntPtr wParam, string lParam);
[System.Runtime.InteropServices.DllImport("user32.dll", EntryPoint="SendMessageW", CharSet=System.Runtime.InteropServices.CharSet.Unicode)]
public static extern System.IntPtr SendMessageBuffer(System.IntPtr hWnd, uint msg, System.IntPtr wParam, System.Text.StringBuilder lParam);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern System.IntPtr GetParent(System.IntPtr hWnd);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern int GetDlgCtrlID(System.IntPtr hWnd);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern System.IntPtr GetMenu(System.IntPtr hWnd);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern int GetMenuItemCount(System.IntPtr hMenu);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern uint GetMenuItemID(System.IntPtr hMenu, int position);
[System.Runtime.InteropServices.DllImport("user32.dll", CharSet=System.Runtime.InteropServices.CharSet.Unicode)]
public static extern int GetMenuString(System.IntPtr hMenu, uint item, System.Text.StringBuilder text, int max, uint flags);
[System.Runtime.InteropServices.DllImport("user32.dll", SetLastError=true)]
public static extern bool PrintWindow(System.IntPtr hWnd, System.IntPtr hdcBlt, uint nFlags);
[System.Runtime.InteropServices.DllImport("user32.dll")]
public static extern bool SetForegroundWindow(System.IntPtr hWnd);
[System.Runtime.InteropServices.DllImport("user32.dll", SetLastError=true)]
public static extern bool SetWindowPos(System.IntPtr hWnd, System.IntPtr hWndInsertAfter,
    int x, int y, int cx, int cy, uint flags);
'@

function Wait-Window([string]$Title, [int]$ProcessId, [int]$TimeoutSeconds = 45) {
    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $topWindows = $root.FindAll([System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition)
        foreach ($top in $topWindows) {
            if ($top.Current.ProcessId -ne $ProcessId) {
                continue
            }
            $windows = @($top) + @($top.FindAll([System.Windows.Automation.TreeScope]::Descendants,
                [System.Windows.Automation.Condition]::TrueCondition))
            foreach ($window in $windows) {
                if ($window.Current.ControlType -eq [System.Windows.Automation.ControlType]::Window -and
                        $window.Current.Name -and $window.Current.Name.StartsWith($Title,
                        [System.StringComparison]::OrdinalIgnoreCase)) {
                    return $window
                }
            }
        }
        Start-Sleep -Milliseconds 300
    }
    throw "Window not found in owned process ${ProcessId}: $Title"
}

function Find-NativeClass($Window, [string]$ClassName) {
    return $Window.FindAll([System.Windows.Automation.TreeScope]::Descendants,
        (New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ClassNameProperty, $ClassName)))
}

function Find-Button($Window, [string]$Name) {
    $condition = New-Object System.Windows.Automation.AndCondition(
        (New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::ClassNameProperty, 'Button')),
        (New-Object System.Windows.Automation.PropertyCondition(
            [System.Windows.Automation.AutomationElement]::NameProperty, $Name)))
    $button = $Window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
    if (-not $button) {
        throw "Button not found: $Name"
    }
    return $button
}

function Invoke-Button($Window, [string]$Name) {
    $button = Find-Button $Window $Name
    $handle = [IntPtr]($button.Current.NativeWindowHandle)
    if ($handle -eq [IntPtr]::Zero) {
        throw "Button has no native handle: $Name"
    }
    [void][Hades.Native]::SendMessage($handle, 0x00F5, [IntPtr]::Zero, [IntPtr]::Zero)
}

function Invoke-EditConfiguration($Main) {
    $handle = [IntPtr]($Main.Current.NativeWindowHandle)
    $menu = [Hades.Native]::GetMenu($handle)
    $count = [Hades.Native]::GetMenuItemCount($menu)
    if ($menu -eq [IntPtr]::Zero -or $count -le 0) {
        throw 'Native File menu not found'
    }
    $editId = 0
    for ($i = 0; $i -lt $count; $i++) {
        $text = New-Object System.Text.StringBuilder 512
        [void][Hades.Native]::GetMenuString($menu, [uint32]$i, $text, $text.Capacity, 0x400)
        if ($text.ToString() -match 'Edit.*Configuration') {
            $editId = [Hades.Native]::GetMenuItemID($menu, $i)
        }
    }
    if ($editId -eq 0) {
        throw 'Edit Configuration native menu action not found'
    }
    Write-Output "Invoking Edit Configuration native menu ID $editId"
    if (-not [Hades.Native]::PostMessage($handle, 0x0111, [IntPtr]([int64]$editId), [IntPtr]::Zero)) {
        throw 'Could not post Edit Configuration menu action'
    }
}

function Get-Edits($Window) {
    return Find-NativeClass $Window 'Edit'
}

function Set-EditValue($Window, [int]$Index, [string]$Value) {
    $edits = @(Get-Edits $Window)
    if ($Index -lt 0 -or $Index -ge $edits.Count) {
        throw "Edit index $Index is unavailable; only $($edits.Count) edit controls"
    }
    $handle = [IntPtr]($edits[$Index].Current.NativeWindowHandle)
    [void][Hades.Native]::SendMessageText($handle, 0x000C, [IntPtr]::Zero, $Value)
    $parent = [Hades.Native]::GetParent($handle)
    $id = [Hades.Native]::GetDlgCtrlID($handle)
    if ($parent -ne [IntPtr]::Zero -and $id -ne 0) {
        $wParam = [IntPtr](($id -band 0xffff) -bor (0x0300 -shl 16))
        [void][Hades.Native]::SendMessage($parent, 0x0111, $wParam, $handle)
    }
}

function Get-ComboText($Window) {
    $combos = @(Find-NativeClass $Window 'ComboBox')
    if ($combos.Count -eq 0) {
        throw 'No combo box found'
    }
    $handle = [IntPtr]($combos[0].Current.NativeWindowHandle)
    $selection = [int]([Hades.Native]::SendMessage($handle, 0x0147, [IntPtr]::Zero, [IntPtr]::Zero).ToInt64())
    if ($selection -lt 0) {
        throw 'Native combo box has no selection'
    }
    $text = New-Object System.Text.StringBuilder 512
    [void][Hades.Native]::SendMessageBuffer($handle, 0x0148, [IntPtr]$selection, $text)
    if ($text.Length -eq 0) {
        throw 'Could not read selected native combo box item'
    }
    return $text.ToString()
}

function Capture-Window($Window, [string]$Name) {
    $handle = [IntPtr]($Window.Current.NativeWindowHandle)
    if ($handle -ne [IntPtr]::Zero) {
        [void][Hades.Native]::SetWindowPos($handle, [IntPtr]::Zero, 80, 60, 0, 0, 0x0003)
        [void][Hades.Native]::SetForegroundWindow($handle)
        Start-Sleep -Milliseconds 250
    }
    $rectangle = $Window.Current.BoundingRectangle
    $width = [Math]::Max(1, [int]$rectangle.Width)
    $height = [Math]::Max(1, [int]$rectangle.Height)
    $bitmap = New-Object System.Drawing.Bitmap $width, $height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $captureMethod = 'CopyFromScreen'
        try {
            $graphics.CopyFromScreen([int]$rectangle.X, [int]$rectangle.Y, 0, 0,
                (New-Object System.Drawing.Size $width, $height))
        } catch {
            $screenError = $_.Exception
            if ($handle -eq [IntPtr]::Zero) {
                throw
            }
            $graphics.Clear([System.Drawing.Color]::White)
            $hdc = $graphics.GetHdc()
            try {
                $printed = [Hades.Native]::PrintWindow($handle, $hdc, 2)
            } finally {
                $graphics.ReleaseHdc($hdc)
            }
            if (-not $printed) {
                throw "CopyFromScreen failed ($screenError); PrintWindow also failed"
            }
            $captureMethod = 'PrintWindow'
        }
        $colors = [System.Collections.Generic.HashSet[int]]::new()
        $stepX = [Math]::Max(1, [int]($width / 8))
        $stepY = [Math]::Max(1, [int]($height / 8))
        for ($x = 0; $x -lt $width; $x += $stepX) {
            for ($y = 0; $y -lt $height; $y += $stepY) {
                [void]$colors.Add($bitmap.GetPixel($x, $y).ToArgb())
            }
        }
        if ($colors.Count -lt 2) {
            throw "Capture $Name is blank ($captureMethod)"
        }
        $path = Join-Path $ScreenshotDir $Name
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        Write-Output "Screenshot: $path ($captureMethod)"
    } finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Find-NamedElement($Window, [string]$Name) {
    $condition = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::NameProperty, $Name)
    return $Window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
}

function Get-NativeCheckBoxState($Window, [string]$Name) {
    $button = Find-Button $Window $Name
    $handle = [IntPtr]($button.Current.NativeWindowHandle)
    if ($handle -eq [IntPtr]::Zero) {
        throw "Checkbox has no native handle: $Name"
    }
    $state = [int]([Hades.Native]::SendMessage($handle, 0x00F0, [IntPtr]::Zero, [IntPtr]::Zero).ToInt64())
    if ($state -ne 0 -and $state -ne 1) {
        throw "Checkbox is not in a two-state checked/unchecked state: $Name ($state)"
    }
    return ($state -eq 1)
}

function Assert-NativeCheckBoxState($Window, [string]$Name, [bool]$Expected) {
    $actual = Get-NativeCheckBoxState $Window $Name
    if ($actual -ne $Expected) {
        throw "Checkbox '$Name' state $actual does not match expected $Expected"
    }
}

function Assert-ModulePreview($Window, [string]$Location, $Evidence) {
    $preview = Find-NamedElement $Window 'Module Port Preview'
    if (-not $preview) {
        throw "$Location does not expose Module Port Preview"
    }
    $windowBounds = $Window.Current.BoundingRectangle
    $previewBounds = $preview.Current.BoundingRectangle
    if ($previewBounds.IsEmpty -or $previewBounds.Width -le 0 -or $previewBounds.Height -le 0) {
        throw "$Location Module Port Preview has no visible bounds"
    }
    if ($windowBounds.Width -gt 0 -and
            $previewBounds.Left -ge ($windowBounds.Left + $windowBounds.Width / 2)) {
        throw "$Location Module Port Preview is not on the left side"
    }
    $Evidence.preview_locations += $Location
}

function Test-PreviewControls($Window, [string]$Prefix, $Evidence) {
    $preview = Find-NamedElement $Window 'Module Port Preview'
    if (-not $preview) {
        throw "$Prefix cannot exercise missing Module Port Preview controls"
    }
    foreach ($Name in @('Zoom In', 'Zoom Out', 'Fit', 'Reset')) {
        Invoke-Button $preview $Name
        Start-Sleep -Milliseconds 400
        $Evidence.buttons_exercised += $Name
        $SafeName = $Name.ToLowerInvariant().Replace(' ', '-')
        $Screenshot = "$Prefix-preview-$SafeName.png"
        Capture-Window $Window $Screenshot
        $Evidence.preview_screenshots += $Screenshot
    }
}

function Test-PreviewSignalTree($Window, [string]$Prefix, $Evidence) {
    $preview = Find-NamedElement $Window 'Module Port Preview'
    if (-not $preview) {
        throw "$Prefix cannot inspect missing Module Port Preview Signal Tree"
    }
    $tab = Find-NamedElement $preview 'Signal Tree'
    if (-not $tab) {
        $Evidence.accessible_tree = $false
        $Evidence.limitations += 'The packaged preview did not expose an accessible Signal Tree tab; group expansion was not available to UIA.'
        Write-Host "$Prefix accessible Signal Tree tab is not exposed; skipping UIA group expansion"
        return
    }
    $Evidence.accessible_tree = $true
    $selection = $tab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern)
    $selection.Select()
    Start-Sleep -Milliseconds 500
    $tree = Find-NamedElement $preview 'Module signal tree'
    if (-not $tree) {
        throw "$Prefix Signal Tree tab did not expose the Module signal tree"
    }
    foreach ($GroupName in @('SRAM Port 0', 'AXI4 Backend (m_axi)', 'AXI4-Lite CSR (s_axil)')) {
        $group = Find-NamedElement $tree $GroupName
        if (-not $group) {
            throw "$Prefix Signal Tree is missing group: $GroupName"
        }
        $expansion = $group.GetCurrentPattern([System.Windows.Automation.ExpandCollapsePattern]::Pattern)
        if ($expansion.Current.ExpandCollapseState -ne [System.Windows.Automation.ExpandCollapseState]::Expanded) {
            $expansion.Expand()
            Start-Sleep -Milliseconds 300
        }
        if ($expansion.Current.ExpandCollapseState -ne [System.Windows.Automation.ExpandCollapseState]::Expanded) {
            throw "$Prefix Signal Tree group did not expand: $GroupName"
        }
        $signals = @($group.FindAll([System.Windows.Automation.TreeScope]::Children,
            [System.Windows.Automation.Condition]::TrueCondition))
        if ($signals.Count -eq 0) {
            throw "$Prefix Signal Tree group has no expanded signal rows: $GroupName"
        }
        $Evidence.expanded_groups += [pscustomobject]@{
            label = $GroupName
            expanded = $true
            signal_rows = $signals.Count
        }
    }
    $Screenshot = "$Prefix-preview-expanded-groups.png"
    Capture-Window $Window $Screenshot
    $Evidence.preview_screenshots += $Screenshot
}

function Complete-Generation(
        $Process,
        [string]$Output,
        [int]$ExpectedFileCount,
        [bool]$FullCopy,
        [string]$Screenshot,
        $Evidence) {
    $completion = Wait-Window 'Generation Complete' $Process.Id
    Capture-Window $completion $Screenshot
    $windowPattern = $completion.GetCurrentPattern([System.Windows.Automation.WindowPattern]::Pattern)
    if (-not $windowPattern.Current.IsModal) {
        throw 'Generation Complete was not reported as a modal window'
    }
    $completionText = (@($completion.FindAll([System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition) | ForEach-Object { $_.Current.Name }) -join "`n")
    $ExpectedMessage = "Generated $ExpectedFileCount managed files successfully."
    if (-not $completionText.Contains($ExpectedMessage)) {
        throw "Generation Complete did not report '$ExpectedMessage': $completionText"
    }
    if (-not $completionText.Contains($Output)) {
        throw "Generation Complete did not report destination ${Output}: $completionText"
    }
    if ($FullCopy) {
        $ModeMessage = 'The complete integration source payload was copied.'
    } else {
        $ModeMessage = 'Only configuration-specific artifacts were generated; maintained sources were not copied.'
    }
    if (-not $completionText.Contains($ModeMessage)) {
        throw "Generation Complete did not report full_copy=$FullCopy correctly: $completionText"
    }
    Invoke-Button $completion 'OK'
    Start-Sleep -Milliseconds 700
    $Evidence.generation_complete = [ordered]@{
        verified = $true
        modal = $true
        expected_files = $ExpectedFileCount
        full_copy = $FullCopy
        destination = $Output
        screenshot = $Screenshot
        dismissed_with_ok = $true
    }
}

function Start-OwnedProduct([string[]]$Arguments) {
    $Quoted = @($Arguments | ForEach-Object {
        if ($_ -match '\s') { '"{0}"' -f $_ } else { $_ }
    })
    Write-Host "Launching $Executable $($Quoted -join ' ')"
    return Start-Process -FilePath $Executable -ArgumentList ($Quoted -join ' ') -PassThru
}

function Assert-BundledRuntime($Process, [string]$Label) {
    $AllProcesses = @(Get-CimInstance Win32_Process)
    $ProcessIds = @($Process.Id)
    for ($i = 0; $i -lt $ProcessIds.Count; $i++) {
        $ProcessIds += @($AllProcesses | Where-Object { $_.ParentProcessId -eq $ProcessIds[$i] } |
            ForEach-Object { [int]$_.ProcessId })
    }
    $RuntimePaths = @()
    foreach ($Id in $ProcessIds) {
        try {
            $RuntimePaths += @((Get-Process -Id $Id -ErrorAction Stop).Modules |
                Where-Object { $_.ModuleName -ieq 'jvm.dll' } | ForEach-Object { $_.FileName })
        } catch {}
        $RuntimePaths += @($AllProcesses | Where-Object {
            $_.ProcessId -eq $Id -and $_.Name -match '^javaw?\.exe$'
        } | ForEach-Object { $_.ExecutablePath })
    }
    $Prefix = [IO.Path]::GetFullPath($ProductDirectory).TrimEnd('\') + '\'
    $Bundled = @($RuntimePaths | Where-Object {
        $_ -and [IO.Path]::GetFullPath($_).StartsWith($Prefix, [StringComparison]::OrdinalIgnoreCase)
    } | Select-Object -Unique)
    if ($Bundled.Count -eq 0) {
        throw "$Label product did not load its bundled Java runtime"
    }
    [IO.File]::WriteAllLines((Join-Path $WorkDir "$Label-runtime.txt"),
        @("Executable=$Executable", "ProcessId=$($Process.Id)") + $Bundled, [Text.Encoding]::UTF8)
    Write-Host "$Label bundled runtime: $($Bundled -join ', ')"
}

function Stop-OwnedProcessTree([int]$ProcessId) {
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-OwnedProcessTree $child.ProcessId
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Invoke-DirectGenerate([string]$Config, [string]$Output, [bool]$FullCopy = $true) {
    $script = @'
import json, os, subprocess, sys
launcher, config_path, output, full_copy_text = sys.argv[1:]
full_copy = json.loads(full_copy_text)
assert type(full_copy) is bool, full_copy
with open(config_path, encoding='utf-8') as stream:
    config = json.load(stream)
assert 'full_copy' not in config, 'full_copy must remain a generation option'
process = subprocess.Popen([sys.executable, launcher, 'protocol'], stdin=subprocess.PIPE,
    stdout=subprocess.PIPE, text=True, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
request = {'protocol_version': 1, 'id': 1, 'operation': 'generate', 'config': config,
    'output': output, 'force': False, 'full_copy': full_copy}
process.stdin.write(json.dumps(request, separators=(',', ':')) + '\n')
process.stdin.flush()
response = json.loads(process.stdout.readline())
assert response['ok'], response
result = response['result']
assert result['outcome'] == 'generated' and result['status'] == 0, result
process.stdin.close()
assert process.wait() == 0
'@
    $helper = Join-Path $HelperDir 'direct_generate.py'
    Set-Content -LiteralPath $helper -Value $script -Encoding UTF8
    $FullCopyText = ([string]$FullCopy).ToLowerInvariant()
    & $Python $helper $ProviderLauncher $Config $Output $FullCopyText
    if ($LASTEXITCODE -ne 0) {
        throw "Direct reference generation failed for $Config (full_copy=$FullCopyText)"
    }
}

function Get-RelativeArtifactPath([string]$Root, [string]$Path) {
    $RootPath = [IO.Path]::GetFullPath($Root)
    $Prefix = $RootPath.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $FullPath = [IO.Path]::GetFullPath($Path)
    if (-not $FullPath.StartsWith($Prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Artifact is outside output root ${Root}: $FullPath"
    }
    return $FullPath.Substring($Prefix.Length).Replace('\', '/')
}

function Resolve-ArtifactPath([string]$Root, [string]$RelativePath, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath)) {
        throw "$Label path is empty or not output-root-relative: $RelativePath"
    }
    $RootPath = [IO.Path]::GetFullPath($Root)
    $Prefix = $RootPath.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $Resolved = [IO.Path]::GetFullPath((Join-Path $RootPath $RelativePath))
    if (-not $Resolved.StartsWith($Prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label path escapes output root: $RelativePath"
    }
    return $Resolved
}

function Read-FileList([string]$Root, [string]$RelativePath, [string]$Label) {
    $Path = Resolve-ArtifactPath $Root $RelativePath $Label
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Label filelist is missing: $RelativePath"
    }
    $Entries = @()
    foreach ($Line in [IO.File]::ReadAllLines($Path)) {
        $Entry = $Line.Trim()
        if (-not $Entry -or $Entry.StartsWith('#') -or $Entry.StartsWith('//')) {
            continue
        }
        $Resolved = Resolve-ArtifactPath $Root $Entry "$Label $RelativePath"
        if (-not (Test-Path -LiteralPath $Resolved -PathType Leaf)) {
            throw "$Label $RelativePath entry does not exist from the generated root: $Entry"
        }
        $Entries += Get-RelativeArtifactPath $Root $Resolved
    }
    if ($Entries.Count -eq 0) {
        throw "$Label $RelativePath has no source entries"
    }
    if (@($Entries | Sort-Object -CaseSensitive -Unique).Count -ne $Entries.Count) {
        throw "$Label $RelativePath contains duplicate source entries"
    }
    return $Entries
}

function Assert-ArtifactParity(
        [string]$Generated,
        [string]$Reference,
        [string]$Label,
        [int]$ExpectedFileCount,
        [int]$ExpectedPortCount,
        [bool]$FullCopy = $true,
        [string[]]$RequiredPaths = @(),
        [string[]]$ForbiddenPaths = @(),
        $PreviewEvidence = $null) {
    $ExpectedManifestCount = $ExpectedFileCount - 1
    $Files = @(Get-ChildItem -LiteralPath $Generated -File -Recurse |
        Sort-Object { Get-RelativeArtifactPath $Generated $_.FullName })
    if ($Files.Count -ne $ExpectedFileCount) {
        throw "$Label produced $($Files.Count) recursive artifacts instead of $ExpectedFileCount"
    }
    $GeneratedPaths = @($Files | ForEach-Object { Get-RelativeArtifactPath $Generated $_.FullName })
    $AllRequiredPaths = @($RequiredPaths)
    for ($i = 0; $i -lt $ExpectedPortCount; $i++) {
        $AllRequiredPaths += "constraints/sram${i}_strict.xdc"
    }
    foreach ($Required in $AllRequiredPaths) {
        if (-not ($GeneratedPaths -ccontains $Required)) {
            throw "$Label is missing required relative artifact: $Required"
        }
    }
    $AllForbiddenPaths = @($ForbiddenPaths) + "constraints/sram${ExpectedPortCount}_strict.xdc"
    foreach ($Forbidden in $AllForbiddenPaths) {
        if ($GeneratedPaths -contains $Forbidden) {
            throw "$Label contains forbidden relative artifact: $Forbidden"
        }
    }
    if (-not ($GeneratedPaths -ccontains 'config.json')) {
        throw "$Label is missing root config.json"
    }
    $GeneratedConfig = Join-Path $Generated 'config.json'
    $ConfigHashBefore = (Get-FileHash -Algorithm SHA256 -LiteralPath $GeneratedConfig).Hash
    Invoke-DirectGenerate -Config $GeneratedConfig -Output $Reference -FullCopy $FullCopy
    $ConfigHashAfter = (Get-FileHash -Algorithm SHA256 -LiteralPath $GeneratedConfig).Hash
    if ($ConfigHashAfter -ne $ConfigHashBefore) {
        throw "$Label direct reference generation modified its read-only input config.json"
    }
    $ReferenceFiles = @(Get-ChildItem -LiteralPath $Reference -File -Recurse |
        Sort-Object { Get-RelativeArtifactPath $Reference $_.FullName })
    if ($ReferenceFiles.Count -ne $ExpectedFileCount) {
        throw "$Label direct reference produced $($ReferenceFiles.Count) recursive artifacts instead of $ExpectedFileCount"
    }
    $ReferencePaths = @($ReferenceFiles | ForEach-Object { Get-RelativeArtifactPath $Reference $_.FullName })
    $PathDifference = @(Compare-Object -ReferenceObject $ReferencePaths -DifferenceObject $GeneratedPaths -CaseSensitive)
    if ($PathDifference.Count -ne 0) {
        $Details = ($PathDifference | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join ', '
        throw "$Label recursive relative-path mismatch: $Details"
    }
    $ReferenceByPath = @{}
    foreach ($File in $ReferenceFiles) {
        $ReferenceByPath[(Get-RelativeArtifactPath $Reference $File.FullName)] = $File
    }
    $Comparison = @()
    foreach ($File in $Files) {
        $RelativePath = Get-RelativeArtifactPath $Generated $File.FullName
        $ReferenceFile = $ReferenceByPath[$RelativePath]
        $ActualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $File.FullName).Hash.ToLowerInvariant()
        $ReferenceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ReferenceFile.FullName).Hash.ToLowerInvariant()
        if ($ActualHash -ne $ReferenceHash) {
            throw "$Label byte mismatch: $RelativePath"
        }
        $Comparison += [pscustomobject]@{
            name = $RelativePath
            gui_sha256 = $ActualHash
            reference_sha256 = $ReferenceHash
        }
    }
    $Manifest = Get-Content -Raw -LiteralPath (Join-Path $Generated 'manifest.json') | ConvertFrom-Json
    $ManifestEntries = @($Manifest.files)
    if ($ManifestEntries.Count -ne $ExpectedManifestCount) {
        throw "$Label manifest lists $($ManifestEntries.Count) managed files instead of $ExpectedManifestCount"
    }
    $ManifestPaths = @()
    foreach ($Item in $ManifestEntries) {
        $ItemPath = [string]$Item.path
        $Resolved = Resolve-ArtifactPath $Generated $ItemPath "$Label manifest"
        if (-not (Test-Path -LiteralPath $Resolved -PathType Leaf)) {
            throw "$Label manifest entry is missing: $ItemPath"
        }
        $ManifestPaths += Get-RelativeArtifactPath $Generated $Resolved
        $ActualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Resolved).Hash
        if ($ActualHash -ine $Item.sha256) {
            throw "$Label manifest hash mismatch: $ItemPath"
        }
    }
    if (@($ManifestPaths | Sort-Object -CaseSensitive -Unique).Count -ne $ManifestPaths.Count) {
        throw "$Label manifest contains duplicate relative paths"
    }
    $ManagedPaths = @($GeneratedPaths | Where-Object { $_ -cne 'manifest.json' })
    $ManifestDifference = @(Compare-Object -ReferenceObject $ManagedPaths -DifferenceObject $ManifestPaths -CaseSensitive)
    if ($ManifestDifference.Count -ne 0) {
        $Details = ($ManifestDifference | ForEach-Object { "$($_.SideIndicator)$($_.InputObject)" }) -join ', '
        throw "$Label manifest does not list every managed artifact exactly once: $Details"
    }
    foreach ($Root in @($Generated, $Reference)) {
        $ConfigObject = Get-Content -Raw -LiteralPath (Join-Path $Root 'config.json') | ConvertFrom-Json
        if ($ConfigObject.PSObject.Properties.Name -contains 'full_copy') {
            throw "$Label serialized generation-only full_copy into $Root/config.json"
        }
    }
    $ExpectedWrapperEntries = @('logical/hades_sram_config_pkg.sv', 'logical/hades_sram_user.sv')
    $WrapperEntries = @(Read-FileList $Generated 'wrapper.f' $Label)
    if (($WrapperEntries -join "`n") -cne ($ExpectedWrapperEntries -join "`n")) {
        throw "$Label wrapper.f must list only the generated package and wrapper in compile order"
    }
    $StaticEntries = $null
    if ($FullCopy) {
        $StaticEntries = @(Read-FileList $Generated 'static_rtl.f' $Label)
        if ($StaticEntries.Count -ne 12) {
            throw "$Label static_rtl.f lists $($StaticEntries.Count) core sources instead of 12"
        }
        $Overlap = @($StaticEntries | Where-Object { $WrapperEntries -ccontains $_ })
        if ($Overlap.Count -ne 0) {
            throw "$Label wrapper and core source lists overlap: $($Overlap -join ', ')"
        }
        $ExpectedCorePaths = @($GeneratedPaths | Where-Object {
            $_ -like 'logical/*.sv' -and -not ($WrapperEntries -ccontains $_)
        })
        $CoreDifference = @(Compare-Object -ReferenceObject $ExpectedCorePaths -DifferenceObject $StaticEntries -CaseSensitive)
        if ($CoreDifference.Count -ne 0) {
            throw "$Label static_rtl.f does not list exactly the copied core SystemVerilog sources"
        }
    } elseif ($GeneratedPaths -contains 'static_rtl.f') {
        throw "$Label emitted static_rtl.f with full_copy=false"
    }
    $SafeLabel = ($Label.ToLowerInvariant() -replace '[^a-z0-9]+', '-').Trim('-')
    $Evidence = [ordered]@{
        label = $Label
        full_copy = $FullCopy
        expected_artifacts = $ExpectedFileCount
        gui_artifacts = $Files.Count
        reference_artifacts = $ReferenceFiles.Count
        expected_manifest_entries = $ExpectedManifestCount
        manifest_entries = $ManifestEntries.Count
        recursive_relative_path_parity = $true
        byte_parity = $true
        manifest_parity = $true
        direct_reference_read_only = $true
        config_full_copy_absent = $true
        filelists = [ordered]@{
            output_root_relative_and_existing = $true
            wrapper = $WrapperEntries
            static_rtl = $StaticEntries
        }
        strict_xdc_ports = $ExpectedPortCount
        extra_strict_xdc_absent = $true
        preview = $PreviewEvidence
        files = $Comparison
    }
    [IO.File]::WriteAllText((Join-Path $WorkDir "$SafeLabel-parity.json"),
        ($Evidence | ConvertTo-Json -Depth 8), [Text.Encoding]::UTF8)
    Write-Output "$Label generated $($Files.Count) artifacts with recursive byte and manifest parity (full_copy=$FullCopy)"
}

try {
    $defaultOutput = Join-Path $WorkDir 'default-output'
    $defaultReference = Join-Path $WorkDir 'default-reference'
    $manualOutput = Join-Path $WorkDir 'manual-output'
    $manualReference = Join-Path $WorkDir 'manual-reference'
    foreach ($Output in @($defaultOutput, $defaultReference, $manualOutput, $manualReference)) {
        if (Test-Path -LiteralPath $Output) {
            throw "Smoke output must start fresh: $Output"
        }
    }
    $defaultPreview = [ordered]@{
        preview_locations = @()
        buttons_exercised = @()
        accessible_tree = $null
        expanded_groups = @()
        preview_screenshots = @()
        generation_complete = $null
        limitations = @('Native canvas drag/pan and internal zoom, pan, and transform state remain in the SWTBot suite; packaged smoke exercises native buttons and the UIA tree only.')
    }
    $defaultRequiredPaths = @(
        'wrapper.f',
        'static_rtl.f',
        'logical/hades_sram_top.sv',
        'logical/hades_sram_config_pkg.sv',
        'logical/hades_sram_user.sv',
        'report/analysis.json',
        'report/report.html',
        'constraints/sram0_strict.xdc',
        'constraints/hades_sram_clocks_sync.xdc',
        'constraints/hades_sram_clocks_async.xdc',
        'docs/INTEGRATION_GUIDE.md',
        'docs/csr_regmap.md'
    )
    $process = Start-OwnedProduct @('--output', $defaultOutput, '--python', $Python)
    try {
        $wizard = Wait-Window 'HADES-SRAM Configuration' $process.Id
        Assert-BundledRuntime $process 'default'
        Assert-ModulePreview $wizard 'Automatic Integration' $defaultPreview
        Capture-Window $wizard 'default-01-integration.png'
        Invoke-Button $wizard 'Next >'
        Start-Sleep -Milliseconds 700
        Assert-ModulePreview $wizard 'Automatic Ports' $defaultPreview
        Capture-Window $wizard 'default-02-ports-automatic.png'
        Invoke-Button $wizard 'Next >'
        Start-Sleep -Milliseconds 700
        Assert-ModulePreview $wizard 'Automatic Clock' $defaultPreview
        Capture-Window $wizard 'default-03-clock.png'
        Invoke-Button $wizard 'Next >'
        Start-Sleep -Milliseconds 700
        Assert-ModulePreview $wizard 'Automatic Maintenance' $defaultPreview
        Capture-Window $wizard 'default-04-maintenance.png'
        Invoke-Button $wizard 'Next >'
        Start-Sleep -Milliseconds 700
        Assert-ModulePreview $wizard 'Automatic Review' $defaultPreview
        Capture-Window $wizard 'default-05-review.png'
        Assert-NativeCheckBoxState $wizard 'Copy complete integration sources' $true
        $defaultPreview.full_copy_default_checked = $true
        Test-PreviewControls $wizard 'default' $defaultPreview
        Test-PreviewSignalTree $wizard 'default' $defaultPreview
        $memoryTab = Find-NamedElement $wizard 'Memory Map'
        if (-not $memoryTab) {
            throw 'Automatic Review is missing the Memory Map tab'
        }
        $memoryTab.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select()
        Start-Sleep -Milliseconds 500
        Capture-Window $wizard 'default-05b-memory-map.png'
        $defaultPreview.preview_screenshots += 'default-05b-memory-map.png'
        Invoke-Button $wizard 'Finish'
        Complete-Generation $process $defaultOutput 32 $true 'default-06-generation-complete.png' $defaultPreview
        Assert-ArtifactParity -Generated $defaultOutput -Reference $defaultReference -Label 'Default packaged GUI' `
            -ExpectedFileCount 32 -ExpectedPortCount 1 -FullCopy $true -RequiredPaths $defaultRequiredPaths `
            -PreviewEvidence $defaultPreview
    } finally {
        Stop-OwnedProcessTree $process.Id
    }

    $manualConfig = Join-Path $WorkDir 'manual-config.json'
    $manualScript = @'
import json, os, subprocess, sys
launcher, destination = sys.argv[1:]
process = subprocess.Popen([sys.executable, launcher, 'protocol'], stdin=subprocess.PIPE,
    stdout=subprocess.PIPE, text=True, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
def call(i, operation, **payload):
    process.stdin.write(json.dumps({'protocol_version': 1, 'id': i, 'operation': operation, **payload},
        separators=(',', ':')) + '\n')
    process.stdin.flush()
    response = json.loads(process.stdout.readline())
    assert response['ok'], response
    return response['result']
config = call(1, 'schema')['defaults']
config = call(2, 'resize', config=config, count=4)['config']
for index, width in enumerate([40, 1, 300, 512]):
    config['ports'][index]['width'] = width
    config['ports'][index]['byte_enable'] = True
config = call(3, 'allocate', config=config, depths=[17, 18, 19, 20])['config']
with open(destination, 'w', encoding='utf-8', newline='\n') as stream:
    json.dump(config, stream, indent=2)
    stream.write('\n')
process.stdin.close()
assert process.wait() == 0
'@
    $manualHelper = Join-Path $HelperDir 'make_manual.py'
    Set-Content -LiteralPath $manualHelper -Value $manualScript -Encoding UTF8
    & $Python $manualHelper $ProviderLauncher $manualConfig
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not create mixed Manual configuration'
    }
    $initial = Get-Content -Raw -LiteralPath $manualConfig | ConvertFrom-Json
    $initialBases = @($initial.ports | ForEach-Object { $_.base_beats })
    $manualPreview = [ordered]@{
        preview_locations = @()
        buttons_exercised = @()
        accessible_tree = $null
        expanded_groups = @()
        preview_screenshots = @()
        generation_complete = $null
        limitations = @('Native canvas drag/pan and internal zoom, pan, and transform state remain in the SWTBot suite; packaged smoke exercises native buttons and the UIA tree only.')
    }
    $manualRequiredPaths = @(
        'wrapper.f',
        'logical/hades_sram_config_pkg.sv',
        'logical/hades_sram_user.sv',
        'report/analysis.json',
        'report/report.html'
    )
    $manualForbiddenPaths = @(
        'static_rtl.f',
        'logical/hades_sram_top.sv',
        'constraints/hades_sram_clocks_sync.xdc',
        'constraints/hades_sram_clocks_async.xdc',
        'docs/INTEGRATION_GUIDE.md',
        'docs/csr_regmap.md'
    )
    $process = Start-OwnedProduct @('--config', $manualConfig, '--output', $manualOutput, '--python', $Python)
    try {
        $main = Wait-Window 'HADES-Wizard' $process.Id
        Assert-BundledRuntime $process 'manual'
        Assert-ModulePreview $main 'Manual main window' $manualPreview
        Capture-Window $main 'manual-00-main-preview.png'
        Invoke-EditConfiguration $main
        $wizard = Wait-Window 'HADES-SRAM Configuration' $process.Id
        Invoke-Button $wizard 'Next >'
        Start-Sleep -Milliseconds 700
        $mode = Get-ComboText $wizard
        if ($mode -notmatch 'Manual') {
            throw "Expected Manual addressing, got '$mode'"
        }
        Capture-Window $wizard 'manual-01-before-edits.png'
        Set-EditValue $wizard 2 '64'
        Start-Sleep -Milliseconds 700
        Set-EditValue $wizard 0 '48'
        Start-Sleep -Milliseconds 700
        Set-EditValue $wizard 1 '19'
        Start-Sleep -Milliseconds 700
        Capture-Window $wizard 'manual-02-width-depth-base.png'
        Invoke-Button $wizard 'Next >'
        Start-Sleep -Milliseconds 700
        Capture-Window $wizard 'manual-03-clock.png'
        Invoke-Button $wizard 'Next >'
        Start-Sleep -Milliseconds 700
        Capture-Window $wizard 'manual-04-maintenance.png'
        Invoke-Button $wizard 'Next >'
        Start-Sleep -Milliseconds 700
        Capture-Window $wizard 'manual-05-review.png'
        Assert-NativeCheckBoxState $wizard 'Copy complete integration sources' $true
        Invoke-Button $wizard 'Copy complete integration sources'
        Start-Sleep -Milliseconds 400
        Assert-NativeCheckBoxState $wizard 'Copy complete integration sources' $false
        $manualPreview.full_copy_explicitly_unchecked = $true
        Invoke-Button $wizard 'Finish'
        Complete-Generation $process $manualOutput 18 $false 'manual-06-generation-complete.png' $manualPreview
        Assert-ArtifactParity -Generated $manualOutput -Reference $manualReference -Label 'Manual packaged GUI' `
            -ExpectedFileCount 18 -ExpectedPortCount 4 -FullCopy $false -RequiredPaths $manualRequiredPaths `
            -ForbiddenPaths $manualForbiddenPaths -PreviewEvidence $manualPreview
        $generatedConfig = Get-Content -Raw -LiteralPath (Join-Path $manualOutput 'config.json') | ConvertFrom-Json
        if ($generatedConfig.ports[0].base_beats -ne 64 -or $generatedConfig.ports[0].width -ne 48) {
            throw 'Manual base/width was unexpectedly relocated or rewritten'
        }
        for ($i = 1; $i -lt 4; $i++) {
            if ($generatedConfig.ports[$i].base_beats -ne $initialBases[$i]) {
                throw "Manual edit relocated port $i"
            }
        }
        $analysis = Get-Content -Raw -LiteralPath (Join-Path $manualOutput 'report/analysis.json') | ConvertFrom-Json
        if ($analysis.ports[0].capacity_words -ne 19) {
            throw "Manual depth was not applied: $($analysis.ports[0].capacity_words)"
        }
        Write-Output 'Manual base, width 48, depth 19 and other bases verified'
    } finally {
        Stop-OwnedProcessTree $process.Id
    }
} catch {
    Write-Error $_
    throw
}
