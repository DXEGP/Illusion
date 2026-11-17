param(
    [Parameter(Mandatory = $true)][string]$NativeRoot,
    [string]$RuntimeDirectory = '',
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$NativeRoot = (Resolve-Path -LiteralPath $NativeRoot).Path
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $projectRoot 'run/dxrmc' }
$reportDirectory = Join-Path $projectRoot ('build/reports/workgraph/oit-operations/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $reportDirectory | Out-Null
$runtimeFiles = @((Join-Path $NativeRoot 'target/x64_Release/MCDR.dll'))
foreach ($name in @('D3D12Core.dll', 'dxcompiler.dll', 'dxil.dll')) { $runtimeFiles += Join-Path $RuntimeDirectory $name }
foreach ($source in $runtimeFiles) { Copy-Item -LiteralPath $source -Destination $reportDirectory }
Get-ChildItem -LiteralPath $reportDirectory -Filter '*.dll' | ForEach-Object {
    [pscustomobject]@{ File = $_.Name; SHA256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
} | Export-Csv -LiteralPath (Join-Path $reportDirectory 'runtime-manifest.csv') -NoTypeInformation
$vcvars = 'C:/Program Files/Microsoft Visual Studio/18/Community/VC/Auxiliary/Build/vcvars64.bat'
$sourceFile = Join-Path $PSScriptRoot 'main.cpp'
$objectFile = Join-Path $reportDirectory 'main.obj'
$executable = Join-Path $reportDirectory 'oit-operations.exe'
$includeDirectory = Join-Path $NativeRoot 'include'
$importLibrary = Join-Path $NativeRoot 'target/x64_Release/MCDR.lib'
$compile = 'call "{0}" >nul && cl /nologo /std:c++latest /EHsc /O2 /W4 /WX /I"{1}" "{2}" /Fo"{3}" /Fe"{4}" /link "{5}"' -f $vcvars, $includeDirectory, $sourceFile, $objectFile, $executable, $importLibrary
& cmd.exe /d /c $compile 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'build.log')
if ($LASTEXITCODE -ne 0) { throw "Compile failed; reports: $reportDirectory" }
Write-Output "ReportDirectory=$reportDirectory"
if ($BuildOnly) { return }
Push-Location $reportDirectory
try { & $executable 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'gpu.log'); $probeExitCode = $LASTEXITCODE } finally { Pop-Location }
if ($probeExitCode -ne 0) { throw "OIT operation check failed with exit code $probeExitCode; reports: $reportDirectory" }
