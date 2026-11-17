# Terrain atlas retirement probe

Run `./src/probes/terrain_atlas/run.ps1` from PowerShell after the project classes and
`build/reports/mesh-earlywindow-review/runtime-classpath.txt` are available. The script
uses the installed GraalVM 25 and cached Kotlin 2.5.0-Beta2 compiler; it downloads nothing.

The actual buffer, upload arena, transient memory and meshlet residency sources are
compiled against the project ABI first. The same sources are then compiled with a
separate probe that replaces only `MCDR_ABI` with a controlled resource/fence boundary.

The probe forces 1 MiB → 2 MiB atlas growth, checks that unrelated or earlier fences
cannot retire the old atlas, injects a failure after a recorded copy, verifies that
published offsets survive failure, and checks unsubmitted retention and exactly-once
terminal cleanup. It does not load native D3D12, execute GPU work, validate Mixin weaving,
or verify the encoder's shutdown guard. The encoder must still wait for its fence and
destroy its command context before calling transient memory and atlas `close()`.
