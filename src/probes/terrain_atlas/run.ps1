$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
Set-Location -LiteralPath $repoRoot
$dependencyCache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
$compilerLibraries = @(
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.5.0-Beta2',
    'org.jetbrains.kotlin/kotlin-stdlib/2.5.0-Beta2',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.5.0-Beta2',
    'org.jetbrains.kotlin/kotlin-reflect/2.4.21',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.11.0',
    'org.jetbrains/annotations/26.0.2-1'
)
$compilerJars = foreach ($library in $compilerLibraries) {
    $binary = Get-ChildItem (Join-Path $dependencyCache $library) -Recurse -Filter '*.jar' |
        Where-Object Name -notmatch 'sources|javadoc' | Select-Object -First 1 -ExpandProperty FullName
    if (!$binary) { throw "Missing installed compiler dependency: $library" }
    $binary
}
$java = 'C:/Program Files/GraalVM/JDKs/25.1/bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { throw "Required installed JDK is unavailable: $java" }
$runtimeClasspath = (Get-Content 'build/reports/mesh-earlywindow-review/runtime-classpath.txt' -Raw).Trim()
$projectClasspath = "build/classes/kotlin/main;build/classes/java/main;$runtimeClasspath"
$output = 'build/reports/workgraph/terrain-atlas-probe'
$classes = Join-Path $output 'classes'
$production = Join-Path $output 'production'
New-Item -ItemType Directory -Force $classes, $production | Out-Null
$sources = @(
    'src/main/kotlin/io/github/dxegp/d3d12/resource/SubmissionUploadArena.kt',
    'src/main/kotlin/io/github/dxegp/d3d12/resource/D3D12GpuBuffer.kt',
    'src/main/kotlin/io/github/dxegp/d3d12/resource/D3D12TransientMemory.kt',
    'src/main/kotlin/io/github/dxegp/render/terrain/mesh/D3D12MeshletBuilder.kt',
    'src/main/kotlin/io/github/dxegp/render/terrain/mesh/D3D12SectionGeometry.kt',
    'src/main/kotlin/io/github/dxegp/render/terrain/mesh/D3D12MeshletResidency.kt'
)
& $java -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $projectClasspath -d $production @sources
if ($LASTEXITCODE -ne 0) { throw 'Atlas retirement production sources failed project ABI compilation' }
$sources += 'src/probes/terrain_atlas/TerrainAtlasAbi.kt', 'src/probes/terrain_atlas/TerrainAtlasRetirementProbe.kt'
& $java -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $projectClasspath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Atlas retirement production sources failed isolated compilation' }
& $java -cp "$classes;$projectClasspath" io.github.dxegp.render.terrain.mesh.TerrainAtlasRetirementProbeKt
if ($LASTEXITCODE -ne 0) { throw 'Atlas retirement production-source probe failed' }
