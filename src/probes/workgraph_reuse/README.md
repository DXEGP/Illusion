# Work Graph resource reuse probe

`main.cpp` uses only the public Work Graph C ABI and a real D3D12 device. The graph reads one DEFAULT buffer through both a 256-byte CBV span and a separate ByteAddressBuffer SRV span, then writes two UAV bindings that intentionally point at the same output resource. A second graph reverses the CBV/SRV binding order while using the same resource.

The probe also submits invalid CPU records (null records, undersized stride, and misaligned stride), then attempts CBV/SRV plus UAV aliasing on the same resource with disjoint spans in both binding orders. Each rejection is checked before any output changes. The existing checks for abandoned recording, two pending contexts, early graph destruction, and submitted-slot reuse remain in the same run.

`run.ps1 -NativeRoot <MCDR source directory>` copies the selected Release DLL into a separate report directory, builds the probe against its public headers and import library, then runs it with the staged D3D12/DXC runtime. Build the native DLL containing the matching resource-alias validation before running this probe. Results and runtime hashes are saved under `build/reports/workgraph/reuse/`.
