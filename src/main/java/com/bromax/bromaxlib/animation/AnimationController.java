package com.bromax.bromaxlib.animation;

import com.bromax.bromaxlib.BromaxLib;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@SideOnly(Side.CLIENT)
public class AnimationController {
    public static final AnimationController INSTANCE = new AnimationController();

    private final Map<UUID, AnimationState> states        = new ConcurrentHashMap<>();
    private final Map<UUID, AnimationState> offhandStates = new ConcurrentHashMap<>();
    private final Map<UUID, AnimationState> idleStates    = new ConcurrentHashMap<>();
    private final AtomicBoolean mixinConfirmed = new AtomicBoolean(false);

    // FPS cap: only advance the interpolation value 40 times per second.
    // applyToModelUnsafe still runs every frame so bones are always set and
    // vanilla's walking animation can't bleed through on skipped frames.
    private final Map<UUID, Long>  lastInterpNanos   = new ConcurrentHashMap<>();
    private final Map<UUID, Float> cachedPartialTick = new ConcurrentHashMap<>();
    private static final long INTERP_INTERVAL_NS = 1_000_000_000L / 40; // 25 ms

    // Per-instance scratch arrays — avoids static state corruption if two controller
    // instances ever exist, and makes threading intent explicit.
    private final float[] EVAL_SCRATCH   = new float[3];
    private final float[] INTERP_SCRATCH = new float[3];
    private final float[] TRANS_SCRATCH  = new float[3];

    // Max lunge contribution at lunge=1.0 luCurve=1.0. All in radians.
    // Body lean: 55° forward. Arm push: 45° more forward. Front leg: 55° forward. Back leg: 65° back.
    private static final float LUNGE_BODY_RX     = (float) Math.toRadians( 55f);
    private static final float LUNGE_ARM_RX      = (float) Math.toRadians(-45f);
    private static final float LUNGE_FRONT_LEG_RX = (float) Math.toRadians(-55f);
    private static final float LUNGE_BACK_LEG_RX  = (float) Math.toRadians( 65f);
    private static final float LUNGE_OFFARM_RX   = (float) Math.toRadians( 18f);

    // Velocity impulse scale: lunge=1.0 → 0.30 blocks/tick forward push.
    public static final float LUNGE_VELOCITY_SCALE = 0.30f;

    // Vanilla held-item base for the right arm: ModelPlayer.setRotationAngles applies
    // arm.rx = arm.rx * 0.5f - PI/10 for ArmPose.ITEM. For a standing idle player,
    // arm.rx is ~0 before ModelPlayer runs, so the final base ≈ -PI/10 ≈ -0.314 rad.
    private static final float VANILLA_RIGHT_ARM_RX = -(float)(Math.PI / 10.0);

    // Vanilla rotationPoint baselines per bone. Translations are additive on top of these.
    // As blend → 0 at animation end, rotationPoint naturally returns to vanilla.
    private static final float[] VANILLA_RP_X = new float[BoneTarget.COUNT];
    private static final float[] VANILLA_RP_Y = new float[BoneTarget.COUNT];
    private static final float[] VANILLA_RP_Z = new float[BoneTarget.COUNT];
    static {
        // HEAD and BODY pivot at origin
        // RIGHT_ARM / LEFT_ARM: X = ±5, Y = 2
        // RIGHT_LEG / LEFT_LEG: X = ±1.9, Y = 12
        VANILLA_RP_X[BoneTarget.RIGHT_ARM.ordinal()] = -5f;   VANILLA_RP_Y[BoneTarget.RIGHT_ARM.ordinal()] = 2f;
        VANILLA_RP_X[BoneTarget.LEFT_ARM.ordinal()]  =  5f;   VANILLA_RP_Y[BoneTarget.LEFT_ARM.ordinal()]  = 2f;
        VANILLA_RP_X[BoneTarget.RIGHT_LEG.ordinal()] = -1.9f; VANILLA_RP_Y[BoneTarget.RIGHT_LEG.ordinal()] = 12f;
        VANILLA_RP_X[BoneTarget.LEFT_LEG.ordinal()]  =  1.9f; VANILLA_RP_Y[BoneTarget.LEFT_LEG.ordinal()]  = 12f;
        // HEAD, BODY, WEAPON_HAND all default to 0,0,0 — Java zero-initializes the arrays
    }

    public void play(UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        if (playerId == null || animation == null) return;
        // Clamp speed — zero or negative would cause the animation to never complete
        // (tickF never advances), leaking the state in the map until the player logs out.
        // Values above 5x would skip most keyframes on a 20fps tick rate.
        if (!Float.isFinite(speedMultiplier) || speedMultiplier < 0.1f) speedMultiplier = 0.1f;
        if (speedMultiplier > 5.0f) speedMultiplier = 5.0f;
        AnimationState prev = states.get(playerId);
        float[][] snapshot = null;

        if (prev != null) {
            float prevTick = Math.min(prev.tickF, prev.animation.duration);
            snapshot = new float[BoneTarget.COUNT][];
            for (BoneTarget bone : prev.animation.blendMask) {
                prev.animation.evaluate(bone, prevTick, EVAL_SCRATCH);
                float blend = prev.getBlendFactor(prevTick);
                snapshot[bone.ordinal()] = new float[]{
                    EVAL_SCRATCH[0] * blend, EVAL_SCRATCH[1] * blend, EVAL_SCRATCH[2] * blend
                };
            }
        }

        states.put(playerId, new AnimationState(animation, speedMultiplier, snapshot));
    }

    /**
     * Plays an animation mirrored for the offhand (left arm becomes the primary
     * attack arm). Same animation file — ry/rz are negated and arm bones swapped
     * at apply time, so no separate animation assets are needed.
     */
    public void playOffhand(UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        if (playerId == null || animation == null) return;
        if (!Float.isFinite(speedMultiplier) || speedMultiplier < 0.1f) speedMultiplier = 0.1f;
        if (speedMultiplier > 5.0f) speedMultiplier = 5.0f;
        offhandStates.put(playerId, new AnimationState(animation, speedMultiplier, null));
    }

    public void tickAll() {
        tickMap(states);
        tickMap(offhandStates);
        tickMap(idleStates);
    }

    private static void tickMap(Map<UUID, AnimationState> map) {
        map.entrySet().removeIf(entry -> {
            AnimationState state = entry.getValue();
            state.tickF += state.speedMultiplier;
            if (state.isComplete()) return true;
            if (state.tickF > state.animation.duration * 4f) return true;
            state.refreshCache();
            return false;
        });
    }

    public boolean isHitWindowActive(UUID playerId) {
        AnimationState state = states.get(playerId);
        return state != null && state.animation.isHitWindowActive(state.tickF);
    }

    /** True if the mainhand animation's main body is still playing. Returns false
     *  during the tail blend-out so the player can queue the next attack. */
    public boolean isPlaying(UUID playerId) {
        AnimationState s = states.get(playerId);
        return s != null && !s.isMainBodyComplete();
    }

    /** True if the offhand mirrored animation's main body is still playing. */
    public boolean isOffhandPlaying(UUID playerId) {
        AnimationState s = offhandStates.get(playerId);
        return s != null && !s.isMainBodyComplete();
    }

    /** True if any animation state (attack or idle, including blend-out) is active for this player.
     *  Used by the render mixin so blend-out frames are actually applied to bones. */
    public boolean hasActiveState(UUID playerId) {
        return states.containsKey(playerId) || offhandStates.containsKey(playerId) || idleStates.containsKey(playerId);
    }

    /**
     * Re-applies the arm bone animation for first-person rendering.
     *
     * RenderPlayer.renderRightArm / renderLeftArm reset bipedRightArm.rotateAngleX = 0
     * AFTER setRotationAngles runs, just before calling render(). This method is called
     * by MixinRenderPlayer immediately before that render() call to restore the correct
     * animation values that the setRotationAngles reset destroyed.
     *
     * @param model   the player's ModelPlayer — arm renderers are read from this
     * @param isLeft  true for left arm, false for right arm
     */
    public void applyFirstPersonArm(UUID playerId, net.minecraft.client.model.ModelPlayer model,
                                     boolean isLeft, float partialTick) {
        AnimationState state = states.get(playerId);
        if (state == null || state.cachedDeltas == null) return;

        BoneTarget bone = isLeft ? BoneTarget.LEFT_ARM : BoneTarget.RIGHT_ARM;
        if (!state.animation.blendMask.contains(bone)) return;

        float[] cur = state.cachedDeltas[bone.ordinal()];
        if (cur == null) return;

        if (!Float.isFinite(partialTick)) partialTick = 1f;
        else partialTick = Math.max(0f, Math.min(1f, partialTick));

        float rx, ry, rz;
        float[] prev = state.prevCachedDeltas != null ? state.prevCachedDeltas[bone.ordinal()] : null;
        if (prev != null) {
            rx = prev[0] + (cur[0] - prev[0]) * partialTick;
            ry = prev[1] + (cur[1] - prev[1]) * partialTick;
            rz = prev[2] + (cur[2] - prev[2]) * partialTick;
        } else {
            rx = cur[0]; ry = cur[1]; rz = cur[2];
        }

        ModelRenderer arm = isLeft ? model.bipedLeftArm : model.bipedRightArm;

        float finalRx = isLeft ? rx : VANILLA_RIGHT_ARM_RX + rx;
        arm.rotateAngleX = finalRx;
        arm.rotateAngleY = ry;
        arm.rotateAngleZ = rz;
        // armwear.rotateAngleX is reset to 0 again after this injection point,
        // so we don't set it here; ry/rz on armwear are already correct from
        // the setRotationAngles pass in MixinModelPlayer.
    }

    public void applyToModel(UUID playerId, ModelBiped model, float partialTick) {
        if (mixinConfirmed.compareAndSet(false, true)) {
            BromaxLib.LOGGER.info("[BROMAX] ModelPlayer mixin confirmed — animation engine active.");
        }
        // applyToModel is called every render frame. The state remains in the map
        // during blend-out (tickF >= duration, not yet isComplete), so we read
        // directly from the map rather than going through isPlaying().
        AnimationState mainhand = states.get(playerId);
        AnimationState offhand  = offhandStates.get(playerId);
        if (mainhand == null && offhand == null) return;

        if (!Float.isFinite(partialTick)) partialTick = 1.0f;
        else partialTick = Math.max(0f, Math.min(1f, partialTick));

        // Advance the interpolation value at most 40 times per second.
        // Between updates we reuse the last cached value so bones are still
        // written every frame — vanilla's walk animation can't bleed through.
        long now = System.nanoTime();
        Long lastNs = lastInterpNanos.get(playerId);
        if (lastNs == null || (now - lastNs) >= INTERP_INTERVAL_NS) {
            lastInterpNanos.put(playerId, now);
            cachedPartialTick.put(playerId, partialTick);
        } else {
            partialTick = cachedPartialTick.getOrDefault(playerId, partialTick);
        }

        AnimationState idle = idleStates.get(playerId);

        try {
            // Idle applies only when no attack is running — avoids addDelta conflicts on WEAPON_HAND
            if (mainhand == null && offhand == null && idle != null && idle.cachedDeltas != null) {
                applyToModelUnsafe(idle, model, partialTick, false, false, true);
            }
            if (mainhand != null && mainhand.cachedDeltas != null) {
                applyToModelUnsafe(mainhand, model, partialTick, false, false, false);
            }
            if (offhand != null && offhand.cachedDeltas != null) {
                applyToModelUnsafe(offhand, model, partialTick, true, mainhand != null, false);
            }
        } catch (Exception e) {
            BromaxLib.LOGGER.error("[BROMAX] Animation render error for {}, clearing: {}", playerId, e.getMessage());
            states.remove(playerId);
            offhandStates.remove(playerId);
            idleStates.remove(playerId);
        }
    }

    /**
     * @param mirror   — true for offhand: swap arm bones and negate ry/rz to produce
     *                   a left-hand mirror of any right-hand animation.
     * @param skipBody — true when mainhand is also active so offhand doesn't fight
     *                   the mainhand for control of the body/legs/head.
     */
    private void applyToModelUnsafe(AnimationState state, ModelBiped model,
                                     float partialTick, boolean mirror, boolean skipBody,
                                     boolean skipHeadLock) {
        if (state.cachedDeltas == null) return;

        boolean isPlayerModel = model instanceof ModelPlayer;

        // Head locking — only for attack animations, not idle
        if (!skipBody && !skipHeadLock) {
            ModelRenderer head     = model.bipedHead;
            ModelRenderer headwear = isPlayerModel ? ((ModelPlayer) model).bipedHeadwear : null;
            if (head != null) {
                if (Float.isNaN(state.lockedHeadRX)) {
                    state.lockedHeadRX = head.rotateAngleX;
                    state.lockedHeadRY = head.rotateAngleY;
                }
                head.rotateAngleX = state.lockedHeadRX;
                head.rotateAngleY = state.lockedHeadRY;
                if (headwear != null) {
                    headwear.rotateAngleX = state.lockedHeadRX;
                    headwear.rotateAngleY = state.lockedHeadRY;
                }
            }
        }

        for (BoneTarget bone : state.animation.blendMask) {
            // Skip body/legs/head for offhand when mainhand is also running
            if (skipBody && bone != BoneTarget.RIGHT_ARM && bone != BoneTarget.LEFT_ARM) continue;

            float[] cur  = state.cachedDeltas[bone.ordinal()];
            if (cur == null) continue;
            float[] prev = state.prevCachedDeltas != null ? state.prevCachedDeltas[bone.ordinal()] : null;
            final float[] c;
            if (prev != null) {
                INTERP_SCRATCH[0] = prev[0] + (cur[0] - prev[0]) * partialTick;
                INTERP_SCRATCH[1] = prev[1] + (cur[1] - prev[1]) * partialTick;
                INTERP_SCRATCH[2] = prev[2] + (cur[2] - prev[2]) * partialTick;
                c = INTERP_SCRATCH;
            } else {
                c = cur;
            }

            // mirror swaps LEFT↔RIGHT bone assignments so offhand uses the correct renderer
            ModelRenderer main = getMainRenderer(model, bone, mirror);
            ModelRenderer wear = isPlayerModel ? getWearRenderer((ModelPlayer) model, bone, mirror) : null;

            switch (bone) {
                case RIGHT_LEG:
                case LEFT_LEG:
                    setRotation(main, c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    setRotation(wear, c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    break;

                case RIGHT_ARM:
                    // mirror: RIGHT_ARM values → bipedLeftArm, negate ry/rz
                    setRotation(main, VANILLA_RIGHT_ARM_RX + c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    setRotation(wear, VANILLA_RIGHT_ARM_RX + c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    break;

                case LEFT_ARM:
                    // mirror: LEFT_ARM values (counterbalance) → bipedRightArm, negate ry/rz
                    setRotation(main, c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    setRotation(wear, c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    break;

                case BODY:
                    if (Float.isNaN(state.lockedBodyRY) && main != null) {
                        state.lockedBodyRY = main.rotateAngleY;
                    }
                    float bodyRY = Float.isNaN(state.lockedBodyRY) ? 0f : state.lockedBodyRY;
                    // mirror: negate rz so body leans toward the active hand
                    setBodyLean(main, c[0], bodyRY, mirror ? -c[2] : c[2]);
                    setBodyLean(wear, c[0], bodyRY, mirror ? -c[2] : c[2]);
                    break;

                default:
                    addDelta(main, c[0], c[1], c[2]);
                    addDelta(wear, c[0], c[1], c[2]);
                    break;
            }
        }

        // Lunge visual contribution — additive on top of whatever the base animation set.
        // Bell-curve progress: peaks at 50% of the animation, zero at start and end.
        // This means the full body surge is visible at the strike moment, gone by recovery.
        if (!skipHeadLock && state.animation.lunge > 0f) {
            float d = state.animation.duration;
            float swingProg = d > 0 ? Math.min(1f, state.tickF / d) : 0f;
            // sin(π·p): 0 at p=0, 1.0 at p=0.5, 0 at p=1.0
            float luCurve = (float) Math.sin(Math.PI * swingProg);
            float lu = state.animation.lunge * luCurve * state.getBlendFactor(state.tickF);

            if (lu > 0.001f) {
                // Body: lean forward
                ModelRenderer body = model.bipedBody;
                if (body != null) body.rotateAngleX += lu * LUNGE_BODY_RX;

                // Arm: dramatic push forward (already extended by attack anim; lunge adds more)
                ModelRenderer strikeArm = mirror ? model.bipedLeftArm : model.bipedRightArm;
                if (strikeArm != null) strikeArm.rotateAngleX += lu * LUNGE_ARM_RX;

                // Legs: stride — strike-side leg forward, back leg extends behind
                if (!skipBody) {
                    ModelRenderer frontLeg = mirror ? model.bipedLeftLeg  : model.bipedRightLeg;
                    ModelRenderer backLeg  = mirror ? model.bipedRightLeg : model.bipedLeftLeg;
                    if (frontLeg != null) frontLeg.rotateAngleX += lu * LUNGE_FRONT_LEG_RX;
                    if (backLeg  != null) backLeg.rotateAngleX  += lu * LUNGE_BACK_LEG_RX;
                }

                // Off-hand: slight counterbalance arm goes back
                ModelRenderer offArm = mirror ? model.bipedRightArm : model.bipedLeftArm;
                if (offArm != null) offArm.rotateAngleX += lu * LUNGE_OFFARM_RX;
            }
        }

        // Apply bone translation offsets (rotationPoint displacement).
        // cachedTranslations has blend already baked in, so at blend=0 this sets vanilla.
        if (state.animation.hasTranslations && state.cachedTranslations != null) {
            for (BoneTarget bone : state.animation.blendMask) {
                if (skipBody && bone != BoneTarget.RIGHT_ARM && bone != BoneTarget.LEFT_ARM) continue;
                if (bone == BoneTarget.WEAPON_HAND) continue;

                float[] tCur = state.cachedTranslations[bone.ordinal()];
                if (tCur == null) continue;

                float[] tPrev = state.prevCachedTranslations != null
                    ? state.prevCachedTranslations[bone.ordinal()] : null;
                if (tPrev != null) {
                    TRANS_SCRATCH[0] = tPrev[0] + (tCur[0] - tPrev[0]) * partialTick;
                    TRANS_SCRATCH[1] = tPrev[1] + (tCur[1] - tPrev[1]) * partialTick;
                    TRANS_SCRATCH[2] = tPrev[2] + (tCur[2] - tPrev[2]) * partialTick;
                } else {
                    TRANS_SCRATCH[0] = tCur[0];
                    TRANS_SCRATCH[1] = tCur[1];
                    TRANS_SCRATCH[2] = tCur[2];
                }

                int ord = bone.ordinal();
                ModelRenderer main = getMainRenderer(model, bone, mirror);
                ModelRenderer wear = isPlayerModel ? getWearRenderer((ModelPlayer) model, bone, mirror) : null;

                // For mirrored animations, flip X component (left↔right symmetry).
                // Vanilla RP_X is also negated since getMainRenderer already swapped the renderer.
                float sign  = mirror ? -1f : 1f;
                float rpX   = sign * (VANILLA_RP_X[ord] + TRANS_SCRATCH[0]);
                float rpY   = VANILLA_RP_Y[ord] + TRANS_SCRATCH[1];
                float rpZ   = VANILLA_RP_Z[ord] + TRANS_SCRATCH[2];
                applyTranslation(main, rpX, rpY, rpZ);
                applyTranslation(wear, rpX, rpY, rpZ);
            }
        }
    }

    /**
     * Returns interpolated [rx, ry, rz] in radians for the WEAPON_HAND bone of the
     * currently playing animation, or null if no animation is active or the animation
     * has no WEAPON_HAND keyframes. Called by MixinLayerHeldItem to rotate the held
     * item GL matrix independently of the arm bone.
     */
    public float[] getWeaponAngles(UUID playerId, boolean mainhand, float partialTick) {
        AnimationState state = mainhand ? states.get(playerId) : offhandStates.get(playerId);
        if (state == null || state.cachedDeltas == null) return null;

        int ord = BoneTarget.WEAPON_HAND.ordinal();
        float[] cur = state.cachedDeltas[ord];
        if (cur == null) return null;

        if (!Float.isFinite(partialTick)) partialTick = 1f;
        else partialTick = Math.max(0f, Math.min(1f, partialTick));

        float[] prev = state.prevCachedDeltas != null ? state.prevCachedDeltas[ord] : null;
        if (prev != null) {
            return new float[]{
                prev[0] + (cur[0] - prev[0]) * partialTick,
                prev[1] + (cur[1] - prev[1]) * partialTick,
                prev[2] + (cur[2] - prev[2]) * partialTick
            };
        }
        return new float[]{cur[0], cur[1], cur[2]};
    }

    /**
     * Called once per game tick by MixinEntityPlayerSP on the first tick of a new attack.
     * Returns the lunge impulse magnitude [0,1] the first time it is called for the current
     * animation state, then returns 0 for all subsequent calls. The caller multiplies this
     * by LUNGE_VELOCITY_SCALE to get the actual blocks/tick forward impulse.
     */
    public float consumeLungeImpulse(UUID playerId) {
        AnimationState s = states.get(playerId);
        if (s == null || s.lungeImpulseFired || s.animation.lunge <= 0f) return 0f;
        s.lungeImpulseFired = true;
        return s.animation.lunge;
    }

    /** Returns animation progress [0,1] for the mainhand attack, or 0 if none playing.
     *  Used by MixinEntityPlayerSP to smooth the movement restriction curve. */
    public float getAttackSwingProgress(UUID playerId) {
        AnimationState s = states.get(playerId);
        if (s == null) return 0f;
        float d = s.animation.duration;
        return d <= 0 ? 0f : Math.min(1f, s.tickF / d);
    }

    /** Starts an idle animation only if no idle is currently active.
     *  Uses a separate map from attack states so isPlaying() and spam-lockout never see it. */
    public void playIdleIfInactive(UUID playerId, AnimationDefinition animation) {
        if (playerId == null || animation == null) return;
        if (idleStates.containsKey(playerId)) return;
        idleStates.put(playerId, new AnimationState(animation, 1.0f, null));
    }

    /** Clears the idle animation for this player. Called when the player unequips the weapon. */
    public void clearIdle(UUID playerId) {
        idleStates.remove(playerId);
    }

    public void clear(UUID playerId) {
        states.remove(playerId);
        offhandStates.remove(playerId);
        idleStates.remove(playerId);
        lastInterpNanos.remove(playerId);
        cachedPartialTick.remove(playerId);
    }

    private static void applyTranslation(ModelRenderer r, float x, float y, float z) {
        if (r == null) return;
        r.rotationPointX = x;
        r.rotationPointY = y;
        r.rotationPointZ = z;
    }

    private static void setBodyLean(ModelRenderer r, float rx, float ry, float rz) {
        if (r == null) return;
        r.rotateAngleX = rx;
        r.rotateAngleY = ry;
        r.rotateAngleZ = rz;
    }

    private static void addDelta(ModelRenderer r, float dx, float dy, float dz) {
        if (r == null) return;
        r.rotateAngleX += dx;
        r.rotateAngleY += dy;
        r.rotateAngleZ += dz;
    }

    private static void setRotation(ModelRenderer r, float x, float y, float z) {
        if (r == null) return;
        r.rotateAngleX = x;
        r.rotateAngleY = y;
        r.rotateAngleZ = z;
    }

    private static ModelRenderer getMainRenderer(ModelBiped model, BoneTarget bone, boolean mirror) {
        switch (bone) {
            case HEAD:      return model.bipedHead;
            case BODY:      return model.bipedBody;
            case RIGHT_ARM: return mirror ? model.bipedLeftArm  : model.bipedRightArm;
            case LEFT_ARM:  return mirror ? model.bipedRightArm : model.bipedLeftArm;
            case RIGHT_LEG: return mirror ? model.bipedLeftLeg  : model.bipedRightLeg;
            case LEFT_LEG:  return mirror ? model.bipedRightLeg : model.bipedLeftLeg;
            default:        return null;
        }
    }

    private static ModelRenderer getWearRenderer(ModelPlayer model, BoneTarget bone, boolean mirror) {
        switch (bone) {
            case HEAD:      return model.bipedHeadwear;
            case BODY:      return model.bipedBodyWear;
            case RIGHT_ARM: return mirror ? model.bipedLeftArmwear  : model.bipedRightArmwear;
            case LEFT_ARM:  return mirror ? model.bipedRightArmwear : model.bipedLeftArmwear;
            case RIGHT_LEG: return mirror ? model.bipedLeftLegwear  : model.bipedRightLegwear;
            case LEFT_LEG:  return mirror ? model.bipedRightLegwear : model.bipedLeftLegwear;
            default:        return null;
        }
    }
}
