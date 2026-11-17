# Vanilla scheduler frontier probe

Standalone C++ probe for the production header; does not enter the game or production artifact.

From a Visual Studio developer shell, compile with the real native header directory:

```powershell
cl /nologo /std:c++latest /EHsc /W4 /I ..\MCDR\src\render\vanilla src\probes\vanilla_scheduler\main.cpp /Fe:build\reports\workgraph\vanilla-scheduler-probe.exe /Fo:build\reports\workgraph\vanilla-scheduler-probe.obj
build\reports\workgraph\vanilla-scheduler-probe.exe
```

Checks long repeated-read ordering without accumulated prior dependencies, disjoint mip independence, last-writer/latest-reader WAR/WAW, stale commit rejection, resource incarnation changes, external exit state and UAV barriers, poison/reset. Root coordinates serial execution.
