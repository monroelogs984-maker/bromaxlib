package com.bromax.bromaxlib.animation;

public class AnimationState {
    public final AnimationDefinition animation;
    public final float speedMultiplier;
    public float tickF;
    public final float[][] crossfadeSnapshot;

    /**
     * Bone deltas for the CURRENT tick, indexed by BoneTarget ordinal.
     * Each entry is [rx, ry, rz] in radians, or null if the bone isn't in the blend mask.
     * Written by refreshCache() on each tick; read by the render thread for interpolation.
     */
    public float[][] cachedDeltas;

    /**
     * Bone deltas for the PREVIOUS tick. The render thread interpolates between
     * prevCachedDeltas and cachedDeltas using partialTick to get sub-tick smooth motion.
     * Copied from cachedDeltas at the START of each refreshCache() before updating.
     */
    public float[][] prevCachedDeltas;

    /**
     * Bone translation offsets for the CURRENT tick, indexed by BoneTarget ordinal.
     * Each entry is [tx, ty, tz] in model pixels. Added to the bone's vanilla rotationPoint.
     * Only allocated when animation.hasTranslations is true.
     */
    public float[][] cachedTranslations;

    /**
     * Bone translation offsets for the PREVIOUS tick. Same interpolation role as prevCachedDeltas.
     */
    public float[][] prevCachedTranslations;

    /**
     * Body yaw (renderYawOffset converted to radians) captured on the first render frame
     * after the animation starts. Held constant for the animation's duration so that
     * body.ry doesn't change between render frames while body.rx/rz are non-zero.
     *
     * Without this, body = {rx=lean, ry=changing_every_frame, rz=tilt} — a complex 3D
     * rotation that forces gpu_tape/FoamFix to re-record the full player model every
     * render frame when the player turns. With this locked, all three body components
     * are constant between ticks and the cache stays valid.
     *
     * NaN means "not yet captured".
     */
    public float lockedBodyRY = Float.NaN;

    /** Set to true after the forward velocity impulse has been fired for this animation.
     *  Prevents the impulse from re-firing if onLivingUpdate runs multiple times. */
    public boolean lungeImpulseFired = false;

    /**
     * Head orientation captured on the first render frame of the animation.
     * Vanilla writes head.rx/ry every render frame from mouse look, which changes
     * the player display list every frame and busts the gpu_tape cache.
     * Locking it for the animation's duration keeps the head static (as BC/EF do).
     * NaN means "not yet captured".
     */
    public float lockedHeadRX = Float.NaN;
    public float lockedHeadRY = Float.NaN;

    private static final float CROSSFADE_TICKS = 2.5f;
    private static final float BLENDIN_TICKS   = 2.0f;
    // How many real ticks to spend fading back to vanilla after the animation ends.
    // Arm holds at the strike/follow-through pose and smoothly returns to vanilla.
    // BC uses ~10 ticks; 8 balances visibility with responsiveness.
    private static final float BLENDOUT_TICKS  = 8.0f;

    public AnimationState(AnimationDefinition animation, float speedMultiplier,
                          float[][] crossfadeSnapshot) {
        this.animation         = animation;
        this.speedMultiplier   = speedMultiplier;
        this.crossfadeSnapshot = crossfadeSnapshot;
        this.tickF             = 0f;
        refreshCache();
    }

    // State is only fully removed after the blend-out window expires.
    public boolean isComplete() {
        return tickF >= animation.duration + BLENDOUT_TICKS * speedMultiplier;
    }

    // Returns false as soon as the main animation body is done so the player can
    // queue the next attack while the visual blend-out is still running.
    public boolean isMainBodyComplete() {
        return tickF >= animation.duration;
    }

    public float getBlendFactor(float interpolatedTick) {
        if (speedMultiplier <= 0 || !Float.isFinite(speedMultiplier)) return 1.0f;
        float realTicks = interpolatedTick / speedMultiplier;

        // Blend-in
        float t = Math.max(0.0f, Math.min(1.0f,
            realTicks / (crossfadeSnapshot != null ? CROSSFADE_TICKS : BLENDIN_TICKS)));
        float blend = t * (2.0f - t);

        // Blend-out: once past duration, fade smoothly back to 0
        float overrun = realTicks - animation.duration;
        if (overrun > 0f) {
            float blendOutT = Math.min(1.0f, overrun / BLENDOUT_TICKS);
            float fadeBlend = 1.0f - blendOutT * (2.0f - blendOutT);
            blend = Math.min(blend, fadeBlend);
        }

        return blend;
    }

    /** Called once per game tick from tickAll(). Bakes current deltas so the render
     *  thread reads a stable array instead of re-running evaluate() at 60fps. */
    private final float[] CACHE_SCRATCH = new float[3];

    public void refreshCache() {
        if (cachedDeltas == null) {
            cachedDeltas = new float[BoneTarget.COUNT][];
        }

        // Snapshot current → prev before overwriting, so the render thread can interpolate.
        if (prevCachedDeltas == null) {
            prevCachedDeltas = new float[BoneTarget.COUNT][];
        }
        for (int i = 0; i < BoneTarget.COUNT; i++) {
            float[] cur = cachedDeltas[i];
            if (cur == null) { prevCachedDeltas[i] = null; continue; }
            float[] prev = prevCachedDeltas[i];
            if (prev == null) { prevCachedDeltas[i] = new float[]{cur[0], cur[1], cur[2]}; }
            else              { prev[0] = cur[0]; prev[1] = cur[1]; prev[2] = cur[2]; }
        }

        // Snapshot translation prev values before overwriting
        if (animation.hasTranslations) {
            if (cachedTranslations == null) cachedTranslations = new float[BoneTarget.COUNT][];
            if (prevCachedTranslations == null) prevCachedTranslations = new float[BoneTarget.COUNT][];
            for (int i = 0; i < BoneTarget.COUNT; i++) {
                float[] cur = cachedTranslations[i];
                if (cur == null) { prevCachedTranslations[i] = null; continue; }
                float[] prev = prevCachedTranslations[i];
                if (prev == null) { prevCachedTranslations[i] = new float[]{cur[0], cur[1], cur[2]}; }
                else              { prev[0] = cur[0]; prev[1] = cur[1]; prev[2] = cur[2]; }
            }
        }

        float blend        = getBlendFactor(tickF);
        float crossfadeOut = (crossfadeSnapshot != null) ? (1.0f - blend) : 0.0f;

        // During blend-out, clamp to duration so evaluate() returns the final keyframe
        // (the strike/follow-through pose). Animation JSONs no longer include a
        // return-to-zero keyframe, so the strike remains the last defined pose.
        float evalTick = Math.min(tickF, (float) animation.duration);

        for (BoneTarget bone : animation.blendMask) {
            animation.evaluate(bone, evalTick, CACHE_SCRATCH);
            float dx = CACHE_SCRATCH[0] * blend;
            float dy = CACHE_SCRATCH[1] * blend;
            float dz = CACHE_SCRATCH[2] * blend;

            if (crossfadeSnapshot != null) {
                float[] snap = crossfadeSnapshot[bone.ordinal()];
                if (snap != null) {
                    dx += snap[0] * crossfadeOut;
                    dy += snap[1] * crossfadeOut;
                    dz += snap[2] * crossfadeOut;
                }
            }
            float[] entry = cachedDeltas[bone.ordinal()];
            if (entry == null) {
                cachedDeltas[bone.ordinal()] = new float[]{dx, dy, dz};
            } else {
                entry[0] = dx; entry[1] = dy; entry[2] = dz;
            }
        }

        // Bake translation deltas — blend toward 0 naturally as blend-out progresses
        if (animation.hasTranslations) {
            for (BoneTarget bone : animation.blendMask) {
                animation.evaluateTranslation(bone, evalTick, CACHE_SCRATCH);
                float dx = CACHE_SCRATCH[0] * blend;
                float dy = CACHE_SCRATCH[1] * blend;
                float dz = CACHE_SCRATCH[2] * blend;
                float[] entry = cachedTranslations[bone.ordinal()];
                if (entry == null) {
                    cachedTranslations[bone.ordinal()] = new float[]{dx, dy, dz};
                } else {
                    entry[0] = dx; entry[1] = dy; entry[2] = dz;
                }
            }
        }
    }
}
