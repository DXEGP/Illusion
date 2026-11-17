package io.github.dxegp.render.reconstruction.oit

import io.github.dxegp.d3d12.ffi.MCDRLayout
import io.github.dxegp.d3d12.ffi.MCDR_ABI
import io.github.dxegp.d3d12.resource.D3D12UploadRead
import io.github.dxegp.render.terrain.mesh.D3D12MeshDrawCapture
import io.github.dxegp.render.terrain.mesh.D3D12TerrainWorkGraph
import io.github.dxegp.d3d12.ffi.allocateFfmArray
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment

internal enum class OitTerrainReadSetRegistration {
    REGISTERED, ENTRY_UNAVAILABLE, GEOMETRY_UNAVAILABLE, MESHLET_UNAVAILABLE,
    UPLOAD_UNAVAILABLE, OWNER_UNAVAILABLE, UNSUPPORTED, OVER_BUDGET,
}

internal object OitTerrainReplayReadSet {
    fun registerReads(arena: Arena, recording: OitTerrainNativeRecording, token: Long,
        invocation: OitTerrainStageInvocation, prepared: D3D12TerrainWorkGraph.Prepared): OitTerrainReadSetRegistration {
        if (!MCDR_ABI.hasOitTerrainReplayReadSetV2()) return OitTerrainReadSetRegistration.ENTRY_UNAVAILABLE
        val facts = prepared.terrainFacts ?: return OitTerrainReadSetRegistration.OWNER_UNAVAILABLE
        val reads = when (val exact = facts.exactReadSet) {
            is D3D12MeshDrawCapture.TerrainExactReadSet.Available -> exact
            is D3D12MeshDrawCapture.TerrainExactReadSet.Unavailable -> return when (exact.reason) {
                D3D12MeshDrawCapture.TerrainExactReadSet.Reason.GEOMETRY_OWNER_UNAVAILABLE -> OitTerrainReadSetRegistration.OWNER_UNAVAILABLE
                D3D12MeshDrawCapture.TerrainExactReadSet.Reason.GEOMETRY_RANGE_MISMATCH -> OitTerrainReadSetRegistration.GEOMETRY_UNAVAILABLE
                D3D12MeshDrawCapture.TerrainExactReadSet.Reason.MESHLET_RANGE_UNAVAILABLE -> OitTerrainReadSetRegistration.MESHLET_UNAVAILABLE
            }
        }
        check(prepared.batchHandle != 0L && facts.pipeline == prepared.pipeline)
        val uploadReads = facts.uploadReads.map { fact ->
            val read = fact.read as? D3D12UploadRead.Available
                ?: return OitTerrainReadSetRegistration.UPLOAD_UNAVAILABLE
            if (!read.isCurrent(recording.context)) return OitTerrainReadSetRegistration.UPLOAD_UNAVAILABLE
            val role = when (val input = fact.input) {
                is D3D12MeshDrawCapture.TerrainUploadInput.Uniform -> input.graphicsBindingSlot
                D3D12MeshDrawCapture.TerrainUploadInput.TerrainGlobals -> 0x10000000
                D3D12MeshDrawCapture.TerrainUploadInput.TerrainSections -> 0x10000001
            }
            role to read
        }
        val uploadBytes = MCDRLayout.OIT_TERRAIN_UPLOAD_READ_V2.byteSize()
        val uploads = arena.allocateFfmArray(uploadReads.size, MCDRLayout.OIT_TERRAIN_UPLOAD_READ_V2)
        for ((index, entry) in uploadReads.withIndex()) {
            val (role, read) = entry
            val output = uploads.asSlice(index * uploadBytes, uploadBytes)
            output.set(MCDRLayout.I32, Upload.schemaVersion, 2)
            output.set(MCDRLayout.I32, Upload.structSize, Math.toIntExact(uploadBytes))
            output.set(MCDRLayout.I32, Upload.role, role)
            output.set(MCDRLayout.PTR, Upload.buffer, MemorySegment.ofAddress(read.bufferHandle))
            output.set(MCDRLayout.I64, Upload.byteOffset, read.byteOffset)
            output.set(MCDRLayout.I64, Upload.byteLength, read.byteLength)
            output.set(MCDRLayout.I64, Upload.contentVersion, read.contentVersion)
            output.set(MCDRLayout.I64, Upload.resourceVersion, invocation.frame.identity.resourceVersion)
            output.set(MCDRLayout.I64, Upload.ownerIdentity, read.ownerIdentity)
        }
        val rangeCount = Math.addExact(reads.geometry.size, reads.meshlets.size)
        val rangeBytes = MCDRLayout.OIT_TERRAIN_REPLAY_RANGE_V2.byteSize()
        val ranges = arena.allocateFfmArray(rangeCount, MCDRLayout.OIT_TERRAIN_REPLAY_RANGE_V2)
        for (index in reads.geometry.indices) {
            val geometry = reads.geometry[index]
            val meshlet = reads.meshlets[index]
            check(geometry.commandIndex == index && meshlet.commandIndex == index)
            val geometryOutput = ranges.asSlice(index * 2L * rangeBytes, rangeBytes)
            geometryOutput.set(MCDRLayout.I32, Range.schemaVersion, 2)
            geometryOutput.set(MCDRLayout.I32, Range.structSize, Math.toIntExact(rangeBytes))
            geometryOutput.set(MCDRLayout.I32, Range.role, 1)
            geometryOutput.set(MCDRLayout.I32, Range.commandIndex, index)
            geometryOutput.set(MCDRLayout.I64, Range.generation, geometry.allocationGeneration)
            geometryOutput.set(MCDRLayout.I64, Range.ownerIdentity, geometry.ownerIdentity)
            geometryOutput.set(MCDRLayout.I64, Range.byteOffset, geometry.byteOffset)
            geometryOutput.set(MCDRLayout.I64, Range.byteLength, geometry.byteLength)
            geometryOutput.set(MCDRLayout.PTR, Range.buffer, MemorySegment.ofAddress(geometry.bufferHandle))
            val meshletOutput = ranges.asSlice((index * 2L + 1L) * rangeBytes, rangeBytes)
            meshletOutput.set(MCDRLayout.I32, Range.schemaVersion, 2)
            meshletOutput.set(MCDRLayout.I32, Range.structSize, Math.toIntExact(rangeBytes))
            meshletOutput.set(MCDRLayout.I32, Range.role, 2)
            meshletOutput.set(MCDRLayout.I32, Range.commandIndex, index)
            meshletOutput.set(MCDRLayout.I64, Range.generation, meshlet.generation)
            meshletOutput.set(MCDRLayout.I64, Range.ownerIdentity, meshlet.generation)
            meshletOutput.set(MCDRLayout.I64, Range.byteOffset, meshlet.byteOffset)
            meshletOutput.set(MCDRLayout.I64, Range.byteLength, meshlet.byteLength)
            meshletOutput.set(MCDRLayout.PTR, Range.buffer, MemorySegment.ofAddress(meshlet.bufferHandle))
        }
        val input = arena.allocate(MCDRLayout.OIT_TERRAIN_REPLAY_READ_SET_V2)
        input.set(MCDRLayout.I32, ReadSet.schemaVersion, 2)
        input.set(MCDRLayout.I32, ReadSet.structSize, Math.toIntExact(MCDRLayout.OIT_TERRAIN_REPLAY_READ_SET_V2.byteSize()))
        input.set(MCDRLayout.I32, ReadSet.geometryRangeCount, rangeCount)
        input.set(MCDRLayout.I32, ReadSet.uploadBindingCount, uploadReads.size)
        input.set(MCDRLayout.I64, ReadSet.frameSerial, recording.frameSerial)
        input.set(MCDRLayout.I64, ReadSet.scopeSerial, invocation.scope.serial)
        input.set(MCDRLayout.I64, ReadSet.programGeneration, recording.programGeneration)
        input.set(MCDRLayout.I64, ReadSet.resourceVersion, invocation.frame.identity.resourceVersion)
        input.set(MCDRLayout.PTR, ReadSet.geometryRanges, ranges)
        input.set(MCDRLayout.PTR, ReadSet.uploadBindings, uploads)
        return when (val status = MCDR_ABI.D3D12OitRegisterTerrainReplayReadSetV2(recording.context,
            recording.frameHandle, token, prepared.batchHandle, facts.vertexBuffer, facts.vertexOffset, input)) {
            1 -> OitTerrainReadSetRegistration.REGISTERED
            -2 -> OitTerrainReadSetRegistration.UNSUPPORTED
            -3 -> OitTerrainReadSetRegistration.OVER_BUDGET
            -20 -> OitTerrainReadSetRegistration.GEOMETRY_UNAVAILABLE
            -21 -> OitTerrainReadSetRegistration.MESHLET_UNAVAILABLE
            -22 -> OitTerrainReadSetRegistration.UPLOAD_UNAVAILABLE
            -23 -> OitTerrainReadSetRegistration.OWNER_UNAVAILABLE
            else -> throw OitRecordException(when (status) {
                -1 -> OitRecordFailure.INVALID_CONTRACT
                -4 -> OitRecordFailure.BUSY
                -5 -> OitRecordFailure.STALE
                -6 -> OitRecordFailure.UNKNOWN_SUBMISSION
                -8 -> OitRecordFailure.RECORDING_FAILED
                else -> OitRecordFailure.NATIVE_CONTRACT
            }, "register terrain reads", invocation.frame.identity, MCDR_ABI.D3D12LastError())
        }
    }

    private object Range {
        private val layout = MCDRLayout.OIT_TERRAIN_REPLAY_RANGE_V2
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val role = layout.byteOffset(groupElement("role"))
        val commandIndex = layout.byteOffset(groupElement("commandIndex"))
        val generation = layout.byteOffset(groupElement("generation"))
        val ownerIdentity = layout.byteOffset(groupElement("ownerIdentity"))
        val byteOffset = layout.byteOffset(groupElement("byteOffset"))
        val byteLength = layout.byteOffset(groupElement("byteLength"))
        val buffer = layout.byteOffset(groupElement("object"))
    }

    private object ReadSet {
        private val layout = MCDRLayout.OIT_TERRAIN_REPLAY_READ_SET_V2
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val geometryRangeCount = layout.byteOffset(groupElement("geometryRangeCount"))
        val uploadBindingCount = layout.byteOffset(groupElement("uploadBindingCount"))
        val frameSerial = layout.byteOffset(groupElement("frameSerial"))
        val scopeSerial = layout.byteOffset(groupElement("producerScopeSerial"))
        val programGeneration = layout.byteOffset(groupElement("programGeneration"))
        val resourceVersion = layout.byteOffset(groupElement("resourceVersion"))
        val geometryRanges = layout.byteOffset(groupElement("geometryRanges"))
        val uploadBindings = layout.byteOffset(groupElement("uploadBindings"))
    }

    private object Upload {
        private val layout = MCDRLayout.OIT_TERRAIN_UPLOAD_READ_V2
        val schemaVersion = layout.byteOffset(groupElement("schemaVersion"))
        val structSize = layout.byteOffset(groupElement("structSize"))
        val role = layout.byteOffset(groupElement("role"))
        val buffer = layout.byteOffset(groupElement("object"))
        val byteOffset = layout.byteOffset(groupElement("byteOffset"))
        val byteLength = layout.byteOffset(groupElement("byteLength"))
        val contentVersion = layout.byteOffset(groupElement("contentVersion"))
        val resourceVersion = layout.byteOffset(groupElement("resourceVersion"))
        val ownerIdentity = layout.byteOffset(groupElement("ownerIdentity"))
    }
}
