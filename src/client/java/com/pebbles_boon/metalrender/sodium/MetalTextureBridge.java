package com.pebbles_boon.metalrender.sodium;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.mixin.LightmapTextureManagerAccessor;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Bridges Minecraft's GL-side block atlas + lightmap textures into the Metal
 * texture slots that {@code fragment_terrain} samples.
 *
 * <p>Atlas: large but static — read back via {@code glGetTexImage} once at
 * first frame after world load, uploaded as a Metal texture, registered as
 * {@code g_blockAtlas} via {@code nBindTexture(_, _, 0)}.
 *
 * <p>Lightmap: 16×16, per-frame — read directly from the
 * {@code NativeImageBackedTexture} via accessor mixin, uploaded each frame
 * via {@code nUpdateTexture2D}.
 */
public final class MetalTextureBridge {
  private static long blockAtlasMetalHandle = 0L;
  private static int blockAtlasW = 0, blockAtlasH = 0;
  private static long lightmapMetalHandle = 0L;
  private static int lightmapW = 0, lightmapH = 0;

  // Once an atlas upload fails for non-availability reasons (e.g., GL bind
  // returned 0), we throttle the retry rate so we don't spam glGetTexImage
  // every frame for an entire session.
  private static int atlasRetryCountdown = 0;

  private MetalTextureBridge() {}

  /** Per-frame entry point: ensures atlas is uploaded once, refreshes lightmap. */
  public static void updatePerFrame() {
    if (!MetalRenderClient.isEnabled()) return;
    long handle = MetalRenderClient.getHandle();
    if (handle == 0L) return;
    uploadBlockAtlasIfNeeded(handle);
    updateLightmap(handle);
  }

  private static void uploadBlockAtlasIfNeeded(long handle) {
    if (blockAtlasMetalHandle != 0L) return;
    if (atlasRetryCountdown > 0) { atlasRetryCountdown--; return; }
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc.getBakedModelManager() == null) { atlasRetryCountdown = 30; return; }
    SpriteAtlasTexture atlas;
    try {
      atlas = mc.getBakedModelManager().getAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
    } catch (Throwable t) {
      atlasRetryCountdown = 60;
      return;
    }
    if (atlas == null) { atlasRetryCountdown = 30; return; }
    int glId = atlas.getGlId();
    if (glId <= 0) { atlasRetryCountdown = 30; return; }

    int prevBound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
    int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
    int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
    if (w <= 0 || h <= 0 || w > 16384 || h > 16384) {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBound);
      atlasRetryCountdown = 60;
      return;
    }

    int sizeBytes = w * h * 4;
    ByteBuffer buf = MemoryUtil.memAlloc(sizeBytes);
    try {
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
      byte[] bytes = new byte[sizeBytes];
      buf.get(bytes);
      long mtlHandle = NativeBridge.nCreateTexture2D(handle, w, h, bytes);
      if (mtlHandle == 0L) {
        atlasRetryCountdown = 120;
        MetalLogger.warn("Block atlas: nCreateTexture2D failed (%dx%d)", w, h);
        return;
      }
      NativeBridge.nBindTexture(handle, mtlHandle, 0);
      blockAtlasMetalHandle = mtlHandle;
      blockAtlasW = w;
      blockAtlasH = h;
      MetalLogger.info("Block atlas uploaded to Metal: %dx%d (%d MB)", w, h, sizeBytes / (1024 * 1024));
    } finally {
      MemoryUtil.memFree(buf);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevBound);
    }
  }

  private static void updateLightmap(long handle) {
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc.gameRenderer == null) return;
    LightmapTextureManager ltm = mc.gameRenderer.getLightmapTextureManager();
    if (ltm == null) return;
    NativeImageBackedTexture nibt = ((LightmapTextureManagerAccessor) (Object) ltm).metalrender$getTexture();
    if (nibt == null) return;
    NativeImage img = nibt.getImage();
    if (img == null) return;
    int w = img.getWidth();
    int h = img.getHeight();
    if (w <= 0 || h <= 0 || (w * h) > 4096) return; // sanity bound

    int sizeBytes = w * h * 4;
    byte[] bytes = new byte[sizeBytes];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        // NativeImage.Format.RGBA: bit layout (A<<24)|(B<<16)|(G<<8)|R
        // i.e., in-memory bytes [R, G, B, A] — matches nCreateTexture2D RGBA8 expectation.
        int color = img.getColor(x, y);
        int i = (y * w + x) * 4;
        bytes[i + 0] = (byte) (color);
        bytes[i + 1] = (byte) (color >> 8);
        bytes[i + 2] = (byte) (color >> 16);
        bytes[i + 3] = (byte) (color >> 24);
      }
    }

    if (lightmapMetalHandle == 0L || w != lightmapW || h != lightmapH) {
      // First frame, or lightmap dims changed (very rare). Create + bind.
      long mtlHandle = NativeBridge.nCreateTexture2D(handle, w, h, bytes);
      if (mtlHandle == 0L) return;
      NativeBridge.nBindTexture(handle, mtlHandle, 1);
      lightmapMetalHandle = mtlHandle;
      lightmapW = w;
      lightmapH = h;
      MetalLogger.info("Lightmap uploaded to Metal: %dx%d", w, h);
    } else {
      NativeBridge.nUpdateTexture2D(lightmapMetalHandle, w, h, bytes);
    }
  }

  /** Reset on world unload so a fresh atlas/lightmap upload happens on next world. */
  public static void reset() {
    blockAtlasMetalHandle = 0L;
    blockAtlasW = blockAtlasH = 0;
    lightmapMetalHandle = 0L;
    lightmapW = lightmapH = 0;
    atlasRetryCountdown = 0;
  }
}
