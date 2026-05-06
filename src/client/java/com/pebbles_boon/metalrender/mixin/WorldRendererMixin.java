package com.pebbles_boon.metalrender.mixin;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.sodium.MetalTextureBridge;
import net.minecraft.client.render.Camera;
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

  @Inject(method = "render", at = @At("HEAD"))
  private void metalrender$beginFrame(MatrixStack matrices, float tickDelta, long limitTime,
                                      boolean renderBlockOutline, Camera camera,
                                      GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager,
                                      Matrix4f positionMatrix, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    long handle = MetalRenderClient.getHandle();

    positionMatrix.get(metalrender$projTmp);
    matrices.peek().getPositionMatrix().get(metalrender$mvTmp);
    Vec3d pos = camera.getPos();

    NativeBridge.nSetProjectionMatrix(handle, metalrender$projTmp);
    NativeBridge.nSetModelViewMatrix(handle, metalrender$mvTmp);
    NativeBridge.nSetCameraPosition(handle, pos.x, pos.y, pos.z);
    NativeBridge.nBeginFrame(handle, metalrender$projTmp, metalrender$mvTmp, 0.0f, 0.0f);

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
