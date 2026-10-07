package com.bromax.bromaxlib.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.ResourceLocation;

import java.util.*;

public class AnimationDefinition {
    public final ResourceLocation    id;
    public final int                 duration;
    public final Set<BoneTarget>     blendMask;
    public final int                 hitWindowStart;
    public final int                 hitWindowEnd;
    public final boolean             hasTrail;
    public final boolean             hasTranslations;
    public final float               lunge;
    public final WeaponGrip          grip;
    public final float               gripScale;

    private final Map<BoneTarget, List<Keyframe>> keyframesByBone;

    public AnimationDefinition(ResourceLocation id, int duration, Set<BoneTarget> blendMask,
                               int hitWindowStart, int hitWindowEnd, boolean hasTrail,
                               float lunge, WeaponGrip grip, float gripScale, List<Keyframe> keyframes) {
        this.id             = id;
        this.duration       = duration;
        this.blendMask      = Collections.unmodifiableSet(blendMask);
        this.hitWindowStart = hitWindowStart;
        this.hitWindowEnd   = hitWindowEnd;
        this.hasTrail       = hasTrail;
        this.lunge          = Math.max(0f, Math.min(1f, lunge));
        this.grip           = grip != null ? grip : WeaponGrip.VANILLA;
        this.gripScale      = Math.max(0f, Math.min(2f, gripScale));

        this.keyframesByBone = new EnumMap<>(BoneTarget.class);
        boolean anyTranslation = false;
        for (Keyframe kf : keyframes) {
            this.keyframesByBone.computeIfAbsent(kf.bone, k -> new ArrayList<>()).add(kf);
            if (kf.tx != 0f || kf.ty != 0f || kf.tz != 0f) anyTranslation = true;
        }
        this.hasTranslations = anyTranslation;
        for (List<Keyframe> list : this.keyframesByBone.values()) {
            list.sort(Comparator.comparingInt(k -> k.tick));
        }
    }

    /** Keyframes for one bone, in tick order (empty when the bone isn't animated). */
    public List<Keyframe> keyframes(BoneTarget bone) {
        List<Keyframe> frames = keyframesByBone.get(bone);
        return frames == null ? Collections.emptyList() : Collections.unmodifiableList(frames);
    }

    /** Keyframe ticks for one bone, in order (empty when the bone isn't animated). */
    public List<Integer> keyframeTicks(BoneTarget bone) {
        List<Keyframe> frames = keyframesByBone.get(bone);
        if (frames == null) return Collections.emptyList();
        List<Integer> out = new ArrayList<>();
        for (Keyframe kf : frames) out.add(kf.tick);
        return out;
    }

    /** Share of the final pose eased off between the last keyframe and the end of the animation. */
    private static final float SETTLE = 0.25f;

    /**
     * Most animations stop at their last keyframe and hold it for 15-40% of their length before
     * the blend-out starts, which reads as the figure freezing after the hit. Past the last
     * keyframe the pose now eases a little toward neutral so the recovery keeps moving.
     */
    private float settle(Keyframe last, float tick) {
        float span = duration - last.tick;
        if (span <= 0f) return 1f;
        float t = Math.max(0f, Math.min(1f, (tick - last.tick) / span));
        return 1f - SETTLE * t * t * (3f - 2f * t);
    }

    public boolean isHitWindowActive(float tick) {
        return tick >= hitWindowStart && tick <= hitWindowEnd;
    }

    /** Writes interpolated [rx, ry, rz] in radians into {@code out}. Pass a float[3]. */
    public void evaluate(BoneTarget bone, float tick, float[] out) {
        if (!Float.isFinite(tick)) tick = 0f;
        else tick = Math.max(0f, Math.min(tick, duration));

        List<Keyframe> frames = keyframesByBone.get(bone);
        if (frames == null || frames.isEmpty()) { out[0] = out[1] = out[2] = 0f; return; }

        Keyframe before = null, after = null;
        for (Keyframe kf : frames) {
            if (kf.tick <= tick) before = kf;
            if (kf.tick >= tick && after == null) after = kf;
        }

        if (before == null)               { out[0] = after.rx;  out[1] = after.ry;  out[2] = after.rz;  return; }
        if (after == null) { float k = settle(before, tick); out[0] = before.rx * k; out[1] = before.ry * k; out[2] = before.rz * k; return; }
        if (before == after) { out[0] = before.rx; out[1] = before.ry; out[2] = before.rz; return; }

        float t = (tick - before.tick) / (float)(after.tick - before.tick);
        float e = before.easing.apply(t);
        out[0] = lerp(before.rx, after.rx, e);
        out[1] = lerp(before.ry, after.ry, e);
        out[2] = lerp(before.rz, after.rz, e);
    }

    /** Writes interpolated [tx, ty, tz] in model pixels into {@code out}. Pass a float[3]. */
    public void evaluateTranslation(BoneTarget bone, float tick, float[] out) {
        if (!Float.isFinite(tick)) tick = 0f;
        else tick = Math.max(0f, Math.min(tick, duration));

        List<Keyframe> frames = keyframesByBone.get(bone);
        if (frames == null || frames.isEmpty()) { out[0] = out[1] = out[2] = 0f; return; }

        Keyframe before = null, after = null;
        for (Keyframe kf : frames) {
            if (kf.tick <= tick) before = kf;
            if (kf.tick >= tick && after == null) after = kf;
        }

        if (before == null)               { out[0] = after.tx;  out[1] = after.ty;  out[2] = after.tz;  return; }
        if (after == null) { float k = settle(before, tick); out[0] = before.tx * k; out[1] = before.ty * k; out[2] = before.tz * k; return; }
        if (before == after) { out[0] = before.tx; out[1] = before.ty; out[2] = before.tz; return; }

        float t = (tick - before.tick) / (float)(after.tick - before.tick);
        float e = before.easing.apply(t);
        out[0] = lerp(before.tx, after.tx, e);
        out[1] = lerp(before.ty, after.ty, e);
        out[2] = lerp(before.tz, after.tz, e);
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    public static AnimationDefinition fromJson(ResourceLocation id, JsonObject json) {
        if (!json.has("duration"))
            throw new IllegalArgumentException("Missing 'duration' in " + id);
        int duration = json.get("duration").getAsInt();
        if (duration <= 0)
            throw new IllegalArgumentException("'duration' must be > 0 in " + id);

        boolean hasTrail = json.has("trail");
        float lunge = json.has("lunge") ? Math.max(0f, Math.min(1f, json.get("lunge").getAsFloat())) : 0f;
        WeaponGrip grip = json.has("grip") ? WeaponGrip.fromString(json.get("grip").getAsString()) : WeaponGrip.VANILLA;
        float gripScale = json.has("grip_scale") ? json.get("grip_scale").getAsFloat() : 1.0f;

        int hitStart = 0, hitEnd = 0;
        if (json.has("hit_window")) {
            JsonObject hw = json.getAsJsonObject("hit_window");
            hitStart = hw.get("start").getAsInt();
            hitEnd   = hw.get("end").getAsInt();
        }

        Set<BoneTarget> blendMask;
        if (json.has("blend_mask")) {
            blendMask = EnumSet.noneOf(BoneTarget.class);
            for (JsonElement e : json.getAsJsonArray("blend_mask")) {
                try { blendMask.add(BoneTarget.valueOf(e.getAsString().toUpperCase())); }
                catch (IllegalArgumentException ignored) {}
            }
            if (blendMask.isEmpty()) blendMask = EnumSet.of(BoneTarget.RIGHT_ARM);
        } else {
            blendMask = EnumSet.of(BoneTarget.RIGHT_ARM);
        }

        if (!json.has("keyframes"))
            return new AnimationDefinition(id, duration, blendMask, hitStart, hitEnd, hasTrail,
                    lunge, grip, gripScale, Collections.emptyList());

        List<Keyframe> keyframes = new ArrayList<>();
        for (JsonElement kfElem : json.getAsJsonArray("keyframes")) {
            try {
                JsonObject kf = kfElem.getAsJsonObject();
                int        tick  = kf.get("tick").getAsInt();
                BoneTarget bone  = BoneTarget.valueOf(kf.get("bone").getAsString().toUpperCase());
                float rx = kf.has("rx") ? kf.get("rx").getAsFloat() : 0f;
                float ry = kf.has("ry") ? kf.get("ry").getAsFloat() : 0f;
                float rz = kf.has("rz") ? kf.get("rz").getAsFloat() : 0f;
                float tx = kf.has("tx") ? kf.get("tx").getAsFloat() : 0f;
                float ty = kf.has("ty") ? kf.get("ty").getAsFloat() : 0f;
                float tz = kf.has("tz") ? kf.get("tz").getAsFloat() : 0f;
                EasingFunction easing = EasingFunction.LINEAR;
                if (kf.has("easing")) {
                    try { easing = EasingFunction.valueOf(kf.get("easing").getAsString().toUpperCase()); }
                    catch (IllegalArgumentException ignored) {}
                }
                // JSON stores degrees; convert to radians for ModelPart
                keyframes.add(new Keyframe(tick, bone,
                    (float) Math.toRadians(rx), (float) Math.toRadians(ry), (float) Math.toRadians(rz),
                    tx, ty, tz, easing));
            } catch (Exception ignored) {}
        }

        return new AnimationDefinition(id, duration, blendMask, hitStart, hitEnd, hasTrail, lunge, grip, gripScale, keyframes);
    }
}
