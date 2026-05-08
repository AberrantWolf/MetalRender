package com.pebbles_boon.metalrender.mixin.sodium;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.sodium.MetalChunkRenderer;
import com.pebbles_boon.metalrender.sodium.MetalQuadIndexBuffer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Replaces Sodium 0.5's {@code DefaultChunkRenderer} with our Metal-backed
 * implementation, and intercepts {@code processChunkBuildResults} to mirror
 * each section's mesh data into Metal-owned buffers.
 *
 * <p>{@code RenderRegionManagerMixin} cancels Sodium's parallel GL VBO upload
 * so the same {@link ChunkBuildOutput}s aren't uploaded twice; sprite-active
 * tracking and section bookkeeping still run during meshing and the
 * surrounding result-processing loop.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager",
       remap = false)
public abstract class RenderSectionManagerMixin {

  @Shadow @Final @Mutable
  private ChunkRenderer chunkRenderer;

  @Unique
  private MetalChunkRenderer metalrender$ours;

  @Inject(method = "<init>", at = @At("RETURN"), require = 0)
  private void metalrender$install(CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    ChunkRenderer old = this.chunkRenderer;
    this.metalrender$ours = new MetalChunkRenderer();
    this.chunkRenderer = this.metalrender$ours;
    if (old != null) {
      // Sodium's DefaultChunkRenderer needs a real CommandList to free its GL
      // buffers; passing null leaks them with an NPE in deleteBuffer().
      try (CommandList cl = RenderDevice.INSTANCE.createCommandList()) {
        old.delete(cl);
      } catch (Throwable t) {
        MetalLogger.warn("DefaultChunkRenderer.delete threw during swap: %s", t.toString());
      }
    }
  }

  // Diagnostic counters — categorize each captured output so we can verify
  // that Sodium's quadrant re-sort flow lands here. A re-sort produces a
  // ChunkBuildOutput whose meshes map contains ONLY the TRANSLUCENT pass
  // (the SOLID/CUTOUT meshes are unchanged and not re-emitted). A full
  // rebuild emits one or more of SOLID/CUTOUT.
  // Logged every LOG_INTERVAL captures combined (full + resort + empty).
  @Unique private int metalrender$diagFullRebuilds = 0;
  @Unique private int metalrender$diagResortOnly = 0;
  @Unique private int metalrender$diagEmpty = 0;
  @Unique private static final int METALRENDER_DIAG_LOG_INTERVAL = 200;

  @Inject(method = "processChunkBuildResults", at = @At("HEAD"), require = 0)
  private void metalrender$capture(ArrayList<ChunkBuildOutput> results, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled() || metalrender$ours == null) return;
    long indexBuffer = MetalQuadIndexBuffer.getOrCreate();
    if (indexBuffer == 0L) return;
    long device = MetalRenderClient.getHandle();
    for (ChunkBuildOutput output : results) {
      if (output == null || output.render == null || output.render.isDisposed()) continue;
      int passMask = metalrender$captureOutput(output, device, indexBuffer);
      // bit 0=SOLID, bit 1=CUTOUT, bit 2=TRANSLUCENT
      if (passMask == 0) {
        metalrender$diagEmpty++;
      } else if (passMask == 0b100) {
        metalrender$diagResortOnly++;
      } else {
        metalrender$diagFullRebuilds++;
      }
    }
    int total = metalrender$diagFullRebuilds + metalrender$diagResortOnly + metalrender$diagEmpty;
    if (total >= METALRENDER_DIAG_LOG_INTERVAL) {
      MetalLogger.info("Capture stats over last %d outputs: full=%d resort-only=%d empty=%d",
          total, metalrender$diagFullRebuilds, metalrender$diagResortOnly,
          metalrender$diagEmpty);
      metalrender$diagFullRebuilds = 0;
      metalrender$diagResortOnly = 0;
      metalrender$diagEmpty = 0;
    }
  }

  @Unique
  private int metalrender$captureOutput(ChunkBuildOutput output, long device, long indexBuffer) {
    int mask = 0;
    if (metalrender$capturePass(output, DefaultTerrainRenderPasses.SOLID, 0, device, indexBuffer) != 0)
      mask |= 0b001;
    if (metalrender$capturePass(output, DefaultTerrainRenderPasses.CUTOUT, 1, device, indexBuffer) != 0)
      mask |= 0b010;
    if (metalrender$capturePass(output, DefaultTerrainRenderPasses.TRANSLUCENT, 2, device, indexBuffer) != 0)
      mask |= 0b100;
    return mask;
  }

  // Hard upper bound: must stay in sync with MetalQuadIndexBuffer.MAX_QUADS.
  // A rebuilt section that exceeds this would index past the shared IBO and
  // corrupt rendering; we drop the pass and warn instead.
  @Unique
  private static final int METALRENDER_MAX_QUADS_PER_SECTION = 65536;

  @Unique
  private int metalrender$capturePass(ChunkBuildOutput output, TerrainRenderPass pass, int passId,
                                      long device, long indexBuffer) {
    BuiltSectionMeshParts parts = output.getMesh(pass);
    int vertexCount = 0;
    if (parts != null) {
      for (VertexRange r : parts.getVertexRanges()) {
        if (r != null) vertexCount += r.vertexCount();
      }
    }
    // No geometry for this pass after the rebuild — drop any prior cache slot
    // so we don't keep drawing stale data.
    if (parts == null || vertexCount == 0) {
      metalrender$ours.invalidatePass(output.render.getChunkX(), output.render.getChunkY(),
                                      output.render.getChunkZ(), passId);
      return 0;
    }
    int quadCount = vertexCount / 4;
    if (quadCount > METALRENDER_MAX_QUADS_PER_SECTION) {
      MetalLogger.warn("Section (%d,%d,%d) pass %d has %d quads (> %d max); dropping",
          output.render.getChunkX(), output.render.getChunkY(), output.render.getChunkZ(),
          passId, quadCount, METALRENDER_MAX_QUADS_PER_SECTION);
      metalrender$ours.invalidatePass(output.render.getChunkX(), output.render.getChunkY(),
                                      output.render.getChunkZ(), passId);
      return 0;
    }
    int indexCount = quadCount * 6;
    ByteBuffer vertexData = parts.getVertexData().getDirectBuffer();
    long vbo = NativeBridge.nUploadChunkMesh(device, vertexData, vertexCount, 0);
    if (vbo == 0L) return 0;
    metalrender$ours.recordMesh(output.render, passId, vbo, indexBuffer, 0, indexCount);
    return 1;
  }

  @Inject(method = "onSectionRemoved", at = @At("HEAD"), require = 0)
  private void metalrender$invalidate(int x, int y, int z, CallbackInfo ci) {
    if (metalrender$ours == null) return;
    metalrender$ours.invalidate(x, y, z);
  }
}
