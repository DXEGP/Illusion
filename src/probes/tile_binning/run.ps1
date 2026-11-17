param(
    [string]$RuntimeDirectory = '',
    [string]$DxcPath = 'D:/Projects/MinecraftMod/DXRMC/MCDR/vcpkg_installed/x64-windows/tools/directx-dxc/dxc.exe',
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
if (-not $RuntimeDirectory) { $RuntimeDirectory = Join-Path $projectRoot 'run/dxrmc' }
$DxcPath = (Resolve-Path -LiteralPath $DxcPath).Path
$reportDirectory = Join-Path $projectRoot ('build/reports/workgraph/tile-binning/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $reportDirectory | Out-Null
$runtime = Join-Path $RuntimeDirectory 'D3D12Core.dll'
Copy-Item -LiteralPath $runtime -Destination $reportDirectory
@($runtime, $DxcPath) | ForEach-Object {
    [pscustomobject]@{ File = $_; SHA256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
} | Export-Csv -LiteralPath (Join-Path $reportDirectory 'runtime-manifest.csv') -NoTypeInformation
$shaderDirectory = Join-Path $projectRoot 'src/main/resources/assets/illusion/shaders/oit'
$sources = @('tile_route.hlsl', 'tile_envelope.hlsl', 'tile_binning.hlsl') | ForEach-Object { Join-Path $shaderDirectory $_ }
@($sources) + @((Join-Path $PSScriptRoot 'main.cpp'), $PSCommandPath) | ForEach-Object {
    [pscustomobject]@{ File = $_; SHA256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
} | Export-Csv -LiteralPath (Join-Path $reportDirectory 'source-manifest.csv') -NoTypeInformation
$shader = ($sources | ForEach-Object { Get-Content -LiteralPath $_ -Raw }) -join "`n"
$shaderPath = Join-Path $reportDirectory 'tile_binning.hlsl'
[IO.File]::WriteAllText($shaderPath, $shader)
foreach ($entry in @('InitializeTileBinsV1', 'PrefixTileRefsV1', 'ScatterTileCommandsV1')) {
    & $DxcPath -T cs_6_9 -E $entry -HV 2018 -O3 -WX -Fo (Join-Path $reportDirectory ($entry + '.dxil')) $shaderPath 2>&1 |
        Tee-Object -FilePath (Join-Path $reportDirectory ($entry + '-compile.log'))
    if ($LASTEXITCODE -ne 0) { throw "Shader compile failed: $entry; reports: $reportDirectory" }
}
& $DxcPath -T lib_6_9 -D MCDR_TILE_BIN_NODES -HV 2018 -O3 -WX -Fo (Join-Path $reportDirectory 'TileEnvelopeEntryV1.dxil') $shaderPath 2>&1 |
    Tee-Object -FilePath (Join-Path $reportDirectory 'TileEnvelopeEntryV1-compile.log')
if ($LASTEXITCODE -ne 0) { throw "Node shader compile failed; reports: $reportDirectory" }
$vcvars = 'C:/Program Files/Microsoft Visual Studio/18/Community/VC/Auxiliary/Build/vcvars64.bat'
$sourceFile = Join-Path $PSScriptRoot 'main.cpp'
$objectFile = Join-Path $reportDirectory 'main.obj'
$executable = Join-Path $reportDirectory 'tile-binning.exe'
$compile = 'call "{0}" >nul && cl /nologo /std:c++latest /EHsc /O2 /W4 /WX /DWIN32_LEAN_AND_MEAN "{1}" /Fo"{2}" /Fe"{3}" /link d3d12.lib dxgi.lib dxguid.lib' -f $vcvars, $sourceFile, $objectFile, $executable
& cmd.exe /d /c $compile 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'build.log')
if ($LASTEXITCODE -ne 0) { throw "Compile failed; reports: $reportDirectory" }
Write-Output "ReportDirectory=$reportDirectory"
if ($BuildOnly) { return }
& $executable $reportDirectory 2>&1 | Tee-Object -FilePath (Join-Path $reportDirectory 'gpu.log')
if ($LASTEXITCODE -ne 0) { throw "Tile prefix GPU check failed; reports: $reportDirectory" }
