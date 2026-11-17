param(
    [string]$RuntimeDirectory = '',
    [switch]$BuildOnly,
    [string]$NativeRoot = ''
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
if (-not $NativeRoot) { $NativeRoot = Join-Path $projectRoot '../MCDR' }
$NativeRoot = (Resolve-Path -LiteralPath $NativeRoot).Path
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $projectRoot 'run/dxrmc' }
$reportDirectory = Join-Path $projectRoot ('build/reports/workgraph/reuse/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $reportDirectory | Out-Null
$nativeDll = Join-Path $nativeRoot 'target/x64_Release/MCDR.dll'
Copy-Item -LiteralPath $nativeDll -Destination $reportDirectory
$manifest = @([pscustomobject]@{
    File = 'MCDR.dll'
    SHA256 = (Get-FileHash -LiteralPath $nativeDll -Algorithm SHA256).Hash
})
foreach ($dllName in @('D3D12Core.dll', 'dxcompiler.dll', 'dxil.dll')) {
    $source = Join-Path $RuntimeDirectory $dllName
    Copy-Item -LiteralPath $source -Destination $reportDirectory
    $manifest += [pscustomobject]@{
        File = $dllName
        SHA256 = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    }
}
$manifest | Export-Csv -LiteralPath (Join-Path $reportDirectory 'runtime-manifest.csv')
$vcvars = 'C:/Program Files/Microsoft Visual Studio/18/Community/VC/Auxiliary/Build/vcvars64.bat'
$sourceFile = Join-Path $PSScriptRoot 'main.cpp'
$objectFile = Join-Path $reportDirectory 'main.obj'
$executable = Join-Path $reportDirectory 'workgraph-reuse.exe'
$includeDirectory = Join-Path $nativeRoot 'include'
$importLibrary = Join-Path $nativeRoot 'target/x64_Release/MCDR.lib'
$compile = 'call "{0}" >nul && cl /nologo /std:c++latest /EHsc /O2 /W4 /WX /I"{1}" "{2}" /Fo"{3}" /Fe"{4}" /link "{5}"' -f $vcvars, $includeDirectory, $sourceFile, $objectFile, $executable, $importLibrary
& cmd.exe /d /c $compile 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'build.log')
if ($LASTEXITCODE -ne 0) { throw "Compile failed; reports: $reportDirectory" }
Write-Output "ReportDirectory=$reportDirectory"
if ($BuildOnly) { return }
Push-Location $reportDirectory
try {
    & $executable 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'run.log')
    $probeExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($probeExitCode -ne 0) { throw "Work Graph check failed with exit code $probeExitCode; reports: $reportDirectory" }
