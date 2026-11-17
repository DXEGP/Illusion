import io.github.dxegp.d3d12.ffi.FfmAllocationKt;
import io.github.dxegp.d3d12.ffi.FfmEmptyAllocation;
import io.github.dxegp.d3d12.ffi.FfmScratch;
import io.github.dxegp.d3d12.ffi.MCDRLayout;
import io.github.dxegp.d3d12.ffi.MCDR_ABI;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.LinkedHashMap;

class FfmAllocationCheck {
    private static void verify(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    public static void main(String[] arguments) throws Throwable {
        var scratch = new FfmScratch();
        var first = scratch.allocate(0, 32, 8, null);
        verify(first.byteSize() == 32, "small target retained its requested size");
        first.set(ValueLayout.JAVA_LONG, 0, 0x12345678L);
        scratch.allocate(1, 4096, 16, null);
        var grown = scratch.allocate(1, 65536, 256, null);
        verify(grown.byteSize() == 65536 && grown.address() % 256 == 0, "cache size/alignment");
        verify(first.get(ValueLayout.JAVA_LONG, 0) == 0x12345678L, "growing another slot invalidated input");
        scratch.withScope(65536, 256, arena -> {
            verify(arena == null, "cache boundary created a temporary arena");
            return null;
        });
        var nestedSegments = new MemorySegment[1];
        scratch.withScope(32, 8, outerArena -> {
            var outer = scratch.allocate(0, 32, 8, outerArena);
            outer.set(ValueLayout.JAVA_LONG, 0, 0x12345678L);
            scratch.withScope(32, 8, innerArena -> {
                verify(innerArena != null, "nested call missed confined arena");
                var inner = scratch.allocate(0, 32, 8, innerArena);
                nestedSegments[0] = inner;
                inner.set(ValueLayout.JAVA_LONG, 0, 0x76543210L);
                verify(outer.get(ValueLayout.JAVA_LONG, 0) == 0x12345678L,
                    "nested allocation overwrote outer input");
                return null;
            });
            verify(!nestedSegments[0].scope().isAlive(), "nested allocation survived its call");
            verify(outer.scope().isAlive(), "nested call closed outer input");
            return null;
        });
        var temporarySegments = new MemorySegment[2];
        scratch.withScope(65537, 8, arena -> {
            verify(arena != null, "large allocation missed confined arena");
            temporarySegments[0] = scratch.allocate(0, 65537, 8, arena);
            temporarySegments[1] = scratch.allocate(1, 70000, 16, arena);
            verify(temporarySegments[0].scope().isAlive() && temporarySegments[1].scope().isAlive(),
                "arrays did not survive the same call");
            return null;
        });
        verify(!temporarySegments[0].scope().isAlive() && !temporarySegments[1].scope().isAlive(),
            "temporary arrays survived the call");
        scratch.withScope(32, 8, arena -> {
            verify(arena == null, "small estimate eagerly created an arena");
            var cached = scratch.allocate(0, 32, 8, arena);
            cached.set(ValueLayout.JAVA_LONG, 0, 0x12345678L);
            temporarySegments[0] = scratch.allocate(0, 65537, 8, arena);
            temporarySegments[1] = scratch.allocate(0, 32, 8, arena);
            temporarySegments[1].set(ValueLayout.JAVA_LONG, 0, 0x76543210L);
            verify(cached.get(ValueLayout.JAVA_LONG, 0) == 0x12345678L,
                "lazy arena allocation overwrote earlier cached input");
            verify(temporarySegments[0].scope().isAlive(), "lazy large allocation was not scoped");
            return null;
        });
        verify(!temporarySegments[0].scope().isAlive() && !temporarySegments[1].scope().isAlive(),
            "lazy arena allocations survived their call");
        var reused = scratch.allocate(0, 32, 8, null);
        verify(reused.address() == first.address() && reused.byteSize() == 32,
            "large request replaced small cache or widened the returned target");
        try {
            scratch.withScope(8, 512, arena -> {
                temporarySegments[0] = scratch.allocate(0, 8, 512, arena);
                verify(temporarySegments[0].address() % 512 == 0, "high alignment");
                throw new IllegalArgumentException("expected scope failure");
            });
            throw new AssertionError("scope swallowed failure");
        } catch (IllegalArgumentException expected) {
            verify(expected.getMessage().equals("expected scope failure"), "unexpected failure");
        }
        verify(!temporarySegments[0].scope().isAlive(), "failed scope kept its allocation alive");
        try (var arena = Arena.ofConfined()) {
            verify(FfmAllocationKt.allocateFfmArray(arena, 0, ValueLayout.JAVA_LONG,
                FfmEmptyAllocation.NULL).equals(MemorySegment.NULL), "empty array pointer");
            verify(FfmAllocationKt.allocateFfmArray(arena, 0, ValueLayout.JAVA_LONG,
                FfmEmptyAllocation.NON_NULL).address() != 0, "legacy non-null pointer");
        }
        verify(MCDRLayout.OIT_EXECUTION_NORMALIZATION_V1.byteSize() == 288, "normalization wire size");
        verify(MCDRLayout.OIT_EXECUTION_TEXTURE_V1.byteSize() == 64, "texture capture wire size");
        verify(MCDRLayout.OIT_EXECUTION_NORMALIZATION_V2.byteSize() == 224, "normalization V2 wire size");
        verify(MCDRLayout.OIT_EXECUTION_OPERATION_BEGIN_V1.byteSize() == 144, "operation begin wire size");
        verify(MCDRLayout.OIT_EXECUTION_OPERATION_RECEIPT_V1.byteSize() == 192, "operation receipt wire size");
        if (arguments.length == 1) {
            try (var arena = Arena.ofConfined()) {
                var library = SymbolLookup.libraryLookup(Path.of(arguments[0]), arena);
                var linker = Linker.nativeLinker();
                var abiVersion = linker.downcallHandle(library.find("mcdrABIVersion").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
                verify((int) abiVersion.invokeExact() == MCDR_ABI.MCDR_ABI_VERSION,
                    "native ABI version differs from JVM bindings");
                var layout = linker.downcallHandle(library.find("mcdrABILayout").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                var pointer = arena.allocate(ValueLayout.ADDRESS);
                var count = arena.allocate(ValueLayout.JAVA_INT);
                layout.invokeExact(pointer, count);
                var nativeCount = count.get(ValueLayout.JAVA_INT, 0);
                verify(nativeCount > 0, "native layout table is empty");
                var fields = pointer.get(ValueLayout.ADDRESS, 0).reinterpret(Math.multiplyExact((long) nativeCount, 24L));
                var nativeFields = new LinkedHashMap<String, long[]>();
                for (var index = 0; index < nativeCount; index++) {
                    var offset = index * 24L;
                    var name = fields.get(ValueLayout.ADDRESS, offset).reinterpret(1024L).getString(0);
                    verify(nativeFields.put(name, new long[] {fields.get(ValueLayout.JAVA_LONG, offset + 8),
                        fields.get(ValueLayout.JAVA_LONG, offset + 16)}) == null, "duplicate native field: " + name);
                }
                MCDRLayout.verify(nativeFields);
                var capture = linker.downcallHandle(library.find("mcdr_dx12OitCaptureExecutionTextureV1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                var output = arena.allocate(MCDRLayout.OIT_EXECUTION_TEXTURE_V1);
                output.fill((byte) 0x55);
                int result = (int) capture.invokeExact(MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, 0L, output);
                verify(result == -1, "capture boundary did not reject missing recording");
                for (var index = 0L; index < output.byteSize(); index++) {
                    verify(output.get(ValueLayout.JAVA_BYTE, index) == 0, "rejected capture published output");
                }
                var createNormalization = linker.downcallHandle(
                    library.find("mcdr_dx12CreateOitNormalizationPipelineV1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                var rejectedPipeline = (MemorySegment) createNormalization.invokeExact(
                    MemorySegment.NULL, MemorySegment.NULL);
                verify(rejectedPipeline.equals(MemorySegment.NULL), "missing normalization descriptor created pipeline");
                var beginOperation = linker.downcallHandle(
                    library.find("mcdr_dx12OitBeginExecutionOperationV1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                var finishOperation = linker.downcallHandle(
                    library.find("mcdr_dx12OitFinishExecutionOperationV1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                var cancelOperation = linker.downcallHandle(
                    library.find("mcdr_dx12OitCancelExecutionOperationV1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS));
                var queryOperation = linker.downcallHandle(
                    library.find("mcdr_dx12OitQueryExecutionOperationReceiptV1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                var operationInput = arena.allocate(MCDRLayout.OIT_EXECUTION_OPERATION_BEGIN_V1);
                var operationOut = arena.allocate(ValueLayout.ADDRESS);
                operationInput.fill((byte) 0x55);
                operationOut.fill((byte) 0x55);
                int beginResult = (int) beginOperation.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                    operationInput, operationOut);
                verify(beginResult < 0 && operationOut.get(ValueLayout.ADDRESS, 0).equals(MemorySegment.NULL),
                    "operation begin boundary did not reject missing recording");
                var receiptCount = arena.allocate(ValueLayout.JAVA_LONG);
                receiptCount.set(ValueLayout.JAVA_LONG, 0, -1L);
                int finishResult = (int) finishOperation.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, receiptCount);
                verify(finishResult < 0 && receiptCount.get(ValueLayout.JAVA_LONG, 0) == 0L,
                    "operation finish boundary did not clear count");
                verify((int) cancelOperation.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL) < 0, "operation cancel boundary accepted missing recording");
                var operationReceipt = arena.allocate(MCDRLayout.OIT_EXECUTION_OPERATION_RECEIPT_V1);
                operationReceipt.fill((byte) 0x55);
                int queryResult = (int) queryOperation.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, 0L, operationReceipt);
                verify(queryResult < 0, "operation query boundary accepted missing recording");
                for (var index = 0L; index < operationReceipt.byteSize(); index++) {
                    verify(operationReceipt.get(ValueLayout.JAVA_BYTE, index) == 0,
                        "rejected operation query published output");
                }
                var appendOperation = linker.downcallHandle(
                    library.find("mcdr_dx12OitAppendExecutionOperationEventV1").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                receiptCount.set(ValueLayout.JAVA_LONG, 0, 99L);
                verify((int) appendOperation.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL, receiptCount) < 0 &&
                    receiptCount.get(ValueLayout.JAVA_LONG, 0) == 0,
                    "operation event append boundary did not reject missing recording");
                System.out.println("PASS: actual DLL layout fields=" + nativeFields.size() +
                    ", capture, normalization, and operation FFM signatures, rejected output");
            }
        }
        System.out.println("PASS: independent scratch slots, nested isolation, bounded cache, large/high-alignment scopes, exceptional release, empty pointers, normalization layout");
    }
}
