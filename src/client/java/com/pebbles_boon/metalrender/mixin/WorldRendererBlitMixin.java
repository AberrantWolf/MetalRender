package com.pebbles_boon.metalrender.mixin;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.sodium.MetalCompositor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Composites the IOSurface-backed Metal output onto MC's main framebuffer at
 * the end of the world render (after terrain/entities/particles, before HUD).
 *
 * <p>Gated behind {@code -Dmetalrender.compositor.blitOverlay=true}; otherwise
 * a no-op so the chunk-renderer harness can run unaffected.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererBlitMixin {
  @Inject(method = "render", at = @At("RETURN"))
  private void metalrender$compositeMetal(MatrixStack matrices, float tickDelta, long limitTime,
                                          boolean renderBlockOutline, Camera camera,
                                          GameRenderer gameRenderer,
                                          LightmapTextureManager lightmapTextureManager,
                                          Matrix4f projectionMatrix, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    if (!MetalRenderConfig.compositorBlitOverlay()) return;
    Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
    if (fb == null) return;
    MetalCompositor.get().blitOverlay(fb);
  }
}
