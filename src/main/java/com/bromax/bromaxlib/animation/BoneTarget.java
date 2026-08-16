package com.bromax.bromaxlib.animation;

public enum BoneTarget {
    HEAD,
    BODY,
    RIGHT_ARM,
    LEFT_ARM,
    RIGHT_LEG,
    LEFT_LEG,
    /** Weapon held in the main hand — read by MixinLayerHeldItem, not applied to model bones. */
    WEAPON_HAND;

    /** Cached ordinal count — avoids allocating a new array on every BoneTarget.values() call. */
    public static final int COUNT = values().length;
}
