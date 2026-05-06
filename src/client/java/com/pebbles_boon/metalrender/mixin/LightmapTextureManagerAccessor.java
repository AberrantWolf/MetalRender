package com.pebbles_boon.metalrender.mixin;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.texture.NativeImageBackedTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code LightmapTextureManager.texture} so we can read its
 * {@code NativeImage} pixel data each frame and mirror it into a Metal
 * texture for the chunk fragment shader.
 */
@Mixin(LightmapTextureManager.class)
public interface LightmapTextureManagerAccessor {
  @Accessor("texture")
  NativeImageBackedTexture metalrender$getTexture();
}
