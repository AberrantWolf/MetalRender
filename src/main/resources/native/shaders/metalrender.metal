#include <metal_stdlib>
using namespace metal;

// Sodium 0.5.11 CompactChunkVertex layout (verified against upstream tag mc1.20.1-0.5.11):
//   bytes  0..5  : 3x ushort  position (x, y, z)   — encoded as (8.0 + v) * 2048.0
//   byte      6  : uchar       material bits        — material.bits() & 0xFF
//   byte      7  : uchar       section index
//   bytes  8..11 : uint        color (ABGR int)     — ColorABGR.withAlpha(rgb, faceShade)
//                              little-endian byte order: [R, G, B, A]
//   bytes 12..15 : 2x ushort   texture (u, v)       — round(uv * 32768) & 0xFFFF
//   bytes 16..19 : uint        light (MC lightmap)  — (skyLight << 20) | (blockLight << 4)
//                              as bytes: [blockLight*16, 0, skyLight*16, 0]
struct SodiumVertex {
    packed_ushort3 pos;
    uchar materialBits;
    uchar sectionIndex;
    uint color;
    packed_ushort2 texture;
    uint lightData;
};

float3 decodeSodiumPosition(packed_ushort3 pos) {
    // Inverse of encoder: v = pos / 2048.0 - 8.0
    return float3(ushort3(pos)) / 2048.0 - 8.0;
}

float4 decodeSodiumColor(uint c) {
    // ABGR: alpha is high byte, red is low byte
    float r = float((c >>  0) & 0xFF) / 255.0;
    float g = float((c >>  8) & 0xFF) / 255.0;
    float b = float((c >> 16) & 0xFF) / 255.0;
    float a = float((c >> 24) & 0xFF) / 255.0;
    return float4(r, g, b, a);
}

float2 decodeSodiumTexCoord(packed_ushort2 tex) {
    // Encoder produces values in [0, 32768] for UV in [0, 1]
    return float2(ushort2(tex)) / 32768.0;
}

float2 decodeSodiumLight(uint lightData) {
    // MC packs as (skyLight << 20) | (blockLight << 4); read as 2 shorts:
    //   low short  = blockLight << 4   (low byte = blockLight*16, high byte = 0)
    //   high short = skyLight  << 4    (low byte = skyLight*16,  high byte = 0)
    // Lightmap UV samples cell center: (value*16 + 8) / 256 = (value + 0.5) / 16
    float blockLight = (float(lightData & 0xFFu) + 8.0) / 256.0;
    float skyLight   = (float((lightData >> 16) & 0xFFu) + 8.0) / 256.0;
    return float2(blockLight, skyLight);
}

constant half kFaceShade[6] = {
    half(0.65),
    half(1.0),
    half(0.8),
    half(0.8),
    half(0.65),
    half(0.65),
};

struct SimpleVertexOut {
    float4 position [[position]];
    float2 texCoord;
    half4  color;
    float2 lightUV;
    half  light;
    uint   normalIndex [[flat]];
};

static inline half3 applyUnderwaterFog(half3 rgb, half fogDist, constant float4& overlayParams) {
    float waterFog = overlayParams.z;
    if (waterFog <= 0.0f) {
        return rgb;
    }
    half fogFactor = half(clamp(float(fogDist) / 48.0f, 0.0f, 0.85f) * waterFog);
    return mix(rgb, half3(0.05h, 0.12h, 0.30h), fogFactor);
}

vertex SimpleVertexOut vertex_terrain(
    device const SodiumVertex* vertices       [[buffer(0)]],
    constant float4x4& projectionMatrix       [[buffer(1)]],
    constant float4x4& modelViewMatrix        [[buffer(2)]],
    constant float4& cameraPosition           [[buffer(3)]],
    constant float4& chunkOffset              [[buffer(4)]],
    uint vid [[vertex_id]]
) {
    SodiumVertex v = vertices[vid];
    SimpleVertexOut out;
    float3 localPos = decodeSodiumPosition(v.pos);
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = decodeSodiumTexCoord(v.texture);
    out.color    = half4(decodeSodiumColor(v.color));
    out.lightUV  = decodeSodiumLight(v.lightData);
    out.light    = half(max(max(out.lightUV.x,
                                out.lightUV.y * cameraPosition.w), 0.15f));
    // Sodium bakes face shade into color.a; kFaceShade lookup is unused on this
    // path. normalIndex defaulted to top-face (1) -> kFaceShade[1]==1.0 so the
    // fragment does not double-apply shade.
    out.normalIndex = 1u;
    return out;
}

fragment half4 fragment_terrain(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]]
) {
    // Block atlas: nearest, for crisp pixel art.
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    // Lightmap: linear, so block/sky-light transitions and AO interpolation
    // are smooth across faces (vanilla MC uses linear here).
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear);
    half4 texColor = blockAtlas.sample(atlasSampler, in.texCoord);
    if (texColor.a < half(0.5)) {
        discard_fragment();
    }
    // Sodium 0.5 bakes per-face shade into color.a (range ~0.5..1.0). rgb is
    // the block tint. The lightmap supplies sky/block light contribution.
    half3 tint = in.color.rgb;
    half shade = in.color.a;
    half3 light = lightmap.sample(lightSampler, in.lightUV).rgb;
    half3 finalRgb = texColor.rgb * tint * shade * light;
    return half4(finalRgb, texColor.a);
}

fragment half4 fragment_terrain_cutout(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]]
) {
    constexpr sampler atlasSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    constexpr sampler lightSampler(mag_filter::linear, min_filter::linear);
    half4 texColor = blockAtlas.sample(atlasSampler, in.texCoord);
    if (texColor.a < half(0.5)) discard_fragment();
    half3 tint = in.color.rgb;
    half shade = in.color.a;
    half3 light = lightmap.sample(lightSampler, in.lightUV).rgb;
    half3 finalRgb = texColor.rgb * tint * shade * light;
    return half4(finalRgb, half(1.0));
}

struct InhouseTerrainVertex {
    packed_short3 position;
    packed_ushort2 texCoord;
    packed_uchar4 color;
    uchar packedLight;
    uchar normalIndex;
};

vertex SimpleVertexOut vertex_terrain_inhouse(
    device const InhouseTerrainVertex* vertices [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant float4& cameraPosition [[buffer(3)]],
    constant float4* chunkOffsets [[buffer(4)]],
    uint vid [[vertex_id]],
    uint iid [[instance_id]]
) {
    InhouseTerrainVertex v = vertices[vid];
    SimpleVertexOut out;
    float4 chunkOffset = chunkOffsets[iid];
    uint faceMask = as_type<uint>(chunkOffset.w);
    if (faceMask != 0 && v.color[3] >= 254) {
        uint nIdx = v.normalIndex & 0x7;
        if (nIdx < 6 && ((faceMask >> nIdx) & 1) == 0) {
            out.position = float4(0.0, 0.0, -2.0, 1.0);
            out.texCoord = float2(0.0);
            out.color    = half4(0.0h);
            out.lightUV  = float2(0.0f);
            out.light    = 0.0h;
            out.normalIndex = 1;
            return out;
        }
    }
    float3 localPos = float3(short3(v.position)) / 256.0;
    float3 worldPos = localPos + chunkOffset.xyz;
    float4 viewPos = modelViewMatrix * float4(worldPos, 1.0);
    out.position = projectionMatrix * viewPos;
    out.texCoord = float2(v.texCoord) / 65535.0;
    out.color    = half4(float4(v.color) / 255.0);
    uint blockLight = uint(v.packedLight & 0xFu);
    uint skyLight   = uint((v.packedLight >> 4) & 0xFu);
    out.lightUV = float2((float(blockLight) + 0.5f) / 16.0f,
                         (float(skyLight) + 0.5f) / 16.0f);
    out.light = half(max(max(out.lightUV.x,
                             out.lightUV.y * cameraPosition.w), 0.15f));
    out.normalIndex = uint(v.normalIndex & 0x7);
    return out;
}

struct TerrainFragArgs {
    texture2d<half> blockAtlas [[id(0)]];
    texture2d<half> lightmap   [[id(1)]];
};








fragment half4 fragment_terrain_opaque(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {



        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {
            texColor.a = half(1.0);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = lightmap.sample(texSampler, in.lightUV).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, half(1.0));
}

fragment half4 fragment_terrain_icb_opaque(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = resources.blockAtlas.sample(texSampler, in.texCoord);
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {

        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {
            texColor.a = half(1.0);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = resources.lightmap.sample(texSampler, in.lightUV).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, half(1.0));
}

fragment half4 fragment_terrain_icb(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]],
    constant float4& overlayParams [[buffer(5)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = resources.blockAtlas.sample(texSampler, in.texCoord);
    half vertAlpha = in.color.a;
    if (texColor.a < half(0.5)) {
        if (vertAlpha > half(0.994) && vertAlpha < half(0.998)) {
            texColor.a = half(1.0);
        } else {
            discard_fragment();
        }
    }
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = resources.lightmap.sample(texSampler, in.lightUV).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, vertAlpha < half(0.99) ? vertAlpha : half(1.0));
}




fragment half4 fragment_terrain_cutout_inhouse(
    SimpleVertexOut in [[stage_in]],
    texture2d<half> blockAtlas  [[texture(0)]],
    texture2d<half> lightmap    [[texture(1)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = blockAtlas.sample(texSampler, in.texCoord);
    if (texColor.a < half(0.5)) discard_fragment();
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = lightmap.sample(texSampler, in.lightUV).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, half(1.0));
}

fragment half4 fragment_terrain_icb_cutout(
    SimpleVertexOut in [[stage_in]],
    constant TerrainFragArgs& resources [[buffer(0)]]
) {
    constexpr sampler texSampler(mag_filter::nearest, min_filter::nearest, mip_filter::nearest);
    half4 texColor = resources.blockAtlas.sample(texSampler, in.texCoord);
    if (texColor.a < half(0.5)) discard_fragment();
    half4 tinted = texColor * in.color;
    half faceShade = kFaceShade[min(in.normalIndex, 5u)];
    half3 light = resources.lightmap.sample(texSampler, in.lightUV).rgb;
    tinted.rgb *= light * faceShade;
    return half4(tinted.rgb, half(1.0));
}
struct DebugVertexOut {
    float4 position [[position]];
    half4 color;
};

vertex DebugVertexOut vertex_debug(
    device const packed_float3* positions [[buffer(0)]],
    constant float4x4& projectionMatrix [[buffer(1)]],
    constant float4x4& modelViewMatrix [[buffer(2)]],
    constant float4& debugColor [[buffer(5)]],
    uint vid [[vertex_id]]
) {
    DebugVertexOut out;
    float4 viewPos = modelViewMatrix * float4(float3(positions[vid]), 1.0);
    out.position = projectionMatrix * viewPos;
    out.color = half4(debugColor);
    return out;
}

fragment float4 fragment_debug(DebugVertexOut in [[stage_in]]) {
    return float4(in.color);
}
