package com.pebbles_boon.metalrender.mixin;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.sodium.MetalTextureBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.RunArgs;
import net.minecraft.client.util.Window;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
  @Inject(method = "<init>", at = @At("RETURN"))
  private void metalrender$initNative(RunArgs args, CallbackInfo ci) {
    if (!MetalRenderClient.shouldInit()) return;
    MinecraftClient self = (MinecraftClient) (Object) this;
    Window window = self.getWindow();
    int width = window != null ? window.getFramebufferWidth() : 1280;
    int height = window != null ? window.getFramebufferHeight() : 720;
    MetalRenderClient.tryInit(width, height, 1.0f);
  }

  @Inject(method = "close", at = @At("HEAD"))
  private void metalrender$destroyNative(CallbackInfo ci) {
    MetalRenderClient.destroy();
  }

  @Inject(method = "joinWorld", at = @At("RETURN"))
  private void metalrender$onWorldLoaded(ClientWorld world, CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    NativeBridge.nOnWorldLoaded(MetalRenderClient.getHandle());
  }

  @Inject(method = "disconnect()V", at = @At("HEAD"))
  private void metalrender$onWorldUnloaded(CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) return;
    NativeBridge.nOnWorldUnloaded(MetalRenderClient.getHandle());
    MetalTextureBridge.reset();
  }
}
