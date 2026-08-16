package com.bromax.bromaxlib.animation;

public enum EasingFunction {
    LINEAR {
        @Override public float apply(float t) { return t; }
    },
    EASE_IN_QUAD {
        @Override public float apply(float t) { return t * t; }
    },
    EASE_OUT_QUAD {
        @Override public float apply(float t) { return t * (2 - t); }
    },
    EASE_IN_OUT_QUAD {
        @Override public float apply(float t) {
            return t < 0.5f ? 2 * t * t : -1 + (4 - 2 * t) * t;
        }
    },
    EASE_IN_CUBIC {
        @Override public float apply(float t) { return t * t * t; }
    },
    EASE_OUT_CUBIC {
        @Override public float apply(float t) {
            float f = t - 1;
            return f * f * f + 1;
        }
    },
    EASE_IN_OUT_CUBIC {
        @Override public float apply(float t) {
            return t < 0.5f ? 4 * t * t * t : (t - 1) * (2 * t - 2) * (2 * t - 2) + 1;
        }
    },
    // Sharp wind-up then snap — good for heavy weapons
    EASE_IN_BACK {
        @Override public float apply(float t) {
            float c = 1.70158f;
            return t * t * ((c + 1) * t - c);
        }
    },
    // Overshoot then settle — good for impact recoil
    EASE_OUT_BACK {
        @Override public float apply(float t) {
            float c = 1.70158f;
            float f = t - 1;
            return f * f * ((c + 1) * f + c) + 1;
        }
    };

    public abstract float apply(float t);
}
