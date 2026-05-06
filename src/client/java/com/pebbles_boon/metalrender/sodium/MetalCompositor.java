package com.pebbles_boon.metalrender.sodium;

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
 * Stage-1 diagnostic compositor. Blits the IOSurface-backed Metal output onto
 * Minecraft's main framebuffer with a fullscreen triangle and a tiny shader
 * sampling via {@code sampler2DRect}.
 *
 * <p>Sodium GL chunk render still runs; this overlay is layered on top with
 * standard alpha blend. Identical-on-identical-scene confirms the Metal
 * pipeline is producing valid pixels. Stages CB/CC will replace, not overlay.
 *
 * <p>Native side wiring is already in place:
 * <ul>
 * <li>{@code g_color} is an IOSurface-backed Metal texture
 *     ({@code metalrender.mm:891})</li>
 * <li>{@code nBindIOSurfaceToTexture} performs
 *     {@code CGLTexImageIOSurface2D} into a GL_TEXTURE_RECTANGLE_ARB target
 *     ({@code metalrender.mm:2922})</li>
 * <li>{@code nWaitForRender} blocks until Metal frame is committed
 *     ({@code metalrender.mm:2912})</li>
 * </ul>
 */
public final class MetalCompositor {
  private static volatile MetalCompositor instance;

  // GL resource handles (0 = unallocated)
  private int glRectTexture = 0;
  private int program = 0;
  private int vao = 0;
  private int vbo = 0;

  // Cached GL locations
  private int aPosLoc = -1;
  private int uTextureLoc = -1;
  private int uViewportLoc = -1;

  // Last bind dimensions (rebind when these change)
  private int boundWidth = 0;
  private int boundHeight = 0;

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
      // Metal writes textures top-down; GL samples bottom-up. Flip V so the
      // composite is right-side up. X is unaffected.
      "  vTexCoord = vec2((aPos.x * 0.5 + 0.5) * uViewport.x,\n" +
      "                   (1.0 - (aPos.y * 0.5 + 0.5)) * uViewport.y);\n" +
      "}\n";

  private static final String FRAGMENT_SHADER =
      "#version 150 core\n" +
      "in vec2 vTexCoord;\n" +
      "out vec4 fragColor;\n" +
      "uniform sampler2DRect uTexture;\n" +
      "void main() {\n" +
      "  vec4 c = texture(uTexture, vTexCoord);\n" +
      "  if (c.a < 0.001) discard;\n" +
      "  fragColor = vec4(c.rgb, 1.0);\n" +
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
    uViewportLoc = GL20.glGetUniformLocation(prog, "uViewport");

    // Fullscreen triangle: clip-space verts (-1,-1), (3,-1), (-1,3) — covers the
    // viewport with one triangle (the triangle extends beyond NDC bounds; the
    // GPU clips, no fragment is wasted).
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
    GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, glRectTexture);
    GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
    GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
    GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
    GL11.glTexParameteri(GL31.GL_TEXTURE_RECTANGLE, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
    GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, 0);

    program = prog;
    MetalLogger.info("MetalCompositor: GL program ready (program=%d vao=%d vbo=%d tex=%d)",
        program, vao, vbo, glRectTexture);
    return true;
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

  private boolean ensureBound() {
    long handle = MetalRenderClient.getHandle();
    if (handle == 0L) return false;
    int wantW = NativeBridge.nGetIOSurfaceWidth(handle);
    int wantH = NativeBridge.nGetIOSurfaceHeight(handle);
    if (wantW <= 0 || wantH <= 0) return false;
    if (wantW == boundWidth && wantH == boundHeight && glRectTexture != 0) return true;
    boolean ok = NativeBridge.nBindIOSurfaceToTexture(handle, glRectTexture);
    if (!ok) {
      MetalLogger.warn("MetalCompositor: nBindIOSurfaceToTexture failed (tex=%d %dx%d)",
          glRectTexture, wantW, wantH);
      return false;
    }
    boundWidth = wantW;
    boundHeight = wantH;
    MetalLogger.info("MetalCompositor: bound IOSurface to GL texture (%dx%d)", wantW, wantH);
    return true;
  }

  /**
   * Per-frame entry point. Called from {@code WorldRendererBlitMixin}
   * {@code @At("RETURN")}. Blits the Metal output as an alpha-blended overlay
   * onto the supplied framebuffer (typically MC's main framebuffer).
   */
  public void blitOverlay(Framebuffer mainFb) {
    if (mainFb == null) return;
    long handle = MetalRenderClient.getHandle();
    if (handle == 0L) return;

    // Wait for Metal frame to be committed and visible in IOSurface memory.
    NativeBridge.nWaitForRender(handle);

    if (!ensureInit()) return;
    if (!ensureBound()) return;

    int fbWidth = mainFb.textureWidth;
    int fbHeight = mainFb.textureHeight;
    if (fbWidth <= 0 || fbHeight <= 0) return;

    // Save GL state
    int savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    int savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    int savedActiveTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    int savedTexBinding;
    GL13.glActiveTexture(GL13.GL_TEXTURE0);
    savedTexBinding = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_RECTANGLE);
    int savedDrawFb = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    boolean savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    boolean savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
    boolean savedCullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    boolean savedScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    boolean savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    int[] savedViewport = new int[4];
    GL11.glGetIntegerv(GL11.GL_VIEWPORT, savedViewport);
    int savedBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    int savedBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    int savedBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    int savedBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

    try {
      mainFb.beginWrite(false);
      GL11.glViewport(0, 0, fbWidth, fbHeight);
      GL11.glDisable(GL11.GL_DEPTH_TEST);
      GL11.glDepthMask(false);
      GL11.glDisable(GL11.GL_CULL_FACE);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glEnable(GL11.GL_BLEND);
      GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                               GL11.GL_ONE,        GL11.GL_ONE_MINUS_SRC_ALPHA);

      GL20.glUseProgram(program);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, glRectTexture);
      GL20.glUniform1i(uTextureLoc, 0);
      GL20.glUniform2f(uViewportLoc, (float) boundWidth, (float) boundHeight);

      GL30.glBindVertexArray(vao);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    } finally {
      // Restore in reverse-ish order (bindings/state)
      GL30.glBindVertexArray(savedVao);
      GL11.glBindTexture(GL31.GL_TEXTURE_RECTANGLE, savedTexBinding);
      GL13.glActiveTexture(savedActiveTex);
      GL20.glUseProgram(savedProgram);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, savedDrawFb);
      GL11.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
      setEnabled(GL11.GL_DEPTH_TEST, savedDepthTest);
      setEnabled(GL11.GL_BLEND, savedBlend);
      setEnabled(GL11.GL_CULL_FACE, savedCullFace);
      setEnabled(GL11.GL_SCISSOR_TEST, savedScissor);
      GL11.glDepthMask(savedDepthMask);
      GL14.glBlendFuncSeparate(savedBlendSrcRgb, savedBlendDstRgb,
                               savedBlendSrcAlpha, savedBlendDstAlpha);
    }

    blitFrames++;
    if (blitFrames <= 3 || (blitFrames % 600) == 0) {
      MetalLogger.info("MetalCompositor: blit frame %d (fb=%dx%d, src=%dx%d)",
          blitFrames, fbWidth, fbHeight, boundWidth, boundHeight);
    }
  }

  private static void setEnabled(int cap, boolean enabled) {
    if (enabled) GL11.glEnable(cap); else GL11.glDisable(cap);
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
      inst.program = inst.vao = inst.vbo = inst.glRectTexture = 0;
      inst.boundWidth = inst.boundHeight = 0;
      instance = null;
    }
  }
}
