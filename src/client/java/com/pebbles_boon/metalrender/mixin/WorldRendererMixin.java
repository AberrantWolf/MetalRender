package com.pebbles_boon.metalrender.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.sodium.MetalTextureBridge;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.FogShape;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
  // Reused per-frame; render() runs on the client thread so this stays
  // single-threaded. Keeps allocation off the per-frame hot path.
  private final float[] metalrender$projTmp = new float[16];
  private final float[] metalrender$mvTmp = new float[16];
  // Last-seen framebuffer dimensions; on change we tell Metal to rebuild
  // its IOSurfaces at the new size (the compositor will re-bind them on
  // its next blit when boundWidth/Height differs).
  private int metalrender$lastFbW = 0;
  private int metalrender$lastFbH = 0;

  @Inject(method = "render", at = @At("HEAD"))
  private void metalrender$beginFrame(MatrixStack matrices, float tickDelta, long limitTime,
                                      boolean renderBlockOutline, Camera camera,
                                      GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager,
                                      Matrix4f positionMatrix, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    long handle = MetalRenderClient.getHandle();

    var mc = net.minecraft.client.MinecraftClient.getInstance();
    var fb = mc != null ? mc.getFramebuffer() : null;
    if (fb != null) {
      int fbW = fb.textureWidth;
      int fbH = fb.textureHeight;
      if (fbW > 0 && fbH > 0 && (fbW != metalrender$lastFbW || fbH != metalrender$lastFbH)) {
        NativeBridge.nResize(handle, fbW, fbH, 1.0f);
        metalrender$lastFbW = fbW;
        metalrender$lastFbH = fbH;
      }
    }

    positionMatrix.get(metalrender$projTmp);
    matrices.peek().getPositionMatrix().get(metalrender$mvTmp);
    Vec3d pos = camera.getPos();

    NativeBridge.nSetProjectionMatrix(handle, metalrender$projTmp);
    NativeBridge.nSetModelViewMatrix(handle, metalrender$mvTmp);
    NativeBridge.nSetCameraPosition(handle, pos.x, pos.y, pos.z);
    // BackgroundRenderer.applyFog() runs before WorldRenderer.render and
    // populates these via the RenderSystem fog state on MC 1.20.1, so by the
    // time we read them here they reflect the active fog distance for this
    // dimension/biome/weather. Without forwarding them, Metal terrain
    // renders without distance fog and looks crisp at the far plane while
    // vanilla GL geometry around it fades correctly.
    float fogStart = RenderSystem.getShaderFogStart();
    float fogEnd = RenderSystem.getShaderFogEnd();
    NativeBridge.nBeginFrame(handle, metalrender$projTmp, metalrender$mvTmp, fogStart, fogEnd);
    // Color + shape can't go through nBeginFrame (its signature predates the
    // chunk path); push them via nUploadFogParams so the chunk shader can
    // tint the fog correctly per dimension (overworld blue, nether red,
    // underwater blue-tint, etc.) and pick the right distance metric
    // (CYLINDER for underwater so the column above isn't fogged out).
    float[] fogColor = RenderSystem.getShaderFogColor();
    int fogShape = (RenderSystem.getShaderFogShape() == FogShape.CYLINDER) ? 1 : 0;
    NativeBridge.nUploadFogParams(handle,
        fogColor[0], fogColor[1], fogColor[2], 1.0f,
        fogStart, fogEnd, fogShape);

    // Mirror MC's block atlas (one-time) + lightmap (per-frame) into Metal so
    // the chunk fragment shader has real textures and lighting.
    MetalTextureBridge.updatePerFrame();
  }

  @Inject(method = "render", at = @At("RETURN"))
  private void metalrender$endFrame(MatrixStack matrices, float tickDelta, long limitTime,
                                    boolean renderBlockOutline, Camera camera,
                                    GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager,
                                    Matrix4f positionMatrix, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    NativeBridge.nEndFrame(MetalRenderClient.getHandle());
  }
}
