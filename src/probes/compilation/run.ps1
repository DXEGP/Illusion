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
$output = 'build/reports/workgraph/compilation-cache-probe'
New-Item -ItemType Directory -Force $output | Out-Null
$sources = @(
    'src/main/kotlin/io/github/dxegp/d3d12/shader/CompletedCompilationCache.kt',
    'src/main/kotlin/io/github/dxegp/render/compilation/VersionedPipelinePrewarm.kt',
    'src/main/kotlin/io/github/dxegp/d3d12/shader/ShaderConversionCache.kt',
    'src/main/kotlin/io/github/dxegp/d3d12/shader/NagaShaderProcessor.kt',
    'src/main/kotlin/io/github/dxegp/d3d12/shader/D3D12ShaderCompiler.kt',
    'src/main/kotlin/io/github/dxegp/d3d12/shader/D3D12CompiledShader.kt',
    'src/main/kotlin/io/github/dxegp/d3d12/shader/D3D12IntermediaryShaderModule.kt',
    'src/probes/compilation/CompilationCacheProbe.kt'
)
& $java -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 25 -classpath $projectClasspath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation cache production sources failed isolated compilation' }
& $java -cp "$output;$projectClasspath" io.github.dxegp.probes.compilation.CompilationCacheProbeKt
if ($LASTEXITCODE -ne 0) { throw 'Compilation cache production-source probe failed' }
