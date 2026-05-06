package com.pebbles_boon.metalrender.sodium;

/**
 * Per-section, per-pass mesh handles tracked on the Metal side. Sodium owns
 * visibility; we own the bytes the GPU draws from.
 *
 * <p>{@code vbo} and {@code ibo} are Metal buffer handles obtained from
 * {@code nUploadChunkMesh} and {@code nCreateBuffer} respectively. Both are
 * freed via {@code nDestroyBuffer} when the section is invalidated or the
 * renderer torn down.
 */
final class MetalSectionMesh {
  final long vbo;
  final long ibo;
  final int indexOffset;
  final int indexCount;

  MetalSectionMesh(long vbo, long ibo, int indexOffset, int indexCount) {
    this.vbo = vbo;
    this.ibo = ibo;
    this.indexOffset = indexOffset;
    this.indexCount = indexCount;
  }
}
