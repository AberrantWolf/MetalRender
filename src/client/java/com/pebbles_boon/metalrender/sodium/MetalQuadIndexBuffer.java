package com.pebbles_boon.metalrender.sodium;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lazily-allocated shared quad index buffer (uint32). Sodium's per-section
 * vertex buffers store geometry in quad order (4 vertices per quad); this
 * buffer translates that to triangles via the standard 0-1-2-2-3-0 pattern.
 *
 * <p>Sized for {@link #MAX_QUADS}; one section's draw uses
 * {@code indexCount = vertexCount / 4 * 6} starting at index offset 0.
 */
public final class MetalQuadIndexBuffer {
  private static final int MAX_QUADS = 65536;
  private static volatile long handle = 0L;

  private MetalQuadIndexBuffer() {}

  public static long getOrCreate() {
    long h = handle;
    if (h != 0L) return h;
    synchronized (MetalQuadIndexBuffer.class) {
      if (handle != 0L) return handle;
      int indexCount = MAX_QUADS * 6;
      int sizeBytes = indexCount * 4;
      ByteBuffer buf = MemoryUtil.memAlloc(sizeBytes).order(ByteOrder.nativeOrder());
      try {
        for (int q = 0; q < MAX_QUADS; q++) {
          int base = q * 4;
          buf.putInt(base);
          buf.putInt(base + 1);
          buf.putInt(base + 2);
          buf.putInt(base + 2);
          buf.putInt(base + 3);
          buf.putInt(base);
        }
        buf.flip();
        long created = NativeBridge.nCreateBuffer(MetalRenderClient.getHandle(), sizeBytes, 0);
        if (created == 0L) {
          MetalLogger.warn("Failed to allocate shared quad index buffer (%d bytes)", sizeBytes);
          return 0L;
        }
        NativeBridge.nUploadBufferDataDirect(created, buf, 0, sizeBytes);
        handle = created;
        MetalLogger.info("Shared quad index buffer ready (%d quads, %d bytes)", MAX_QUADS, sizeBytes);
        return created;
      } finally {
        MemoryUtil.memFree(buf);
      }
    }
  }

  public static void destroy() {
    long h;
    synchronized (MetalQuadIndexBuffer.class) {
      h = handle;
      handle = 0L;
    }
    if (h != 0L) {
      NativeBridge.nDestroyBuffer(h);
    }
  }
}
