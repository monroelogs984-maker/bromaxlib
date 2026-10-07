package com.bromax.bromaxlib.animation;

/**
 * Minimal quaternion for 1.12.2 (no JOML, GL matrix stack).
 * Mirrors the JOML Quaternionf operations the newer branches use, with the same conventions
 * (rotationZYX = Rz * Ry * Rx, getEulerAnglesZYX as its inverse), so the grip and torso math
 * ports unchanged. Operations return new instances. Shared with the 1.19.2 BHB branch.
 */
public final class Quat {
    public final float x, y, z, w;

    public static final Quat IDENTITY = new Quat(0f, 0f, 0f, 1f);

    public Quat(float x, float y, float z, float w) {
        this.x = x; this.y = y; this.z = z; this.w = w;
    }

    /** Rotation of {@code radians} about the axis (normalized here). */
    public static Quat axisAngle(float radians, float ax, float ay, float az) {
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-8f) return IDENTITY;
        float s = (float) Math.sin(radians * 0.5) / len;
        return new Quat(ax * s, ay * s, az * s, (float) Math.cos(radians * 0.5));
    }

    /** Same as JOML rotationZYX(z, y, x): rotate about X, then Y, then Z. */
    public static Quat rotationZYX(float zRot, float yRot, float xRot) {
        return axisAngle(zRot, 0f, 0f, 1f).mul(axisAngle(yRot, 0f, 1f, 0f)).mul(axisAngle(xRot, 1f, 0f, 0f));
    }

    /** this * q */
    public Quat mul(Quat q) {
        return new Quat(
            w * q.x + x * q.w + y * q.z - z * q.y,
            w * q.y - x * q.z + y * q.w + z * q.x,
            w * q.z + x * q.y - y * q.x + z * q.w,
            w * q.w - x * q.x - y * q.y - z * q.z);
    }

    public Quat conjugate() {
        return new Quat(-x, -y, -z, w);
    }

    /** Spherical interpolation from this toward {@code target}, shortest path. */
    public Quat slerp(Quat target, float alpha) {
        float cos = x * target.x + y * target.y + z * target.z + w * target.w;
        float tx = target.x, ty = target.y, tz = target.z, tw = target.w;
        if (cos < 0f) { cos = -cos; tx = -tx; ty = -ty; tz = -tz; tw = -tw; }
        float a, b;
        if (1f - cos > 1e-6f) {
            float angle = (float) Math.acos(cos);
            float sin = (float) Math.sin(angle);
            a = (float) Math.sin((1f - alpha) * angle) / sin;
            b = (float) Math.sin(alpha * angle) / sin;
        } else {
            a = 1f - alpha;
            b = alpha;
        }
        return new Quat(a * x + b * tx, a * y + b * ty, a * z + b * tz, a * w + b * tw).normalize();
    }

    public Quat normalize() {
        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        return len < 1e-8f ? IDENTITY : new Quat(x / len, y / len, z / len, w / len);
    }

    /** Rotates the vector; returns {x, y, z}. */
    public float[] transform(float vx, float vy, float vz) {
        // v' = v + 2w(q x v) + 2 q x (q x v)
        float cx = y * vz - z * vy, cy = z * vx - x * vz, cz = x * vy - y * vx;
        float ccx = y * cz - z * cy, ccy = z * cx - x * cz, ccz = x * cy - y * cx;
        return new float[]{vx + 2f * (w * cx + ccx), vy + 2f * (w * cy + ccy), vz + 2f * (w * cz + ccz)};
    }

    /** Euler angles {x, y, z} such that rotationZYX(z, y, x) reproduces this rotation (JOML getEulerAnglesZYX). */
    public float[] eulerZYX() {
        float ex = (float) Math.atan2(y * z + w * x, 0.5f - x * x - y * y);
        float sy = -2f * (x * z - w * y);
        float ey = (float) Math.asin(Math.max(-1f, Math.min(1f, sy)));
        float ez = (float) Math.atan2(x * y + w * z, 0.5f - y * y - z * z);
        return new float[]{ex, ey, ez};
    }

    /** Multiplies the current GL matrix by this rotation (as axis-angle). */
    public void applyGl() {
        float cw = Math.max(-1f, Math.min(1f, w));
        float angle = 2f * (float) Math.acos(cw);
        float s = (float) Math.sqrt(1f - cw * cw);
        if (s < 1e-5f || angle < 1e-5f) return;
        net.minecraft.client.renderer.GlStateManager.rotate((float) Math.toDegrees(angle), x / s, y / s, z / s);
    }
}
