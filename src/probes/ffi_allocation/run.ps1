param([string]$NativeLibrary = '')
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$runtimeDirectory = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.5.0-RC'
$kotlinRuntime = Get-ChildItem -LiteralPath $runtimeDirectory -Recurse -Filter '*.jar' |
    Where-Object Name -notmatch 'sources|javadoc' | Select-Object -First 1 -ExpandProperty FullName
if (!$kotlinRuntime) { throw 'Compile the project first to provide its Kotlin runtime' }
$reportDirectory = Join-Path $projectRoot 'build/reports/workgraph/ffm-allocation-check'
$null = New-Item -ItemType Directory -Force -Path $reportDirectory
$classpath = @((Join-Path $projectRoot 'build/classes/kotlin/main'), $kotlinRuntime) -join ';'
$checkArguments = @('--enable-native-access=ALL-UNNAMED', '--class-path', $classpath,
    (Join-Path $PSScriptRoot 'FfmAllocationCheck.java'))
if ($NativeLibrary) { $checkArguments += (Resolve-Path -LiteralPath $NativeLibrary).Path }
& 'C:/Program Files/GraalVM/JDKs/25.1/bin/java.exe' @checkArguments *> (Join-Path $reportDirectory 'result.log')
$checkExit = $LASTEXITCODE
Get-Content -LiteralPath (Join-Path $reportDirectory 'result.log') -Tail 12
if ($checkExit -ne 0) { throw "FFM allocation check failed: $checkExit" }
