package com.bromax.bromaxlib.animation;

public class Keyframe {
    public final int tick;
    public final BoneTarget bone;
    /** Rotation deltas in radians, applied additively on top of vanilla pose. */
    public final float rx;
    public final float ry;
    public final float rz;
    /** Translation offsets in model pixels. Added to the bone's vanilla rotationPoint. */
    public final float tx;
    public final float ty;
    public final float tz;
    public final EasingFunction easing;

    public Keyframe(int tick, BoneTarget bone, float rx, float ry, float rz,
                    float tx, float ty, float tz, EasingFunction easing) {
        this.tick = tick;
        this.bone = bone;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
        this.easing = easing;
    }
}
