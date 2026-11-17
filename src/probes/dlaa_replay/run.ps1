param(
    [string]$RuntimeDirectory = '',
    [string]$CaptureDirectory = '',
    [ValidateSet('TwoFrame', 'Sequence', 'SequenceCycle', 'SequenceOpaqueAlpha', 'SequenceResetHistory', 'SequenceLinearColor', 'SequencePreparedLdr')][string]$Mode = 'TwoFrame',
    [ValidateSet(10, 11, 12, 13)][int]$Preset = 11,
    [string]$Label = 'hidden-present',
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$nativeRoot = (Resolve-Path (Join-Path $projectRoot '../MCDR')).Path
if (-not $CaptureDirectory) { $CaptureDirectory = Join-Path $projectRoot 'build/reports/workgraph/oit-pixel-trace/capture-1794669380460' }
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $projectRoot 'run/dxrmc' }
$reportDirectory = Join-Path $projectRoot ('build/reports/workgraph/dlaa-replay/' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $Label)
New-Item -ItemType Directory -Path $reportDirectory | Out-Null
$dllNames = @('sl.interposer.dll', 'sl.common.dll', 'sl.dlss.dll', 'sl.reflex.dll', 'sl.pcl.dll', 'sl.dlss_g.dll', 'nvngx_dlss.dll', 'nvngx_dlssg.dll', 'D3D12Core.dll', 'dxcompiler.dll', 'dxil.dll')
$manifest = foreach ($dllName in $dllNames) {
    $source = Join-Path $RuntimeDirectory $dllName
    Copy-Item -LiteralPath $source -Destination $reportDirectory
    [pscustomobject]@{ File = $dllName; Source = (Resolve-Path $source).Path; SHA256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash }
}
$nativeDll = Join-Path $nativeRoot 'target/x64_Release/MCDR.dll'
Copy-Item -LiteralPath $nativeDll -Destination $reportDirectory
$manifest += [pscustomobject]@{ File = 'MCDR.dll'; Source = $nativeDll; SHA256 = (Get-FileHash -LiteralPath $nativeDll -Algorithm SHA256).Hash }
$manifest | Export-Csv -LiteralPath (Join-Path $reportDirectory 'runtime-manifest.csv')
$vcvars = 'C:/Program Files/Microsoft Visual Studio/18/Community/VC/Auxiliary/Build/vcvars64.bat'
$sourceFile = Join-Path $PSScriptRoot 'main.cpp'
$executable = Join-Path $reportDirectory 'dlaa-replay.exe'
$objectFile = Join-Path $reportDirectory 'main.obj'
$includeDirectory = Join-Path $nativeRoot 'include'
$importLibrary = Join-Path $nativeRoot 'target/x64_Release/MCDR.lib'
$compile = 'call "{0}" >nul && cl /nologo /std:c++20 /EHsc /O2 /W4 /DWIN32_LEAN_AND_MEAN /I"{1}" "{2}" /Fo"{3}" /Fe"{4}" /link "{5}" user32.lib' -f $vcvars, $includeDirectory, $sourceFile, $objectFile, $executable, $importLibrary
& cmd.exe /d /c $compile 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'build.log')
if ($LASTEXITCODE -ne 0) { throw "Compile failed; reports: $reportDirectory" }
Write-Output "Executable=$executable"
Write-Output "ReportDirectory=$reportDirectory"
if ($BuildOnly) { return }
$CaptureDirectory = (Resolve-Path -LiteralPath $CaptureDirectory).Path
$priorImplementation = $env:MCDR_DLSS_IMPL
$env:MCDR_DLSS_IMPL = 'streamline'
Push-Location $reportDirectory
try {
    $replayArguments = @($CaptureDirectory, $reportDirectory, "--preset=$Preset")
    if ($Mode -eq 'Sequence') { $replayArguments += '--sequence' }
    if ($Mode -eq 'SequenceCycle') { $replayArguments += '--sequence-cycle' }
    if ($Mode -eq 'SequenceOpaqueAlpha') { $replayArguments += '--sequence-opaque-alpha' }
    if ($Mode -eq 'SequenceResetHistory') { $replayArguments += '--sequence-reset-history' }
    if ($Mode -eq 'SequenceLinearColor') { $replayArguments += '--sequence-linear-color' }
    if ($Mode -eq 'SequencePreparedLdr') { $replayArguments += '--sequence-prepared-ldr' }
    & $executable @replayArguments 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'replay.log')
    $replayExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    $env:MCDR_DLSS_IMPL = $priorImplementation
}
Write-Output "ReportDirectory=$reportDirectory"
if ($replayExitCode -ne 0) { throw "Replay failed with exit code $replayExitCode; reports: $reportDirectory" }
