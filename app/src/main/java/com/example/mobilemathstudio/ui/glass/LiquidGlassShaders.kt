package com.example.mobilemathstudio.ui.glass

/**
 * Optically grounded 2-pass AGSL shaders ported directly from the reference Liquid Glass prototype
 * for Android 13+ (API 33, RuntimeShader + RenderEffect).
 *
 * Optics:
 * - Flat glass interior (zero bulky droplet/bubble dome distortion in the center).
 * - Steep squircle peripheral bevel (`p = 4.6`) with exact Snell's law refraction,
 *   symmetrical mirror-axis reflection, and RGB chromatic dispersion.
 * - Crisp directional edge cut highlight, opposite-side edge shadow, and hairline Fresnel contour.
 * - Optional debug mirror axis controlled by `uShowMirrorAxis` (disabled in production presets).
 *
 * AGSL / SkSL safety invariants:
 * - Every `smoothstep(edge0, edge1, x)` strictly satisfies `edge0 < edge1`.
 * - Every `pow(base, exp)` strictly guards `base > 0.0`.
 * - No `out` parameters, no dynamic loops, no `gl_FragCoord`, no `texture2D`.
 */
object LiquidGlassShaders {

    private const val SHARED_OPTICS_AGSL = """
float sdRoundRect(float2 p, float2 halfSize, float radius) {
    float r = clamp(radius, 0.0, min(halfSize.x, halfSize.y));
    float2 q = abs(p) - (halfSize - float2(r));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

// Fast, C-infinity smooth Euclidean distance approximation for an arbitrary ellipse.
// Accurate to <0.05 px throughout the entire rim, bevel, and mirror band.
float sdEllipseApprox(float2 p, float2 ab) {
    if (abs(ab.x - ab.y) < 0.5) {
        return length(p) - ab.x;
    }
    float2 safeAb = max(ab, float2(1.0));
    float k0 = length(p / safeAb);
    if (k0 < 1e-4) {
        return -min(safeAb.x, safeAb.y);
    }
    float k1 = length(p / (safeAb * safeAb));
    float d = k0 * (k0 - 1.0) / max(k1, 1e-6);
    return max(d, -min(safeAb.x, safeAb.y));
}

float evalSdf(float2 p, float2 halfSize, float radius, float shapeType) {
    if (shapeType > 0.5) {
        return sdEllipseApprox(p, halfSize);
    }
    return sdRoundRect(p, halfSize, radius);
}

// Exact Snell's law refraction on a crisp flat-topped glass plate with a steep squircle bevel:
// h(e) = (1 - (1 - e)^p)^(1/p), with p = 4.6 so bending is tightly focused along the sharp rim
// while the interior remains a completely flat, undistorted pane of glass (not a water bubble).
// Returns float2(bendNorm, cosTheta).
float2 evalSnellBend(float depth, float bandPx, float ior) {
    float e = clamp(depth / max(bandPx, 4.0), 0.0, 1.0);
    float p = 4.6;
    float oneMinusE = max(1.0 - e, 0.0025);
    float innerTerm = max(1.0 - pow(oneMinusE, p), 0.0005);
    float slope = pow(oneMinusE, p - 1.0) / pow(innerTerm, 1.0 - 1.0 / p);

    float invLen = inversesqrt(1.0 + slope * slope);
    float sinT = slope * invLen;
    float cosT = invLen;

    float safeIor = max(ior, 1.01);
    float sinTp = sinT / safeIor;
    float cosTp = sqrt(max(1.0 - sinTp * sinTp, 0.0001));

    float bendRaw = (sinT * cosTp - cosT * sinTp) / max(cosT * cosTp + sinT * sinTp, 1e-4);
    float maxBend = sqrt(max(safeIor * safeIor - 1.0, 0.01));
    float bendNorm = clamp(bendRaw / maxBend, 0.0, 1.0);
    // Clean C1 taper into the flat interior
    float seamFade = 1.0 - smoothstep(0.62, 1.0, e);
    return float2(bendNorm * seamFade, cosT);
}

float3 sampleRgbClamped(float2 coord, float4 bounds) {
    float2 c = clamp(coord, bounds.xy, bounds.zw);
    half4 s = backdrop.eval(c);
    float a = max(float(s.a), 0.001);
    return clamp(float3(s.rgb) / a, 0.0, 1.0);
}

float3 sampleChromatic(float2 baseCoord, float2 offsetVec, float dispersionAmt, float4 bounds) {
    if (dispersionAmt <= 0.001 || dot(offsetVec, offsetVec) < 0.04) {
        return sampleRgbClamped(baseCoord + offsetVec, bounds);
    }
    // Red bends slightly less (lower refractive index), Blue bends further (higher refractive index)
    float3 r = sampleRgbClamped(baseCoord + offsetVec * (1.0 - dispersionAmt), bounds);
    float3 g = sampleRgbClamped(baseCoord + offsetVec, bounds);
    float3 b = sampleRgbClamped(baseCoord + offsetVec * (1.0 + dispersionAmt), bounds);
    return float3(r.r, g.g, b.b);
}

// Affine chroma-preserving iOS glass tone mapping
float3 applyGlassTone(float3 rgb, float3 tintColor, float tintAlpha, float saturationBoost) {
    float luma = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    float3 vibrant = clamp(float3(luma) + (rgb - float3(luma)) * saturationBoost, 0.0, 1.0);
    return mix(vibrant, tintColor, clamp(tintAlpha, 0.0, 0.85));
}

// Evaluates flat-center Liquid Glass optics:
// 1) Sharp peripheral Snell refraction (zero dome distortion in the flat center!)
// 2) Symmetrical mirror reflection across the imaginary axis at depth = mirrorAxisPx
float3 evaluateLiquidBackdrop(
    float2 fragCoord,
    float2 localPos,
    float2 halfSize,
    float depth,
    float2 inwardN,
    float axisFade,
    float bendNorm,
    float bandPx,
    float peakShift,
    float ior,
    float dispersion,
    float mirrorStrength,
    float mirrorAxisPx,
    float2 touchPos,
    float touchActive,
    float4 bounds
) {
    float minHalf = min(halfSize.x, halfSize.y);

    // 1. Steep peripheral rim refraction only — flat interior stays 100% undistorted
    float refractShift = min(bendNorm * peakShift * axisFade, minHalf * 0.65);

    // Subtle interactive fingertip press swell only when actively pressed
    float2 touchOffset = float2(0.0);
    if (touchActive > 0.001) {
        float sigma = max(minHalf * 0.85, 22.0);
        float2 dTouch = localPos - touchPos;
        float tFalloff = exp(-dot(dTouch, dTouch) / (2.0 * sigma * sigma));
        touchOffset = -dTouch * (0.08 * touchActive * tFalloff);
    }

    float2 refractVec = inwardN * refractShift + touchOffset;
    float dispScale = dispersion * (0.25 + 1.25 * bendNorm);
    float3 refractRgb = sampleChromatic(fragCoord, refractVec, dispScale, bounds);

    // 2. Mirror reflection across the imaginary reflection axis (depth == mirrorAxis)
    // Outer band (0 <= depth < mirrorAxis) reflects inner content (depth > mirrorAxis)
    // symmetrically across the axis, matching refractShift continuously at depth == mirrorAxis.
    if (mirrorStrength > 0.002) {
        float axis = clamp(mirrorAxisPx, 2.0, minHalf * 0.72);
        float foldSoft = max(axis * 0.09, 0.8);
        float mirrorZone = 1.0 - smoothstep(axis - foldSoft * 0.75, axis + foldSoft * 1.0, depth);

        if (mirrorZone > 0.002) {
            float bendAtAxis = evalSnellBend(axis, bandPx, ior).x;
            float refractAtAxis = min(bendAtAxis * peakShift * axisFade, minHalf * 0.55);

            float deltaAxis = axis - depth;
            float foldDist = 0.5 * (deltaAxis + sqrt(deltaAxis * deltaAxis + foldSoft * foldSoft));
            float mirrorShift = min((refractAtAxis + 2.0 * foldDist) * axisFade, minHalf * 0.92);

            float2 mirrorVec = inwardN * mirrorShift + touchOffset;
            float mirrorDisp = dispersion * (0.55 + 1.0 * mirrorZone);
            float3 mirrorRgb = sampleChromatic(fragCoord, mirrorVec, mirrorDisp, bounds);

            float mixFactor = clamp(mirrorZone * mirrorStrength, 0.0, 1.0);
            refractRgb = mix(refractRgb, mirrorRgb, mixFactor);
        }
    }

    return refractRgb;
}
"""

    /**
     * Pass 1 AGSL shader: runs on the blurred backdrop (either shared pre-blurred layer or
     * chained `RenderEffect.createBlurEffect`).
     */
    const val BLURRED_PASS_AGSL: String = """
uniform shader backdrop;
uniform float2 uOrigin;
uniform float2 uSize;
uniform float4 uBackdropBounds;
uniform float uCornerRadius;
uniform float uShapeType;
uniform float uBevelWidth;
uniform float uRefractionStrength;
uniform float uIor;
uniform float uDispersion;
uniform float uMirrorStrength;
uniform float uMirrorAxis;
uniform float3 uTintColor;
uniform float uTintAlpha;
uniform float uSaturationBoost;
uniform float2 uTouchPos;
uniform float uTouchActive;
""" + SHARED_OPTICS_AGSL + """
half4 main(float2 fragCoord) {
    float2 halfSize = max(uSize * 0.5, float2(1.0));
    float2 center = uOrigin + halfSize;
    float2 localPos = fragCoord - center;

    float d = evalSdf(localPos, halfSize, uCornerRadius, uShapeType);
    float depth = max(-d, 0.0);

    float bandPx = max(uBevelWidth, 4.0);
    float eps = clamp(bandPx * 0.22, 1.2, 7.0);
    float gx = evalSdf(localPos + float2(eps, 0.0), halfSize, uCornerRadius, uShapeType)
             - evalSdf(localPos - float2(eps, 0.0), halfSize, uCornerRadius, uShapeType);
    float gy = evalSdf(localPos + float2(0.0, eps), halfSize, uCornerRadius, uShapeType)
             - evalSdf(localPos - float2(0.0, eps), halfSize, uCornerRadius, uShapeType);
    float2 grad = float2(gx, gy);
    float gradLen = length(grad);
    float conf = clamp(gradLen / (2.0 * eps), 0.0, 1.0);
    float axisFade = smoothstep(0.12, 0.78, conf);
    float2 inwardN = (gradLen > 1e-4) ? (-grad / gradLen) : float2(0.0, 1.0);

    float2 snell = evalSnellBend(depth, bandPx, uIor);
    float bendNorm = snell.x;
    float peakShift = bandPx * 0.90 * uRefractionStrength;

    float3 rgb = evaluateLiquidBackdrop(
        fragCoord, localPos, halfSize, depth, inwardN, axisFade,
        bendNorm, bandPx, peakShift, uIor,
        uDispersion, uMirrorStrength, uMirrorAxis, uTouchPos, uTouchActive,
        uBackdropBounds
    );
    float3 toned = applyGlassTone(rgb, uTintColor, uTintAlpha, uSaturationBoost);
    return half4(clamp(toned, 0.0, 1.0), 1.0);
}
"""

    /**
     * Pass 2 AGSL shader: runs on the SHARP (unblurred) live backdrop.
     * Renders the razor-sharp glass rim, mirror fold, crisp directional edge highlight,
     * opposite-side edge shadow, hairline Fresnel border, and optional touch lift.
     */
    const val SHARP_AND_LIGHTING_PASS_AGSL: String = """
uniform shader backdrop;
uniform float2 uOrigin;
uniform float2 uSize;
uniform float4 uBackdropBounds;
uniform float uCornerRadius;
uniform float uShapeType;
uniform float uBevelWidth;
uniform float uRefractionStrength;
uniform float uIor;
uniform float uDispersion;
uniform float uMirrorStrength;
uniform float uMirrorAxis;
uniform float uShowMirrorAxis;
uniform float uHasBlurPass;
uniform float uRimCrispness;
uniform float2 uLightDir;
uniform float uHighlightIntensity;
uniform float uHighlightWidth;
uniform float uShadowIntensity;
uniform float uFresnelIntensity;
uniform float3 uTintColor;
uniform float uTintAlpha;
uniform float uSaturationBoost;
uniform float2 uTouchPos;
uniform float uTouchActive;
""" + SHARED_OPTICS_AGSL + """
half4 main(float2 fragCoord) {
    float2 halfSize = max(uSize * 0.5, float2(1.0));
    float2 center = uOrigin + halfSize;
    float2 localPos = fragCoord - center;

    float d = evalSdf(localPos, halfSize, uCornerRadius, uShapeType);
    float depth = max(-d, 0.0);

    float bandPx = max(uBevelWidth, 4.0);
    float eps = clamp(bandPx * 0.22, 1.2, 7.0);
    float gx = evalSdf(localPos + float2(eps, 0.0), halfSize, uCornerRadius, uShapeType)
             - evalSdf(localPos - float2(eps, 0.0), halfSize, uCornerRadius, uShapeType);
    float gy = evalSdf(localPos + float2(0.0, eps), halfSize, uCornerRadius, uShapeType)
             - evalSdf(localPos - float2(0.0, eps), halfSize, uCornerRadius, uShapeType);
    float2 grad = float2(gx, gy);
    float gradLen = length(grad);
    float conf = clamp(gradLen / (2.0 * eps), 0.0, 1.0);
    float axisFade = smoothstep(0.12, 0.78, conf);
    float2 outwardN = (gradLen > 1e-4) ? (grad / gradLen) : float2(0.0, -1.0);
    float2 inwardN = -outwardN;

    // Fine 1px-epsilon normal for razor-crisp edge cut lighting
    float fEps = 1.0;
    float fgx = evalSdf(localPos + float2(fEps, 0.0), halfSize, uCornerRadius, uShapeType)
              - evalSdf(localPos - float2(fEps, 0.0), halfSize, uCornerRadius, uShapeType);
    float fgy = evalSdf(localPos + float2(0.0, fEps), halfSize, uCornerRadius, uShapeType)
              - evalSdf(localPos - float2(0.0, fEps), halfSize, uCornerRadius, uShapeType);
    float2 fGrad = float2(fgx, fgy);
    float fLen = length(fGrad);
    float2 sharpOutwardN = (fLen > 1e-4) ? (fGrad / fLen) : outwardN;

    float2 snell = evalSnellBend(depth, bandPx, uIor);
    float bendNorm = snell.x;
    float peakShift = bandPx * 0.90 * uRefractionStrength;

    // Keep the outer optical rim crystal-sharp while the flat interior stays silky-blurred
    float minHalf = min(halfSize.x, halfSize.y);
    float axis = clamp(uMirrorAxis, 2.0, minHalf * 0.72);
    float crispZoneWidth = max(bandPx * 0.85, axis * 1.25);
    float rimProgress = clamp(depth / max(crispZoneWidth, 1.0), 0.0, 1.0);
    float rimCrispMask = (1.0 - rimProgress) * (1.0 - rimProgress);
    float sharpWeight = (uHasBlurPass < 0.5) ? 1.0 : clamp(uRimCrispness * rimCrispMask, 0.0, 1.0);

    float3 sharpTinted = float3(0.0);
    if (sharpWeight > 0.002) {
        float3 sharpRgb = evaluateLiquidBackdrop(
            fragCoord, localPos, halfSize, depth, inwardN, axisFade,
            bendNorm, bandPx, peakShift, uIor,
            uDispersion, uMirrorStrength, uMirrorAxis, uTouchPos, uTouchActive,
            uBackdropBounds
        );
        sharpTinted = applyGlassTone(sharpRgb, uTintColor, uTintAlpha, uSaturationBoost);
    }

    // === 4. Crisp, Flat-Glass Edge Cut Lighting ===
    float ndotl = dot(sharpOutwardN, uLightDir);
    float posN = max(ndotl, 0.0);
    float negN = max(-ndotl, 0.0);
    float hw = max(uHighlightWidth, 0.75);

    // Crisp flat-edge bevel line right at the rim
    float crispRimCut = 1.0 - smoothstep(hw * 0.55, hw * 1.45, depth);
    float dBead = (depth - min(hw * 0.35, 1.15)) / max(hw * 0.55, 0.70);
    float sharpEdgeBead = exp(-(dBead * dBead));

    float keyLobe = (posN > 0.001) ? pow(posN, 2.2) : 0.0;
    float sharpArc = (posN > 0.001) ? pow(posN, 5.5) : 0.0;
    float counterLobe = ((negN > 0.001) ? pow(negN, 9.0) : 0.0) * 0.14 * (1.0 - 0.65 * uShadowIntensity);
    float specular = (sharpEdgeBead * (0.72 * keyLobe + 0.38 * sharpArc + counterLobe)
                    + crispRimCut * 0.24 * keyLobe) * uHighlightIntensity;

    // Razor-thin continuous crystalline hairline outline all around the glass cut
    float hairlineEdge = 1.0 - smoothstep(0.45, max(hw * 1.05, 1.7), depth);
    float fresnel = hairlineEdge * (0.18 + 0.38 * posN) * uFresnelIntensity;

    // Touch press localized luminance lift
    float touchLift = 0.0;
    if (uTouchActive > 0.001) {
        float sigma = max(minHalf * 0.7, 24.0);
        float2 dTouch = localPos - uTouchPos;
        touchLift = exp(-dot(dTouch, dTouch) / (2.0 * sigma * sigma)) * 0.08 * uTouchActive;
    }

    float specTotal = clamp(specular + fresnel + touchLift, 0.0, 0.96);
    float3 specColor = float3(1.0, 1.0, 1.0);

    // Crisp opposite-side edge shadow
    float shadowSide = (negN > 0.001) ? pow(negN, 1.8) : 0.0;
    float shadowOuterCut = 1.0 - smoothstep(0.35, max(hw * 0.95, 1.55), depth);
    float shadowRimCut = 1.0 - smoothstep(hw * 0.65, hw * 1.85, depth);
    float shadowTotal = clamp(
        (shadowOuterCut * 0.64 + shadowRimCut * 0.36) * shadowSide * uShadowIntensity,
        0.0,
        0.72
    );

    // Optional debug visualization of the imaginary red mirror reflection axis
    if (uShowMirrorAxis > 0.5 && uMirrorStrength > 0.002) {
        float axisLine = 1.0 - smoothstep(0.55, 1.65, abs(depth - axis));
        if (axisLine > 0.01) {
            specColor = mix(specColor, float3(1.0, 0.18, 0.28), axisLine * 0.95);
            specTotal = max(specTotal, axisLine * 0.92);
        }
    }

    // Exact premultiplied SrcOver composition onto Pass 1 (blurred) or screen
    float keepDst = (1.0 - specTotal) * (1.0 - shadowTotal) * (1.0 - sharpWeight);
    float srcAlpha = clamp(1.0 - keepDst, 0.0, 1.0);
    float3 srcRgb = specTotal * specColor
                  + (1.0 - specTotal) * (1.0 - shadowTotal) * sharpWeight * sharpTinted;
    srcRgb = clamp(srcRgb, float3(0.0), float3(srcAlpha));

    return half4(srcRgb, srcAlpha);
}
"""

    /**
     * Maximum floating keys batched into ONE keyboard AGSL pass (covers Standard 39,
     * Advanced 36, Cyrillic Letters 43, plus the floating collapse chevron button).
     */
    const val MAX_KEYBOARD_KEYS: Int = 48

    /**
     * Batched Pass 1 AGSL shader for the entire floating math keyboard.
     *
     * Runs ONCE on a single 0.5× downsampled blurred keyboard layer (1 RenderNode total instead
     * of 38+ separate RenderNodes!), evaluating full Snell refraction, 59% RGB chromatic
     * dispersion, 85% symmetrical mirror reflection across the 15.9 dp axis, glass tone mapping,
     * and per-key functional group color filters while returning transparent `half4(0)` in gaps.
     */
    const val KEYBOARD_BLURRED_PASS_AGSL: String = """
uniform shader backdrop;
uniform float2 uOrigin;
uniform float4 uBackdropBounds;
uniform float uKeyCount;
uniform float4 uKeyRects[48];
uniform float4 uKeyTints[48];
uniform float uKeyRadii[48];
uniform float uBevelWidth;
uniform float uRefractionStrength;
uniform float uIor;
uniform float uDispersion;
uniform float uMirrorStrength;
uniform float uMirrorAxis;
uniform float3 uTintColor;
uniform float uTintAlpha;
uniform float uSaturationBoost;
""" + SHARED_OPTICS_AGSL + """
half4 main(float2 fragCoord) {
    float2 localCoord = fragCoord - uOrigin;
    float4 hitRect = float4(0.0);
    float4 hitTint = float4(0.0);
    float hitRadius = 0.0;
    float found = 0.0;

    // Slight 1.5 half-res px margin prevents bilinear dark halo fringes when upscaled 2x
    // inside the crisp 1x vector clipPath of all floating keys.
    for (int i = 0; i < 48; i++) {
        if (float(i) >= uKeyCount) {
            break;
        }
        float4 r = uKeyRects[i];
        if (localCoord.y >= (r.y - 1.5) && localCoord.y <= (r.w + 1.5) &&
            localCoord.x >= (r.x - 1.5) && localCoord.x <= (r.z + 1.5)) {
            hitRect = r;
            hitTint = uKeyTints[i];
            hitRadius = uKeyRadii[i];
            found = 1.0;
            break;
        }
    }
    if (found < 0.5) {
        return half4(0.0);
    }

    float2 halfSize = max((hitRect.zw - hitRect.xy) * 0.5, float2(1.0));
    float2 center = (hitRect.xy + hitRect.zw) * 0.5;
    float2 localPos = localCoord - center;

    float d = sdRoundRect(localPos, halfSize, hitRadius);
    float depth = max(-d, 0.0);

    float bandPx = max(uBevelWidth, 2.0);
    float eps = clamp(bandPx * 0.22, 0.75, 4.0);
    float gx = sdRoundRect(localPos + float2(eps, 0.0), halfSize, hitRadius)
             - sdRoundRect(localPos - float2(eps, 0.0), halfSize, hitRadius);
    float gy = sdRoundRect(localPos + float2(0.0, eps), halfSize, hitRadius)
             - sdRoundRect(localPos - float2(0.0, eps), halfSize, hitRadius);
    float2 grad = float2(gx, gy);
    float gradLen = length(grad);
    float conf = clamp(gradLen / (2.0 * eps), 0.0, 1.0);
    float axisFade = smoothstep(0.12, 0.78, conf);
    float2 inwardN = (gradLen > 1e-4) ? (-grad / gradLen) : float2(0.0, 1.0);

    float2 snell = evalSnellBend(depth, bandPx, uIor);
    float bendNorm = snell.x;
    float peakShift = bandPx * 0.90 * uRefractionStrength;

    float3 rgb = evaluateLiquidBackdrop(
        fragCoord, localPos, halfSize, depth, inwardN, axisFade,
        bendNorm, bandPx, peakShift, uIor,
        uDispersion, uMirrorStrength, uMirrorAxis, float2(0.0), 0.0,
        uBackdropBounds
    );
    float3 toned = applyGlassTone(rgb, uTintColor, uTintAlpha, uSaturationBoost);
    float3 groupTinted = mix(toned, hitTint.rgb, clamp(hitTint.a, 0.0, 0.92));
    return half4(clamp(groupTinted, 0.0, 1.0), 1.0);
}
"""

    /**
     * Batched Pass 2 AGSL shader for the entire floating math keyboard.
     *
     * Runs ONCE at full 1.0× resolution directly on the sharp backdrop (ZERO blur!).
     * Renders razor-sharp anti-aliased rounded key edges, sharp rim refraction/dispersion/mirror,
     * 0.94 dp / 51% directional edge cut highlight along 138°, 67% Fresnel hairline, and 57%
     * opposite-side shadow, while leaving every gap between keys 100% transparent (`half4(0)`).
     */
    const val KEYBOARD_SHARP_AND_LIGHTING_PASS_AGSL: String = """
uniform shader backdrop;
uniform float2 uOrigin;
uniform float4 uBackdropBounds;
uniform float uKeyCount;
uniform float4 uKeyRects[48];
uniform float4 uKeyTints[48];
uniform float uKeyRadii[48];
uniform float uBevelWidth;
uniform float uRefractionStrength;
uniform float uIor;
uniform float uDispersion;
uniform float uMirrorStrength;
uniform float uMirrorAxis;
uniform float uHasBlurPass;
uniform float uRimCrispness;
uniform float2 uLightDir;
uniform float uHighlightIntensity;
uniform float uHighlightWidth;
uniform float uShadowIntensity;
uniform float uFresnelIntensity;
uniform float3 uTintColor;
uniform float uTintAlpha;
uniform float uSaturationBoost;
""" + SHARED_OPTICS_AGSL + """
half4 main(float2 fragCoord) {
    float2 localCoord = fragCoord - uOrigin;
    float4 hitRect = float4(0.0);
    float4 hitTint = float4(0.0);
    float hitRadius = 0.0;
    float found = 0.0;

    for (int i = 0; i < 48; i++) {
        if (float(i) >= uKeyCount) {
            break;
        }
        float4 r = uKeyRects[i];
        if (localCoord.y >= r.y && localCoord.y <= r.w &&
            localCoord.x >= r.x && localCoord.x <= r.z) {
            hitRect = r;
            hitTint = uKeyTints[i];
            hitRadius = uKeyRadii[i];
            found = 1.0;
            break;
        }
    }
    if (found < 0.5) {
        return half4(0.0);
    }

    float2 halfSize = max((hitRect.zw - hitRect.xy) * 0.5, float2(1.0));
    float2 center = (hitRect.xy + hitRect.zw) * 0.5;
    float2 localPos = localCoord - center;

    float d = sdRoundRect(localPos, halfSize, hitRadius);
    float aaMask = clamp(0.5 - d, 0.0, 1.0);
    if (aaMask <= 0.001) {
        return half4(0.0);
    }
    float depth = max(-d, 0.0);

    float bandPx = max(uBevelWidth, 4.0);
    float eps = clamp(bandPx * 0.22, 1.2, 7.0);
    float gx = sdRoundRect(localPos + float2(eps, 0.0), halfSize, hitRadius)
             - sdRoundRect(localPos - float2(eps, 0.0), halfSize, hitRadius);
    float gy = sdRoundRect(localPos + float2(0.0, eps), halfSize, hitRadius)
             - sdRoundRect(localPos - float2(0.0, eps), halfSize, hitRadius);
    float2 grad = float2(gx, gy);
    float gradLen = length(grad);
    float conf = clamp(gradLen / (2.0 * eps), 0.0, 1.0);
    float axisFade = smoothstep(0.12, 0.78, conf);
    float2 outwardN = (gradLen > 1e-4) ? (grad / gradLen) : float2(0.0, -1.0);
    float2 inwardN = -outwardN;

    // Fine 1px-epsilon normal for razor-crisp edge cut lighting on keycaps
    float fEps = 1.0;
    float fgx = sdRoundRect(localPos + float2(fEps, 0.0), halfSize, hitRadius)
              - sdRoundRect(localPos - float2(fEps, 0.0), halfSize, hitRadius);
    float fgy = sdRoundRect(localPos + float2(0.0, fEps), halfSize, hitRadius)
              - sdRoundRect(localPos - float2(0.0, fEps), halfSize, hitRadius);
    float2 fGrad = float2(fgx, fgy);
    float fLen = length(fGrad);
    float2 sharpOutwardN = (fLen > 1e-4) ? (fGrad / fLen) : outwardN;

    float2 snell = evalSnellBend(depth, bandPx, uIor);
    float bendNorm = snell.x;
    float peakShift = bandPx * 0.90 * uRefractionStrength;

    float minHalf = min(halfSize.x, halfSize.y);
    float axis = clamp(uMirrorAxis, 2.0, minHalf * 0.72);
    float crispZoneWidth = max(bandPx * 0.85, axis * 1.25);
    float rimProgress = clamp(depth / max(crispZoneWidth, 1.0), 0.0, 1.0);
    float rimCrispMask = (1.0 - rimProgress) * (1.0 - rimProgress);
    float sharpWeight = (uHasBlurPass < 0.5) ? 1.0 : clamp(uRimCrispness * rimCrispMask, 0.0, 1.0);

    float3 sharpTinted = float3(0.0);
    if (sharpWeight > 0.002) {
        float3 sharpRgb = evaluateLiquidBackdrop(
            fragCoord, localPos, halfSize, depth, inwardN, axisFade,
            bendNorm, bandPx, peakShift, uIor,
            uDispersion, uMirrorStrength, uMirrorAxis, float2(0.0), 0.0,
            uBackdropBounds
        );
        float3 baseToned = applyGlassTone(sharpRgb, uTintColor, uTintAlpha, uSaturationBoost);
        sharpTinted = mix(baseToned, hitTint.rgb, clamp(hitTint.a, 0.0, 0.92));
    }

    float ndotl = dot(sharpOutwardN, uLightDir);
    float posN = max(ndotl, 0.0);
    float negN = max(-ndotl, 0.0);
    float hw = max(uHighlightWidth, 0.75);

    float crispRimCut = 1.0 - smoothstep(hw * 0.55, hw * 1.45, depth);
    float dBead = (depth - min(hw * 0.35, 1.15)) / max(hw * 0.55, 0.70);
    float sharpEdgeBead = exp(-(dBead * dBead));

    float keyLobe = (posN > 0.001) ? pow(posN, 2.2) : 0.0;
    float sharpArc = (posN > 0.001) ? pow(posN, 5.5) : 0.0;
    float counterLobe = ((negN > 0.001) ? pow(negN, 9.0) : 0.0) * 0.14 * (1.0 - 0.65 * uShadowIntensity);
    float specular = (sharpEdgeBead * (0.72 * keyLobe + 0.38 * sharpArc + counterLobe)
                    + crispRimCut * 0.24 * keyLobe) * uHighlightIntensity;

    float hairlineEdge = 1.0 - smoothstep(0.45, max(hw * 1.05, 1.7), depth);
    float fresnel = hairlineEdge * (0.18 + 0.38 * posN) * uFresnelIntensity;

    float specTotal = clamp(specular + fresnel, 0.0, 0.96);
    float3 specColor = float3(1.0, 1.0, 1.0);

    float shadowSide = (negN > 0.001) ? pow(negN, 1.8) : 0.0;
    float shadowOuterCut = 1.0 - smoothstep(0.35, max(hw * 0.95, 1.55), depth);
    float shadowRimCut = 1.0 - smoothstep(hw * 0.65, hw * 1.85, depth);
    float shadowTotal = clamp(
        (shadowOuterCut * 0.64 + shadowRimCut * 0.36) * shadowSide * uShadowIntensity,
        0.0,
        0.72
    );

    float keepDst = (1.0 - specTotal) * (1.0 - shadowTotal) * (1.0 - sharpWeight);
    float srcAlpha = clamp(1.0 - keepDst, 0.0, 1.0);
    float3 srcRgb = specTotal * specColor
                  + (1.0 - specTotal) * (1.0 - shadowTotal) * sharpWeight * sharpTinted;
    srcRgb = clamp(srcRgb, float3(0.0), float3(srcAlpha));

    return half4(srcRgb * aaMask, srcAlpha * aaMask);
}
"""
}
