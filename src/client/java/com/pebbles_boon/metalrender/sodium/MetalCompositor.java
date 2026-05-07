package com.pebbles_boon.metalrender.sodium;

import com.mojang.blaze3d.platform.GlStateManager;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Composites the IOSurface-backed Metal output (color + depth) onto
 * Minecraft's main framebuffer with a fullscreen triangle and a small shader
 * sampling via {@code sampler2DRect} for both color and depth.
 *
 * <p>Stage-2 mode (called via {@link #flushAndBlit}): committed mid-frame after
 * Sodium's CUTOUT pass — Metal terrain depth is written into MC's depth buffer
 * via {@code gl_FragDepth} so subsequent vanilla GL passes (entities, particles)
 * z-test correctly against Metal-rendered terrain. A second flush+blit happens
 * after TRANSLUCENT to layer water/glass on top.
 *
 * <p>Stage-1 mode (called via {@link #blitOverlay}): end-of-frame overlay
 * without depth, kept for diagnostic A/B testing.
 */
public final class MetalCompositor {
  private static volatile MetalCompositor instance;

  // GL resource handles (0 = unallocated)
  private int glRectTexture = 0;       // color rectangle (BGRA from g_ioSurface)
  private int glDepthRectTexture = 0;  // depth rectangle (R32F from g_depthMirrorIOSurface)
  private int program = 0;
  private int vao = 0;
  private int vbo = 0;

  // Cached GL locations
  private int aPosLoc = -1;
  private int uTextureLoc = -1;
  private int uDepthLoc = -1;
  private int uViewportLoc = -1;
  private int uWriteDepthLoc = -1;
  private int uPreserveAlphaLoc = -1;

  // Last bind dimensions (rebind when these change)
  private int boundWidth = 0;
  private int boundHeight = 0;
  private boolean depthBound = false;

  // Diagnostic counters
  private int blitFrames = 0;

  private MetalCompositor() {}

  public static MetalCompositor get() {
    MetalCompositor inst = instance;
    if (inst != null) return inst;
    synchronized (MetalCompositor.class) {
      if (instance == null) {
        instance = new MetalCompositor();
      }
      return instance;
    }
  }

  private static final String VERTEX_SHADER =
      "#version 150 core\n" +
      "in vec2 aPos;\n" +
      "out vec2 vTexCoord;\n" +
      "uniform vec2 uViewport;\n" +
      "void main() {\n" +
      "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
      // Metal writes textures top-down; GL samples bottom-up. Flip V.
      "  vTexCoord = vec2((aPos.x * 0.5 + 0.5) * uViewport.x,\n" +
      "                   (1.0 - (aPos.y * 0.5 + 0.5)) * uViewport.y);\n" +
      "}\n";

  private static final String FRAGMENT_SHADER =
      "#version 150 core\n" +
      "in vec2 vTexCoord;\n" +
      "out vec4 fragColor;\n" +
      "uniform sampler2DRect uTexture;\n" +
      "uniform sampler2DRect uDepth;\n" +
      "uniform int uWriteDepth;\n" +
      "uniform int uPreserveAlpha;\n" +
      "void main() {\n" +
      "  vec4 c = texture(uTexture, vTexCoord);\n" +
      // Discard transparent pixels so vanilla GL output (sky, gaps) shows
      // through — and crucially also so we don't write depth where Metal
      // didn't render any chunk pixel.
      "  if (c.a < 0.001) discard;\n" +
      "  fragColor = (uPreserveAlpha != 0) ? c : vec4(c.rgb, 1.0);\n" +
      "  if (uWriteDepth != 0) {\n" +
      "    gl_FragDepth = texture(uDepth, vTexCoord).r;\n" +
      "  } else {\n" +
      "    gl_FragDepth = gl_FragCoord.z;\n" +
      "  }\n" +
      "}\n";

  private boolean ensureInit() {
    if (program != 0) return true;
    int vs = compileShader(GL20.GL_VERTEX_SHADER, VERTEX_SHADER, "vertex");
    if (vs == 0) return false;
    int fs = compileShader(GL20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER, "fragment");
    if (fs == 0) {
      GL20.glDeleteShader(vs);
      return false;
    }
    int prog = GL20.glCreateProgram();
    GL20.glAttachShader(prog, vs);
    GL20.glAttachShader(prog, fs);
    GL20.glLinkProgram(prog);
    GL20.glDeleteShader(vs);
    GL20.glDeleteShader(fs);
    if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
      MetalLogger.error("MetalCompositor: program link failed: %s",
          GL20.glGetProgramInfoLog(prog));
      GL20.glDeleteProgram(prog);
      return false;
    }
    aPosLoc = GL20.glGetAttribLocation(prog, "aPos");
    uTextureLoc = GL20.glGetUniformLocation(prog, "uTexture");
    uDepthLoc = GL20.glGetUniformLocation(prog, "uDepth");
    uViewportLoc = GL20.glGetUniformLocation(prog, "uViewport");
    uWriteDepthLoc = GL20.glGetUniformLocation(prog, "uWriteDepth");
    uPreserveAlphaLoc = GL20.glGetUniformLocation(prog, "uPreserveAlpha");

    ByteBuffer verts = MemoryUtil.memAlloc(3 * 2 * 4).order(ByteOrder.nativeOrder());
    try {
      verts.putFloat(-1.0f).putFloat(-1.0f);
      verts.putFloat( 3.0f).putFloat(-1.0f);
      verts.putFloat(-1.0f).putFloat( 3.0f);
      verts.flip();

      vao = GL30.glGenVertexArrays();
      vbo = GL15.glGenBuffers();
      GL30.glBindVertexArray(vao);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
      GL15.glBufferData(GL15.GL_ARRAY_BUFFER, verts, GL15.GL_STATIC_DRAW);
      GL20.glEnableVertexAttribArray(aPosLoc);
      GL20.glVertexAttribPointer(aPosLoc, 2, GL11.GL_FLOAT, false, 8, 0L);
      GL30.glBindVertexArray(0);
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    } finally {
      MemoryUtil.memFree(verts);
    }

    glRectTexture = GL11.glGenTextures();
    initRectTexture(glRectTexture);
    glDepthRectTexture = GL11.glGenTextures();
    initRectTexture(glDepthRectTexture);

    program = prog;
    MetalLogger.info("MetalCompositor: GL program ready (program=%d vao=%d vbo=%d color=%d depth=%d)",
        program, vao, vbo, glRectTexture, glDepthRectTexture);
    return true;
  }

  private static void initRectTexture(int tex) {
    GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, tex);
    // NEAREST is correct for both color and depth: the fullscreen-triangle
    // vertex shader produces vTexCoord values exactly at texel centers, so
    // bilinear filtering would only blend across edges. For the depth
    // texture in particular, LINEAR produces invalid intermediate depth
    // values where terrain meets sky.
    GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
    GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
    GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
    GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, 0);
  }

  private static int compileShader(int type, String source, String label) {
    int id = GL20.glCreateShader(type);
    GL20.glShaderSource(id, source);
    GL20.glCompileShader(id);
    if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
      MetalLogger.error("MetalCompositor: %s shader compile failed: %s",
          label, GL20.glGetShaderInfoLog(id));
      GL20.glDeleteShader(id);
      return 0;
    }
    return id;
  }

  private boolean ensureBound(boolean needDepth) {
    long handle = MetalRenderClient.getHandle();
    if (handle == 0L) return false;
    int wantW = NativeBridge.nGetIOSurfaceWidth(handle);
    int wantH = NativeBridge.nGetIOSurfaceHeight(handle);
    if (wantW <= 0 || wantH <= 0) return false;
    boolean dimsChanged = (wantW != boundWidth || wantH != boundHeight);
    if (dimsChanged || glRectTexture == 0) {
      if (!NativeBridge.nBindIOSurfaceToTexture(handle, glRectTexture)) {
        MetalLogger.warn("MetalCompositor: nBindIOSurfaceToTexture failed (tex=%d %dx%d)",
            glRectTexture, wantW, wantH);
        return false;
      }
    }
    if (needDepth && (dimsChanged || !depthBound)) {
      if (NativeBridge.nBindDepthIOSurfaceToTexture(handle, glDepthRectTexture)) {
        depthBound = true;
        MetalLogger.info("MetalCompositor: bound depth IOSurface to GL texture (%dx%d)",
            wantW, wantH);
      } else {
        depthBound = false;
        MetalLogger.warn("MetalCompositor: nBindDepthIOSurfaceToTexture failed (tex=%d)",
            glDepthRectTexture);
      }
    }
    if (dimsChanged) {
      boundWidth = wantW;
      boundHeight = wantH;
      MetalLogger.info("MetalCompositor: bound color IOSurface to GL texture (%dx%d)",
          wantW, wantH);
    }
    return true;
  }

  /**
   * Stage-1 entry point: end-of-frame overlay blit, no depth write. Kept for
   * the diagnostic flag path (compositor.blitOverlay).
   */
  public void blitOverlay(Framebuffer mainFb) {
    blit(mainFb, /*writeDepth=*/false, /*flushFirst=*/true,
        /*alphaBlend=*/true);
  }

  /**
   * Stage-2 entry point: mid-frame Metal commit + composite blit with depth.
   * Called from {@code MetalChunkRenderer.render} after the CUTOUT pass.
   * Overwrites color and depth at every chunk pixel — vanilla GL passes that
   * follow (entities, particles) z-test against the populated depth buffer.
   */
  public void flushAndBlit(Framebuffer mainFb) {
    blit(mainFb, /*writeDepth=*/true, /*flushFirst=*/true,
        /*alphaBlend=*/false);
  }

  /**
   * Stage-2 translucent variant: alpha-blends the Metal output (water, glass)
   * over MC's framebuffer without overwriting depth. Cutout depth in the GL
   * depth buffer is preserved so particles z-test against opaque terrain, not
   * against translucent geometry.
   */
  public void flushAndBlitTranslucent(Framebuffer mainFb) {
    blit(mainFb, /*writeDepth=*/false, /*flushFirst=*/true,
        /*alphaBlend=*/true);
  }

  private void blit(Framebuffer mainFb, boolean writeDepth, boolean flushFirst,
                    boolean alphaBlend) {
    if (mainFb == null) return;
    long handle = MetalRenderClient.getHandle();
    if (handle == 0L) return;

    if (flushFirst) {
      // Commits the current Metal command buffer so the IOSurface is up-to-date.
      // For stage-1 (end-of-frame), nWaitForRender alone would suffice — but
      // calling nFlushChunkPasses additionally is harmless when there's nothing
      // to flush (it returns early if no encoder is active).
      NativeBridge.nFlushChunkPasses(handle);
    }
    NativeBridge.nWaitForRender(handle);

    if (!ensureInit()) return;
    if (!ensureBound(writeDepth)) return;
    if (writeDepth && !depthBound) {
      // Depth couldn't be bound; degrade gracefully to color-only blit.
      writeDepth = false;
    }

    int fbWidth = mainFb.textureWidth;
    int fbHeight = mainFb.textureHeight;
    if (fbWidth <= 0 || fbHeight <= 0) return;

    // Save GL state. Use GlStateManager for active-texture switches so MC's
    // cached state stays consistent with the hardware — otherwise later
    // GlStateManager._activeTexture calls in the blit body can become no-ops
    // (cache says we're already on the requested slot) while the hardware is
    // actually on a different slot, and bindings end up on the wrong unit.
    int savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    int savedActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    GlStateManager._activeTexture(GL13.GL_TEXTURE0);
    int savedTex0 = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_RECTANGLE);
    GlStateManager._activeTexture(GL13.GL_TEXTURE1);
    int savedTex1 = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_RECTANGLE);
    int savedDrawFb = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    boolean savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    boolean savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
    boolean savedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    boolean savedScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    boolean savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    int savedDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    int[] savedViewport = new int[4];
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
    int savedBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    int savedBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    int savedBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    int savedBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

    try {
      mainFb.beginWrite(false);
      // Route every state change through GlStateManager so MC's cached
      // GL state matches the hardware. Bypassing GlStateManager (with raw
      // GL11.* calls) leaves its cache stale, causing later RenderSystem
      // calls in vanilla code (e.g. depthMask(false) before particles) to
      // be no-ops because the cache thinks the value is already correct.
      GlStateManager._viewport(0, 0, fbWidth, fbHeight);
      GlStateManager._disableCull();
      GlStateManager._disableScissorTest();
      if (writeDepth) {
        // Want to overwrite MC depth where Metal terrain was rendered.
        // GL_ALWAYS makes the depth test always pass; depth write is on so
        // gl_FragDepth gets stored.
        GlStateManager._enableDepthTest();
        GlStateManager._depthFunc(GL11.GL_ALWAYS);
        GlStateManager._depthMask(true);
      } else {
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
      }
      if (alphaBlend) {
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                                          GL11.GL_ONE,        GL11.GL_ONE_MINUS_SRC_ALPHA);
      } else {
        // Chunks fully overwrite vanilla output where they exist.
        GlStateManager._disableBlend();
      }

      GlStateManager._glUseProgram(program);
      GlStateManager._activeTexture(GL13.GL_TEXTURE0);
      // GlStateManager has no rect-texture binding tracker — it tracks
      // GL_TEXTURE_2D only. Use raw GL for these (won't cache-poison since
      // they're a different texture target).
      GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, glRectTexture);
      GL20.glUniform1i(uTextureLoc, 0);
      if (writeDepth) {
        GlStateManager._activeTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, glDepthRectTexture);
        GL20.glUniform1i(uDepthLoc, 1);
      }
      GL20.glUniform2f(uViewportLoc, (float) boundWidth, (float) boundHeight);
      GL20.glUniform1i(uWriteDepthLoc, writeDepth ? 1 : 0);
      GL20.glUniform1i(uPreserveAlphaLoc, alphaBlend ? 1 : 0);

      GL30.glBindVertexArray(vao);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
      // The IOSurface bound to glRectTexture / glDepthRectTexture is shared
      // with Metal. Without a barrier, Metal's next render pass (which clears
      // the IOSurface for translucent rendering) can be queued ahead of GL's
      // sampling read on the GPU — making the blit sample an already-cleared
      // IOSurface and discard everything. A fence sync is much cheaper than
      // glFinish but provides the same ordering guarantee for the IOSurface
      // read.
      long fence = org.lwjgl.opengl.GL32.glFenceSync(
          org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
      if (fence != 0L) {
        org.lwjgl.opengl.GL32.glClientWaitSync(
            fence,
            org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT,
            1_000_000_000L);  // 1 second timeout — should never hit it
        org.lwjgl.opengl.GL32.glDeleteSync(fence);
      }
    } finally {
      GL30.glBindVertexArray(savedVao);
      GlStateManager._activeTexture(GL13.GL_TEXTURE1);
      GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, savedTex1);
      GlStateManager._activeTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, savedTex0);
      GlStateManager._activeTexture(savedActiveTex);
      GlStateManager._glUseProgram(savedProgram);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDrawFb);
      GlStateManager._viewport(savedViewport[0], savedViewport[1],
                               savedViewport[2], savedViewport[3]);
      setEnabled(GL11.GL_DEPTH_TEST, savedDepthTest);
      setEnabled(GL11.GL_BLEND, savedBlend);
      setEnabled(GL11.GL_CULL_FACE, savedCullFace);
      setEnabled(GL11.GL_SCISSOR_TEST, savedScissor);
      GlStateManager._depthMask(savedDepthMask);
      GlStateManager._depthFunc(savedDepthFunc);
      GlStateManager._blendFuncSeparate(savedBlendSrcRgb, savedBlendDstRgb,
                                        savedBlendSrcAlpha, savedBlendDstAlpha);
    }

    blitFrames++;
    if (blitFrames <= 3 || (blitFrames % 600) == 0) {
      MetalLogger.info("MetalCompositor: blit frame %d (fb=%dx%d, src=%dx%d, depth=%s)",
          blitFrames, fbWidth, fbHeight, boundWidth, boundHeight, writeDepth);
    }
  }

  private static void setEnabled(int cap, boolean enabled) {
    // GlStateManager tracks each capability individually; route through it
    // so MC's cached state matches hardware.
    if (cap == GL11.GL_DEPTH_TEST) {
      if (enabled) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
    } else if (cap == GL11.GL_BLEND) {
      if (enabled) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
    } else if (cap == GL11.GL_CULL_FACE) {
      if (enabled) GlStateManager._enableCull(); else GlStateManager._disableCull();
    } else if (cap == GL11.GL_SCISSOR_TEST) {
      if (enabled) GlStateManager._enableScissorTest(); else GlStateManager._disableScissorTest();
    } else {
      if (enabled) GL11.glEnable(cap); else GL11.glDisable(cap);
    }
  }

  public static void destroy() {
    MetalCompositor inst = instance;
    if (inst == null) return;
    synchronized (MetalCompositor.class) {
      if (instance == null) return;
      if (inst.program != 0) GL20.glDeleteProgram(inst.program);
      if (inst.vao != 0) GL30.glDeleteVertexArrays(inst.vao);
      if (inst.vbo != 0) GL15.glDeleteBuffers(inst.vbo);
      if (inst.glRectTexture != 0) GL11.glDeleteTextures(inst.glRectTexture);
      if (inst.glDepthRectTexture != 0) GL11.glDeleteTextures(inst.glDepthRectTexture);
      inst.program = inst.vao = inst.vbo = 0;
      inst.glRectTexture = inst.glDepthRectTexture = 0;
      inst.boundWidth = inst.boundHeight = 0;
      inst.depthBound = false;
      instance = null;
    }
  }
}
