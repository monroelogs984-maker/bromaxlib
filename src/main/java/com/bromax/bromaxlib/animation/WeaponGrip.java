package com.bromax.bromaxlib.animation;


/**
 * Weapon ENDING orientation for an animation, set via the top-level "grip"
 * field in the animation JSON. The weapon rotates from the vanilla hold into
 * this pose over the windup (full by hit-window start), holds through the
 * strike, then blends back out.
 *
 * Rotations are composed around the item's own geometry (applied at the item
 * render origin, so the weapon stays in the hand):
 *   BLADE  = the item's long axis (tools run corner-to-corner, diagonal in the quad plane)
 *   EDGE   = in-plane axis perpendicular to the blade
 *   NORMAL = the quad normal
 *
 *  vanilla — no rotation (default)
 *  forward — blade swings out of its plane to point straight ahead
 *  stab    — forward, then spun 90° around its own shaft (door-handle roll)
 *  slash   — blade laid horizontal in-plane, then rolled flat around its shaft
 *  down    — blade rotated in-plane to point downward (chop enders)
 *  up      — blade rotated in-plane to point straight up (rising strike enders)
 *  thrust  — blade straight out of the fist along the forearm, whatever the arm pose.
 *            Computed, not tuned: in the hand frame (after ItemInHandLayer's X-90/Y180) the
 *            forearm runs along -Z, and the handheld display transform (0,-90,55) leaves the
 *            blade at about (0, 0.985, -0.17), so a -80° turn about X lines them up. The
 *            forward/stab presets assume the hanging vanilla arm and point back on a thrust.
 */
public enum WeaponGrip {
    VANILLA(null),
    FORWARD(axis(90, EDGE())),
    STAB(axis(90, Z()).mul(axis(90, EDGE()))),
    SLASH(axis(90, X()).mul(axis(-45, NORMAL()))),
    DOWN(axis(135, NORMAL())),
    UP(axis(-45, NORMAL())),
    THRUST(axis(-80, X()));

    /** Target rotation, or null for identity. */
    public final Quat rotation;

    WeaponGrip(Quat rotation) {
        this.rotation = rotation;
    }

    private static float[] EDGE()   { return new float[]{1f, -1f, 0f}; }
    private static float[] NORMAL() { return new float[]{0f, 0f, 1f}; }
    private static float[] X()      { return new float[]{1f, 0f, 0f}; }
    private static float[] Z()      { return new float[]{0f, 0f, 1f}; }

    private static Quat axis(float deg, float[] axis) {
        return Quat.axisAngle((float) Math.toRadians(deg), axis[0], axis[1], axis[2]);
    }

    public static WeaponGrip fromString(String s) {
        if (s == null) return VANILLA;
        try {
            return valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VANILLA;
        }
    }
}
