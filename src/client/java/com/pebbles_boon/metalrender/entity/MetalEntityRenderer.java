package com.pebbles_boon.metalrender.entity;

import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.entity.Entity;

/**
 * Foundational scaffolding for Metal-side entity rendering on Sodium 0.5 /
 * 1.20.1. Currently observation-only — the real geometry mirroring and Metal
 * draw dispatch are unimplemented and will land in follow-up work.
 *
 * <h2>Why this is a skeleton, not a full implementation</h2>
 *
 * <p>The legacy 1.21.11 entity renderer
 * ({@code src/legacy_1_21_11/.../entity/MetalEntityRenderer.java}) leaned on
 * 1.21.x APIs that don't exist in 1.20.1:
 *
 * <ul>
 *   <li>{@code EntityRenderState} / {@code LivingEntityRenderState} — the
 *       per-entity render-state objects collected via
 *       {@code fillEntityRenderStates} are 1.21.x-only.</li>
 *   <li>{@code LivingEntityRenderer.getAndUpdateRenderState} — same.</li>
 * </ul>
 *
 * <p>1.20.1 entity rendering is per-entity, immediate-mode: MC iterates
 * visible entities in {@code WorldRenderer.render} and calls
 * {@code WorldRenderer.renderEntity(entity, ..., VertexConsumerProvider)}
 * for each one. The renderer for that entity type writes vertex data into
 * the {@code VertexConsumerProvider} (an {@code Immediate} backed by
 * {@code BufferBuilderStorage}); MC drains those buffers to GL after the
 * loop.
 *
 * <h2>Implementation plan when this is picked up</h2>
 *
 * <ol>
 *   <li><b>Capture path:</b> wrap {@code VertexConsumerProvider.Immediate}
 *       so each {@code getBuffer(RenderLayer)} returns a
 *       {@code MetalVertexConsumer} that mirrors per-quad attribute writes
 *       (pos, color, uv, light, overlay, normal) into a Metal-mapped
 *       staging buffer. Indexed by {@code RenderLayer} → {@code (vbo, vertex
 *       range)}.</li>
 *   <li><b>RenderLayer → MTL pipeline mapping:</b> map each MC
 *       {@code RenderLayer} to one of the existing entity pipelines
 *       ({@code nGetEntityPipelineHandle},
 *       {@code nGetEntityTranslucentPipelineHandle},
 *       {@code nGetEntityEmissivePipelineHandle}) based on its blend /
 *       depth / cutout state. RenderLayers MC adds at runtime
 *       (mob-specific, hurt-flash overlays) need a registry-side resolver.</li>
 *   <li><b>Texture binding:</b> entity textures are GL-side
 *       {@code AbstractTexture}; mirror via
 *       {@code MetalTextureBridge}-style readback once per frame per unique
 *       texture. {@code nBindEntityTexture(frameContext, mtlTextureHandle)}
 *       already exists for the per-draw bind.</li>
 *   <li><b>Per-entity draws:</b> after the entity loop, walk the captured
 *       per-RenderLayer buffers and issue
 *       {@code nDrawEntityBuffer} / {@code nDrawEntityBufferIndexed} calls.
 *       Hurt-flash overlay state goes through
 *       {@code nSetEntityOverlay(hurtTime, whiteFlash, alpha)} before each
 *       affected draw.</li>
 *   <li><b>Skip MC's GL draw:</b> mixin
 *       {@code BufferBuilderStorage.getEntityVertexConsumers} or the
 *       drain-to-GL step so the same vertex data isn't drawn twice.</li>
 * </ol>
 *
 * <h2>Today's behavior</h2>
 *
 * <p>{@link #captureEntity(Entity, double, double, double, float)} only
 * increments observation counters. MC's GL entity rendering is unaffected.
 */
public final class MetalEntityRenderer {
  private static final MetalEntityRenderer INSTANCE = new MetalEntityRenderer();

  // Observation counters — reset and logged at flush time.
  private long observedThisFrame = 0;
  private long observedTotal = 0;
  private int frameCount = 0;
  private static final int LOG_FRAME_INTERVAL = 600; // ~10s @ 60fps

  private MetalEntityRenderer() {}

  public static MetalEntityRenderer get() {
    return INSTANCE;
  }

  /**
   * Called per-entity from {@code WorldRendererEntityMixin}. Currently just
   * counts; future implementations will queue the entity for Metal draw.
   *
   * <p>Coordinates are world-space camera coords (mirrors MC's signature so
   * the mixin can pass them through unchanged).
   */
  public void captureEntity(Entity entity, double cameraX, double cameraY,
                            double cameraZ, float tickDelta) {
    if (entity == null) return;
    observedThisFrame++;
  }

  /** Called once per frame at WorldRenderer.render TAIL to flush state. */
  public void flushFrame() {
    observedTotal += observedThisFrame;
    frameCount++;
    if (frameCount >= LOG_FRAME_INTERVAL) {
      MetalLogger.info("Entity capture observer: %d entities/frame avg over %d frames (total=%d)",
          observedTotal / Math.max(1, frameCount), frameCount, observedTotal);
      frameCount = 0;
      observedThisFrame = 0;
      observedTotal = 0;
    } else {
      observedThisFrame = 0;
    }
  }
}
