param(
    [Parameter(Mandatory = $true)][string]$NativeRoot,
    [string]$RuntimeDirectory = '',
    [switch]$PairsOnly,
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$NativeRoot = (Resolve-Path -LiteralPath $NativeRoot).Path
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $projectRoot 'run/dxrmc' }
$reportDirectory = Join-Path $projectRoot ('build/reports/workgraph/terrain-nodes/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $reportDirectory | Out-Null
$runtimeFiles = @((Join-Path $NativeRoot 'target/x64_Release/MCDR.dll'))
foreach ($name in @('D3D12Core.dll', 'dxcompiler.dll', 'dxil.dll')) {
    $runtimeFiles += Join-Path $RuntimeDirectory $name
}
$manifest = foreach ($source in $runtimeFiles) {
    Copy-Item -LiteralPath $source -Destination $reportDirectory
    [pscustomobject]@{ File = (Split-Path $source -Leaf); Source = $source; SHA256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash }
}
$manifest | Export-Csv -LiteralPath (Join-Path $reportDirectory 'runtime-manifest.csv')
$newShader = Join-Path $PSScriptRoot 'fused-candidate.hlsl'
$oldShader = Join-Path $projectRoot 'src/main/resources/assets/illusion/shaders/terrain/terrain_workgraph.hlsl'
$argumentsShader = Join-Path $projectRoot 'src/main/resources/assets/illusion/shaders/terrain/terrain_arguments.hlsl'
@($newShader, $oldShader, $argumentsShader, (Join-Path $PSScriptRoot 'main.cpp')) | ForEach-Object {
    [pscustomobject]@{ File = $_; SHA256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
} | Export-Csv -LiteralPath (Join-Path $reportDirectory 'source-manifest.csv')
$vcvars = 'C:/Program Files/Microsoft Visual Studio/18/Community/VC/Auxiliary/Build/vcvars64.bat'
$sourceFile = Join-Path $PSScriptRoot 'main.cpp'
$objectFile = Join-Path $reportDirectory 'main.obj'
$executable = Join-Path $reportDirectory 'terrain-nodes.exe'
$includeDirectory = Join-Path $NativeRoot 'include'
$importLibrary = Join-Path $NativeRoot 'target/x64_Release/MCDR.lib'
$compile = 'call "{0}" >nul && cl /nologo /std:c++latest /EHsc /O2 /W4 /WX /I"{1}" "{2}" /Fo"{3}" /Fe"{4}" /link "{5}"' -f $vcvars, $includeDirectory, $sourceFile, $objectFile, $executable, $importLibrary
& cmd.exe /d /c $compile 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'build.log')
if ($LASTEXITCODE -ne 0) { throw "Compile failed; reports: $reportDirectory" }
Write-Output "ReportDirectory=$reportDirectory"
if ($BuildOnly) { return }
Push-Location $reportDirectory
try {
    $probeArguments = @($newShader, $oldShader, $argumentsShader, $reportDirectory)
    if ($PairsOnly) { $probeArguments += '--pairs-only' }
    & $executable @probeArguments 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'gpu.log')
    $probeExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($probeExitCode -ne 0) { throw "Terrain graph check failed with exit code $probeExitCode; reports: $reportDirectory" }
