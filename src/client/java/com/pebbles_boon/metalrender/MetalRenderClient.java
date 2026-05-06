package com.pebbles_boon.metalrender;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;

public class MetalRenderClient implements ClientModInitializer {
  private static MetalRenderConfig config;
  private static boolean metalAvailable = false;
  private static volatile long handle = 0L;

  @Override
  public void onInitializeClient() {
    MetalLogger.info("MetalRender 1.20.1 Sodium 0.5 backport initializing.");

    config = MetalRenderConfig.load();
    if (!config.enableMetalRendering) {
      MetalLogger.info("Metal rendering disabled via config; will not probe hardware.");
      return;
    }

    try {
      NativeBridge.loadLibrary();
    } catch (UnsatisfiedLinkError e) {
      MetalLogger.warn("Native metalrender library not available: %s", e.getMessage());
      return;
    } catch (Throwable t) {
      MetalLogger.warn("Native metalrender library load failed: %s", t.toString());
      return;
    }

    try {
      metalAvailable = MetalHardwareChecker.isMetalSupported();
      if (metalAvailable) {
        MetalLogger.info("Metal device available: %s", MetalHardwareChecker.getDeviceName());
      } else {
        MetalLogger.info("Metal device probe returned unsupported.");
      }
    } catch (Throwable t) {
      MetalLogger.warn("Metal hardware probe failed: %s", t.toString());
      metalAvailable = false;
    }

    if (!isSodiumLoaded()) {
      MetalLogger.info("Sodium not detected; chunk rendering hooks remain inert.");
    }
  }

  public static MetalRenderConfig getConfig() {
    return config;
  }

  public static boolean isMetalAvailable() {
    return metalAvailable;
  }

  /**
   * Whether the harness should attempt to call {@code nInit} when a window
   * becomes available. Distinct from {@link #isEnabled()} which additionally
   * requires {@code nInit} to have succeeded.
   */
  public static boolean shouldInit() {
    return metalAvailable && config != null && config.enableMetalRendering && isSodiumLoaded();
  }

  /**
   * Whether the chunk-render path is live. Mixins guard their bodies on this.
   */
  public static boolean isEnabled() {
    return shouldInit() && handle != 0L;
  }

  public static long getHandle() {
    return handle;
  }

  public static synchronized boolean tryInit(int width, int height, float scale) {
    if (handle != 0L || !shouldInit()) return handle != 0L;
    try {
      long h = NativeBridge.nInit(width, height, scale);
      if (h != 0L) {
        handle = h;
        MetalLogger.info("MetalRender native initialized (%dx%d @ %.2f).", width, height, scale);
        return true;
      } else {
        MetalLogger.warn("nInit returned 0; Metal device unavailable.");
      }
    } catch (Throwable t) {
      MetalLogger.warn("nInit threw: %s", t.toString());
    }
    return false;
  }

  public static synchronized void destroy() {
    if (handle == 0L) return;
    try {
      com.pebbles_boon.metalrender.sodium.MetalCompositor.destroy();
    } catch (Throwable t) {
      MetalLogger.warn("MetalCompositor.destroy threw: %s", t.toString());
    }
    try {
      com.pebbles_boon.metalrender.sodium.MetalQuadIndexBuffer.destroy();
    } catch (Throwable t) {
      MetalLogger.warn("MetalQuadIndexBuffer.destroy threw: %s", t.toString());
    }
    try {
      NativeBridge.nDestroy(handle);
    } catch (Throwable t) {
      MetalLogger.warn("nDestroy threw: %s", t.toString());
    }
    handle = 0L;
  }

  public static boolean isSodiumLoaded() {
    try {
      return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
    } catch (Throwable t) {
      return false;
    }
  }
}
