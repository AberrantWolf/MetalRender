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

  @Inject(method = "processChunkBuildResults", at = @At("HEAD"), require = 0)
  private void metalrender$capture(ArrayList<ChunkBuildOutput> results, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled() || metalrender$ours == null) return;
    long indexBuffer = MetalQuadIndexBuffer.getOrCreate();
    if (indexBuffer == 0L) return;
    long device = MetalRenderClient.getHandle();
    int captured = 0;
    for (ChunkBuildOutput output : results) {
      if (output == null || output.render == null || output.render.isDisposed()) continue;
      captured += metalrender$captureOutput(output, device, indexBuffer);
    }
    if (captured > 0) {
      MetalLogger.debug("Captured %d (section,pass) meshes into Metal buffers", captured);
    }
  }

  @Unique
  private int metalrender$captureOutput(ChunkBuildOutput output, long device, long indexBuffer) {
    int count = 0;
    count += metalrender$capturePass(output, DefaultTerrainRenderPasses.SOLID, 0, device, indexBuffer);
    count += metalrender$capturePass(output, DefaultTerrainRenderPasses.CUTOUT, 1, device, indexBuffer);
    count += metalrender$capturePass(output, DefaultTerrainRenderPasses.TRANSLUCENT, 2, device, indexBuffer);
    return count;
  }

  @Unique
  private int metalrender$capturePass(ChunkBuildOutput output, TerrainRenderPass pass, int passId,
                                      long device, long indexBuffer) {
    BuiltSectionMeshParts parts = output.getMesh(pass);
    if (parts == null) return 0;
    ByteBuffer vertexData = parts.getVertexData().getDirectBuffer();
    int vertexCount = 0;
    for (VertexRange r : parts.getVertexRanges()) {
      if (r != null) vertexCount += r.vertexCount();
    }
    if (vertexCount == 0) return 0;
    int indexCount = (vertexCount / 4) * 6;
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
