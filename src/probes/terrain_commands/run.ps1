$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$cacheRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$libraries = @('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.5.0-RC', 'org.jetbrains.kotlin/kotlin-stdlib/2.5.0-RC', 'org.jetbrains.kotlin/kotlin-script-runtime/2.5.0-RC', 'org.jetbrains.kotlin/kotlin-reflect', 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm', 'org.jetbrains/annotations')
$compilerJars = foreach ($library in $libraries) {
    Get-ChildItem -LiteralPath (Join-Path $cacheRoot $library) -Recurse -Filter '*.jar' |
        Where-Object Name -notmatch 'sources|javadoc' | Select-Object -Last 1 -ExpandProperty FullName
}
$productionClasses = Join-Path $projectRoot 'build/classes/kotlin/main'
$classpath = @($productionClasses, (Join-Path $projectRoot 'build/classes/java/main'),
    (Join-Path $projectRoot 'build/moddev/artifacts/minecraft-patched-26.3.0.95-merged.jar'),
    (Get-Content -LiteralPath (Join-Path $projectRoot 'build/reports/mesh-earlywindow-review/runtime-classpath.txt') -Raw).Trim()) -join ';'
$sources = @((Join-Path $projectRoot 'src/main/kotlin/io/github/dxegp/render/terrain/mesh/D3D12MeshDrawCapture.kt'),
    (Join-Path $projectRoot 'src/main/kotlin/io/github/dxegp/render/terrain/mesh/TerrainGeometryAllocations.kt'),
    (Join-Path $projectRoot 'src/main/kotlin/io/github/dxegp/render/terrain/mesh/D3D12TerrainWorkGraph.kt'),
    (Join-Path $PSScriptRoot 'TerrainCommandRangesProbe.kt'))
$javaPath = 'C:/Program Files/GraalVM/JDKs/25.1/bin/java.exe'
$reportDirectory = Join-Path $projectRoot 'build/reports/workgraph/terrain-command-ranges'
$null = New-Item -ItemType Directory -Force -Path $reportDirectory
& $javaPath -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 "-Xfriend-paths=$productionClasses" -classpath $classpath -d (Join-Path $reportDirectory 'production') @sources *> (Join-Path $reportDirectory 'compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $reportDirectory 'compile.log') -Tail 120; throw 'Terrain command range source compilation failed' }
& $javaPath -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $classpath -d (Join-Path $reportDirectory 'boundary') (Join-Path $PSScriptRoot 'NativeAllocationBoundary.kt') *> (Join-Path $reportDirectory 'boundary-compile.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $reportDirectory 'boundary-compile.log') -Tail 120; throw 'Terrain allocation boundary compilation failed' }
& $javaPath -cp "$(Join-Path $reportDirectory 'boundary');$(Join-Path $reportDirectory 'production');$classpath" io.github.dxegp.render.terrain.mesh.TerrainCommandRangesProbeKt *> (Join-Path $reportDirectory 'result.log')
if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath (Join-Path $reportDirectory 'result.log') -Tail 120; throw 'Terrain command range validation failed' }
Get-Content -LiteralPath (Join-Path $reportDirectory 'result.log') -Tail 8
