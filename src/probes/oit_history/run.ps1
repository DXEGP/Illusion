param(
    [string]$NativeRoot = 'D:/Projects/MinecraftMod/DXRMC/rebuild/MCDR',
    [string]$RuntimeDirectory = '',
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $projectRoot 'run/dxrmc' }
$reportDirectory = Join-Path $projectRoot ('build/reports/workgraph/oit-history/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $reportDirectory | Out-Null
Copy-Item -LiteralPath (Join-Path $RuntimeDirectory 'D3D12Core.dll') -Destination $reportDirectory
$prelude = Get-Content (Join-Path $NativeRoot 'include/mcdr_oit_shader.h') -Raw
$consumer = Get-Content (Join-Path $NativeRoot 'include/mcdr_oit_resolve_shader.h') -Raw
$shader = [regex]::Match($prelude, '(?s)R"HLSL\((.*?)\)HLSL"').Groups[1].Value + [regex]::Match($consumer, '(?s)R"HLSL\((.*?)\)HLSL"').Groups[1].Value + (Get-Content (Join-Path $PSScriptRoot 'append.hlsl') -Raw)
$shaderPath = Join-Path $reportDirectory 'consumer.hlsl'
[IO.File]::WriteAllText($shaderPath, $shader)
$dxc = 'C:/Program Files (x86)/Windows Kits/10/bin/10.0.26100.0/x64/dxc.exe'
foreach ($entry in @('InitializeRecordsV1', 'CompactRecordsV1', 'ResolveRgbHistoryV3', 'AppendPackedRecords')) {
    & $dxc -T cs_6_9 -E $entry -HV 2018 -O3 -WX -Fo (Join-Path $reportDirectory ($entry + '.dxil')) $shaderPath
    if ($LASTEXITCODE -ne 0) { throw "Shader compile failed: $entry" }
}
$vcvars = 'C:/Program Files/Microsoft Visual Studio/18/Community/VC/Auxiliary/Build/vcvars64.bat'
$sourceFile = Join-Path $PSScriptRoot 'main.cpp'
$executable = Join-Path $reportDirectory 'oit-history.exe'
$objectFile = Join-Path $reportDirectory 'main.obj'
$includeDirectory = Join-Path $NativeRoot 'include'
$compile = 'call "{0}" >nul && cl /nologo /std:c++latest /EHsc /O2 /W4 /WX /DWIN32_LEAN_AND_MEAN /I"{1}" "{2}" /Fo"{3}" /Fe"{4}" /link d3d12.lib dxgi.lib dxguid.lib' -f $vcvars, $includeDirectory, $sourceFile, $objectFile, $executable
& cmd.exe /d /c $compile 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'build.log')
if ($LASTEXITCODE -ne 0) { throw "Compile failed; reports: $reportDirectory" }
Write-Output "ReportDirectory=$reportDirectory"
if ($BuildOnly) { return }
& $executable $reportDirectory 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'gpu.log')
if ($LASTEXITCODE -ne 0) { throw "GPU check failed; reports: $reportDirectory" }
