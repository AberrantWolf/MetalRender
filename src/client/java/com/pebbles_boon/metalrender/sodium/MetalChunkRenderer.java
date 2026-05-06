package com.pebbles_boon.metalrender.sodium;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;

import java.util.Iterator;

/**
 * Sodium {@link ChunkRenderer} that dispatches chunk draws through the Metal
 * native bridge instead of OpenGL. Installed by
 * {@code RenderSectionManagerMixin} in place of Sodium's
 * {@code DefaultChunkRenderer}.
 *
 * <p>Per-section, per-pass mesh data is uploaded by the build-result intercept
 * (see {@code RenderSectionManagerMixin.metalrender$capture}) and cached here
 * keyed by packed chunk coordinate. {@link #render} walks Sodium's
 * visibility-sorted region/section iterator and issues one
 * {@link NativeBridge#nDrawChunkSection} per recorded section.
 */
public final class MetalChunkRenderer implements ChunkRenderer {
  // Packed (chunkX, chunkY, chunkZ) → 3-slot per-pass mesh array.
  // Using Long2ObjectOpenHashMap from fastutil (already a Sodium transitive dep).
  private final Long2ObjectMap<MetalSectionMesh[]> meshes = new Long2ObjectOpenHashMap<>();

  // Diagnostic — number of section draws dispatched, accumulated across passes,
  // logged every LOG_EVERY_N invocations of render() (3 per frame typically).
  private int diagDrawsAcc = 0;
  private int diagInvocations = 0;
  private static final int LOG_EVERY_N = 360; // ~ every 2s @ 60fps × 3 passes

  public MetalChunkRenderer() {
    MetalLogger.info("MetalChunkRenderer installed (Sodium 0.5 chunk path)");
  }

  @Override
  public void render(ChunkRenderMatrices matrices, CommandList commandList,
                     ChunkRenderListIterable renderLists, TerrainRenderPass pass,
                     CameraTransform camera) {
    if (!MetalRenderClient.isEnabled()) return;
    int passId = passToId(pass);
    if (passId < 0) return;

    long handle = MetalRenderClient.getHandle();
    long frameContext = NativeBridge.nGetCurrentFrameContext(handle);
    if (frameContext == 0L) return;

    int sectionsDrawn = 0;
    Iterator<ChunkRenderList> regionIt = renderLists.iterator(pass.isReverseOrder());
    while (regionIt.hasNext()) {
      ChunkRenderList list = regionIt.next();
      var sectionIt = list.sectionsWithGeometryIterator(pass.isReverseOrder());
      if (sectionIt == null) continue;

      RenderRegion region = list.getRegion();
      while (sectionIt.hasNext()) {
        int idx = sectionIt.nextByteAsInt();
        RenderSection section = region.getSection(idx);
        if (section == null) continue;
        long key = packCoord(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        MetalSectionMesh[] arr = meshes.get(key);
        if (arr == null) continue;
        MetalSectionMesh mesh = arr[passId];
        if (mesh == null || mesh.indexCount == 0) continue;

        // Camera-relative section origin (block units). Mirrors
        // DefaultChunkRenderer.getCameraTranslation: (chunkBlockPos - cameraIntBlocks) - cameraFrac.
        float ox = (section.getOriginX() - camera.intX) - camera.fracX;
        float oy = (section.getOriginY() - camera.intY) - camera.fracY;
        float oz = (section.getOriginZ() - camera.intZ) - camera.fracZ;

        NativeBridge.nDrawChunkSection(frameContext, mesh.vbo, mesh.ibo,
            mesh.indexOffset, mesh.indexCount, ox, oy, oz, passId);
        sectionsDrawn++;
      }
    }

    diagDrawsAcc += sectionsDrawn;
    diagInvocations++;
    if (diagInvocations >= LOG_EVERY_N) {
      MetalLogger.info("MetalChunkRenderer: %d section draws / %d render() calls (cache size=%d)",
          diagDrawsAcc, diagInvocations, meshes.size());
      diagDrawsAcc = 0;
      diagInvocations = 0;
    }
  }

  @Override
  public void delete(CommandList commandList) {
    clearAll();
  }

  // --- Build-result intercept API (called from RenderSectionManagerMixin) ---

  /**
   * Replace the cached mesh handles for one (section, pass) tuple. Frees any
   * previous handles for the same slot first.
   */
  public void recordMesh(RenderSection section, int passId, long vbo, long ibo,
                         int indexOffset, int indexCount) {
    if (passId < 0 || passId >= 3) return;
    long key = packCoord(section.getChunkX(), section.getChunkY(), section.getChunkZ());
    MetalSectionMesh[] arr = meshes.get(key);
    if (arr == null) {
      arr = new MetalSectionMesh[3];
      meshes.put(key, arr);
    }
    MetalSectionMesh prev = arr[passId];
    if (prev != null) {
      freeMesh(prev);
    }
    arr[passId] = new MetalSectionMesh(vbo, ibo, indexOffset, indexCount);
  }

  /** Drop and free everything cached for one section by chunk coords. */
  public void invalidate(int chunkX, int chunkY, int chunkZ) {
    MetalSectionMesh[] arr = meshes.remove(packCoord(chunkX, chunkY, chunkZ));
    if (arr == null) return;
    for (MetalSectionMesh m : arr) {
      freeMesh(m);
    }
  }

  /** Drop everything (renderer teardown / world unload). */
  public void clearAll() {
    int freed = 0;
    for (MetalSectionMesh[] arr : meshes.values()) {
      for (MetalSectionMesh m : arr) {
        if (m != null) freed++;
        freeMesh(m);
      }
    }
    meshes.clear();
    if (freed > 0) {
      MetalLogger.info("MetalChunkRenderer: freed %d cached section meshes", freed);
    }
  }

  private static void freeMesh(MetalSectionMesh m) {
    if (m == null) return;
    if (m.vbo != 0) NativeBridge.nDestroyBuffer(m.vbo);
    // ibo handle is the shared quad-index buffer — not freed here.
  }

  private static int passToId(TerrainRenderPass pass) {
    if (pass == DefaultTerrainRenderPasses.SOLID) return 0;
    if (pass == DefaultTerrainRenderPasses.CUTOUT) return 1;
    if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) return 2;
    return -1;
  }

  // 21 bits per axis: room for ±1M chunks in each direction. Y is signed.
  private static long packCoord(int x, int y, int z) {
    return ((long) (x & 0x1FFFFF))
         | ((long) (z & 0x1FFFFF) << 21)
         | ((long) (y & 0x1FFFFF) << 42);
  }
}
