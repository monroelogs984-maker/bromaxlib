package com.bromax.bromaxlib.animation;

public class Keyframe {
    public final int          tick;
    public final BoneTarget   bone;
    /** Rotation deltas in radians, applied additively on top of vanilla pose. */
    public final float rx, ry, rz;
    /** Translation offsets in model pixels. Added to the bone's vanilla position. */
    public final float tx, ty, tz;
    public final EasingFunction easing;

    public Keyframe(int tick, BoneTarget bone,
                    float rx, float ry, float rz,
                    float tx, float ty, float tz,
                    EasingFunction easing) {
        this.tick   = tick;
        this.bone   = bone;
        this.rx = rx; this.ry = ry; this.rz = rz;
        this.tx = tx; this.ty = ty; this.tz = tz;
        this.easing = easing;
    }
}
