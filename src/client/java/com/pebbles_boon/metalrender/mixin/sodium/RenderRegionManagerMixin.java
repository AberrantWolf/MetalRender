package com.pebbles_boon.metalrender.mixin.sodium;

import com.pebbles_boon.metalrender.MetalRenderClient;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * Skips Sodium's GL VBO upload for chunk meshes when MetalRender is active.
 * The same {@link ChunkBuildOutput}s are intercepted by
 * {@code RenderSectionManagerMixin} and uploaded into Metal-owned buffers, so
 * Sodium's GL upload would just be wasted work.
 *
 * <p>The surrounding bookkeeping in {@code processChunkBuildResults}
 * (updateSectionInfo, setLastBuiltFrame, build cancellation token clearing) is
 * still allowed to run — it's the GL upload specifically that's redundant.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager",
       remap = false)
public abstract class RenderRegionManagerMixin {

  @Inject(method = "uploadMeshes(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;Ljava/util/Collection;)V",
          at = @At("HEAD"), cancellable = true, require = 0)
  private void metalrender$skipGlUpload(CommandList commandList,
                                        Collection<ChunkBuildOutput> results,
                                        CallbackInfo ci) {
    if (MetalRenderClient.isEnabled()) {
      ci.cancel();
    }
  }
}
