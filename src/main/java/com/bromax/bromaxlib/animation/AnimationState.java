package com.bromax.bromaxlib.animation;

public class AnimationState {
    public final AnimationDefinition animation;
    public final float               speedMultiplier;
    public       float               tickF;
    public final float[][]           crossfadeSnapshot;

    public float[][] cachedDeltas;
    public float[][] prevCachedDeltas;
    public float[][] cachedTranslations;
    public float[][] prevCachedTranslations;

    public float   lockedBodyRY     = Float.NaN;
    public float   lockedHeadRX     = Float.NaN;
    public float   lockedHeadRY     = Float.NaN;
    public boolean lungeImpulseFired = false;
    /** Held at a fixed tick by the animation preview tool; never advanced or expired. */
    public boolean frozen            = false;
    /** Preview-only: replaces the animation's grip when set. */
    public WeaponGrip gripOverride   = null;

    private static final float CROSSFADE_TICKS = 2.5f;
    private static final float BLENDIN_TICKS   = 2.0f;
    private static final float BLENDOUT_TICKS  = 8.0f;

    public AnimationState(AnimationDefinition animation, float speedMultiplier,
                          float[][] crossfadeSnapshot) {
        this.animation       = animation;
        this.speedMultiplier = speedMultiplier;
        this.crossfadeSnapshot = crossfadeSnapshot;
        this.tickF           = 0f;
        refreshCache();
    }

    public boolean isComplete() {
        return tickF >= animation.duration + BLENDOUT_TICKS * speedMultiplier;
    }

    public boolean isMainBodyComplete() {
        return tickF >= animation.duration;
    }

    public float getBlendFactor(float interpolatedTick) {
        if (speedMultiplier <= 0 || !Float.isFinite(speedMultiplier)) return 1.0f;
        float realTicks = interpolatedTick / speedMultiplier;

        float t = Math.max(0f, Math.min(1f,
            realTicks / (crossfadeSnapshot != null ? CROSSFADE_TICKS : BLENDIN_TICKS)));
        float blend = t * (2.0f - t);

        float overrun = realTicks - animation.duration;
        if (overrun > 0f) {
            float blendOutT = Math.min(1.0f, overrun / BLENDOUT_TICKS);
            float fadeBlend = 1.0f - blendOutT * (2.0f - blendOutT);
            blend = Math.min(blend, fadeBlend);
        }
        return blend;
    }

    private final float[] CACHE_SCRATCH = new float[3];

    public void refreshCache() {
        if (cachedDeltas == null) cachedDeltas = new float[BoneTarget.COUNT][];
        if (prevCachedDeltas == null) prevCachedDeltas = new float[BoneTarget.COUNT][];

        for (int i = 0; i < BoneTarget.COUNT; i++) {
            float[] cur = cachedDeltas[i];
            if (cur == null) { prevCachedDeltas[i] = null; continue; }
            float[] prev = prevCachedDeltas[i];
            if (prev == null) prevCachedDeltas[i] = new float[]{cur[0], cur[1], cur[2]};
            else              { prev[0] = cur[0]; prev[1] = cur[1]; prev[2] = cur[2]; }
        }

        if (animation.hasTranslations) {
            if (cachedTranslations == null) cachedTranslations = new float[BoneTarget.COUNT][];
            if (prevCachedTranslations == null) prevCachedTranslations = new float[BoneTarget.COUNT][];
            for (int i = 0; i < BoneTarget.COUNT; i++) {
                float[] cur = cachedTranslations[i];
                if (cur == null) { prevCachedTranslations[i] = null; continue; }
                float[] prev = prevCachedTranslations[i];
                if (prev == null) prevCachedTranslations[i] = new float[]{cur[0], cur[1], cur[2]};
                else              { prev[0] = cur[0]; prev[1] = cur[1]; prev[2] = cur[2]; }
            }
        }

        float blend        = getBlendFactor(tickF);
        float crossfadeOut = (crossfadeSnapshot != null) ? (1.0f - blend) : 0.0f;
        float evalTick     = Math.min(tickF, (float) animation.duration);

        for (BoneTarget bone : animation.blendMask) {
            animation.evaluate(bone, evalTick, CACHE_SCRATCH);
            float dx = CACHE_SCRATCH[0] * blend;
            float dy = CACHE_SCRATCH[1] * blend;
            float dz = CACHE_SCRATCH[2] * blend;

            if (crossfadeSnapshot != null) {
                float[] snap = crossfadeSnapshot[bone.ordinal()];
                if (snap != null) { dx += snap[0] * crossfadeOut; dy += snap[1] * crossfadeOut; dz += snap[2] * crossfadeOut; }
            }
            float[] entry = cachedDeltas[bone.ordinal()];
            if (entry == null) cachedDeltas[bone.ordinal()] = new float[]{dx, dy, dz};
            else               { entry[0] = dx; entry[1] = dy; entry[2] = dz; }
        }

        if (animation.hasTranslations) {
            for (BoneTarget bone : animation.blendMask) {
                animation.evaluateTranslation(bone, evalTick, CACHE_SCRATCH);
                float dx = CACHE_SCRATCH[0] * blend;
                float dy = CACHE_SCRATCH[1] * blend;
                float dz = CACHE_SCRATCH[2] * blend;
                float[] entry = cachedTranslations[bone.ordinal()];
                if (entry == null) cachedTranslations[bone.ordinal()] = new float[]{dx, dy, dz};
                else               { entry[0] = dx; entry[1] = dy; entry[2] = dz; }
            }
        }
    }
}
