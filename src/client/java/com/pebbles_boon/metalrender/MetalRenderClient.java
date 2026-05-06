package com.pebbles_boon.metalrender;

import com.pebbles_boon.metalrender.config.MetalRenderConfig;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;

public class MetalRenderClient implements ClientModInitializer {
  private static MetalRenderConfig config;
  private static boolean metalAvailable = false;

  @Override
  public void onInitializeClient() {
    MetalLogger.info("MetalRender 1.20.1 stub backport initializing — renderer hooks are disabled in this build.");

    config = MetalRenderConfig.load();
    if (!config.enableMetalRendering) {
      MetalLogger.info("Metal rendering disabled via config; stub will not probe hardware.");
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
        MetalLogger.info("Metal device available: %s (stub build, no rendering hooks active)",
            MetalHardwareChecker.getDeviceName());
      } else {
        MetalLogger.info("Metal device probe returned unsupported; stub remains inert.");
      }
    } catch (Throwable t) {
      MetalLogger.warn("Metal hardware probe failed: %s", t.toString());
      metalAvailable = false;
    }
  }

  public static MetalRenderConfig getConfig() {
    return config;
  }

  public static boolean isMetalAvailable() {
    return metalAvailable;
  }

  public static boolean isEnabled() {
    return false;
  }

  public static boolean isSodiumLoaded() {
    try {
      return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
    } catch (Throwable t) {
      return false;
    }
  }
}
