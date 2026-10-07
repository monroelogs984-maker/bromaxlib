package com.bromax.bromaxlib.animation;

public enum BoneTarget {
    HEAD,
    BODY,
    RIGHT_ARM,
    LEFT_ARM,
    RIGHT_LEG,
    LEFT_LEG,
    /** Weapon held in the main hand — applied by MixinItemInHandLayer, not to model bones directly. */
    WEAPON_HAND;

    public static final int COUNT = values().length;
}
