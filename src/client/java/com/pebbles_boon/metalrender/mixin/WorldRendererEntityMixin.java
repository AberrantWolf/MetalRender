package com.pebbles_boon.metalrender.mixin;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Foundational entity-capture observer for the Sodium 0.5 / 1.20.1 backport.
 *
 * <p>Hooks {@code WorldRenderer.renderEntity} to count visible entities per
 * frame, and flushes the counters from {@code WorldRenderer.render} TAIL.
 * Currently a no-op for rendering — MC's GL entity path is unmodified.
 *
 * <p>Gated by {@code metalrender.entity.observer} system property (default
 * off) so chunk-path testing isn't perturbed. See
 * {@link MetalEntityRenderer} for the full implementation plan.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererEntityMixin {

  @Inject(method = "renderEntity", at = @At("HEAD"))
  private void metalrender$captureEntity(Entity entity, double cameraX, double cameraY,
                                         double cameraZ, float tickDelta,
                                         MatrixStack matrices,
                                         VertexConsumerProvider vertexConsumers,
                                         CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    if (!MetalRenderConfig.entityCaptureObserver()) return;
    MetalEntityRenderer.get().captureEntity(entity, cameraX, cameraY, cameraZ, tickDelta);
  }

  @Inject(method = "render", at = @At("TAIL"))
  private void metalrender$flushEntities(MatrixStack matrices, float tickDelta, long limitTime,
                                         boolean renderBlockOutline, Camera camera,
                                         GameRenderer gameRenderer,
                                         LightmapTextureManager lightmapTextureManager,
                                         Matrix4f positionMatrix, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    if (!MetalRenderConfig.entityCaptureObserver()) return;
    MetalEntityRenderer.get().flushFrame();
  }
}
