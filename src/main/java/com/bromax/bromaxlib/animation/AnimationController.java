package com.bromax.bromaxlib.animation;

import com.bromax.bromaxlib.BromaxLib;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelBase;
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

    /** How the left arm is driven while an animation plays. */
    public enum OffArmMode { TWO_HANDED, ONE_HANDED, AUTHORED }

    /**
     * Decides the off-arm mode per animation. BROMAX's Lib doesn't know weapon categories, so the
     * weapon mod installs this (BHB maps its two-handed/paired/ranged categories); until then every
     * animation keeps its authored left arm.
     */
    public static java.util.function.Function<AnimationDefinition, OffArmMode> offArmResolver = def -> OffArmMode.AUTHORED;

    private final Map<UUID, AnimationState> states        = new ConcurrentHashMap<>();
    private final Map<UUID, AnimationState> offhandStates = new ConcurrentHashMap<>();
    private final Map<UUID, AnimationState> idleStates    = new ConcurrentHashMap<>();
    private final AtomicBoolean             mixinConfirmed = new AtomicBoolean(false);

    private final Map<UUID, Long>  lastInterpNanos   = new ConcurrentHashMap<>();
    private final Map<UUID, Float> cachedPartialTick = new ConcurrentHashMap<>();
    private static final long INTERP_INTERVAL_NS = 1_000_000_000L / 40;

    private final float[] EVAL_SCRATCH   = new float[3];
    private final float[] INTERP_SCRATCH = new float[3];
    private final float[] TRANS_SCRATCH  = new float[3];

    private static final float LUNGE_BODY_RX      = (float) Math.toRadians( 55f);
    private static final float LUNGE_ARM_RX       = (float) Math.toRadians(-45f);
    private static final float LUNGE_FRONT_LEG_RX = (float) Math.toRadians(-55f);
    private static final float LUNGE_BACK_LEG_RX  = (float) Math.toRadians( 65f);
    private static final float LUNGE_OFFARM_RX    = (float) Math.toRadians( 18f);
    public  static final float LUNGE_VELOCITY_SCALE = 0.30f;

    // ModelBiped.setupAnim applies: rightArm.rotateAngleX = rightArm.rotateAngleX * 0.5 - PI/10 for ITEM pose
    private static final float VANILLA_RIGHT_ARM_RX = -(float)(Math.PI / 10.0);

    private static final float[] VANILLA_RP_X = new float[BoneTarget.COUNT];
    private static final float[] VANILLA_RP_Y = new float[BoneTarget.COUNT];
    private static final float[] VANILLA_RP_Z = new float[BoneTarget.COUNT];
    static {
        VANILLA_RP_X[BoneTarget.RIGHT_ARM.ordinal()] = -5f;   VANILLA_RP_Y[BoneTarget.RIGHT_ARM.ordinal()] = 2f;
        VANILLA_RP_X[BoneTarget.LEFT_ARM.ordinal()]  =  5f;   VANILLA_RP_Y[BoneTarget.LEFT_ARM.ordinal()]  = 2f;
        VANILLA_RP_X[BoneTarget.RIGHT_LEG.ordinal()] = -1.9f; VANILLA_RP_Y[BoneTarget.RIGHT_LEG.ordinal()] = 12f;
        VANILLA_RP_X[BoneTarget.LEFT_LEG.ordinal()]  =  1.9f; VANILLA_RP_Y[BoneTarget.LEFT_LEG.ordinal()]  = 12f;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void play(UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        if (playerId == null || animation == null) return;
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
     * Holds {@code animation} at exactly {@code tick} for this entity until replaced or cleared.
     * Used by the preview tool to render poses at chosen points in time.
     */
    public void pose(UUID playerId, AnimationDefinition animation, float tick) {
        pose(playerId, animation, tick, null);
    }

    /** As {@link #pose(UUID, AnimationDefinition, float)}, rendering with {@code grip} instead of the animation's own. */
    public void pose(UUID playerId, AnimationDefinition animation, float tick, WeaponGrip grip) {
        if (playerId == null || animation == null) return;
        AnimationState state = new AnimationState(animation, 1.0f, null);
        state.frozen = true;
        state.gripOverride = grip;
        state.tickF = Math.max(0f, tick);
        state.refreshCache();
        state.refreshCache(); // second pass makes prev == current, so no interpolation
        states.put(playerId, state);
        cachedPartialTick.put(playerId, 1.0f);
        lastInterpNanos.put(playerId, System.nanoTime());
    }

    public void playOffhand(UUID playerId, AnimationDefinition animation, float speedMultiplier) {
        if (playerId == null || animation == null) return;
        if (!Float.isFinite(speedMultiplier) || speedMultiplier < 0.1f) speedMultiplier = 0.1f;
        if (speedMultiplier > 5.0f) speedMultiplier = 5.0f;
        offhandStates.put(playerId, new AnimationState(animation, speedMultiplier, null));
    }

    public void playIdleIfInactive(UUID playerId, AnimationDefinition animation) {
        if (playerId == null || animation == null) return;
        if (idleStates.containsKey(playerId)) return;
        idleStates.put(playerId, new AnimationState(animation, 1.0f, null));
    }

    public void clearIdle(UUID playerId) {
        idleStates.remove(playerId);
    }

    public boolean isPlaying(UUID playerId) {
        AnimationState s = states.get(playerId);
        return s != null && !s.isMainBodyComplete();
    }

    public boolean isOffhandPlaying(UUID playerId) {
        AnimationState s = offhandStates.get(playerId);
        return s != null && !s.isMainBodyComplete();
    }

    public boolean hasActiveState(UUID playerId) {
        return states.containsKey(playerId) || offhandStates.containsKey(playerId) || idleStates.containsKey(playerId);
    }

    public boolean isHitWindowActive(UUID playerId) {
        AnimationState s = states.get(playerId);
        return s != null && s.animation.isHitWindowActive(s.tickF);
    }

    public float getAttackSwingProgress(UUID playerId) {
        AnimationState s = states.get(playerId);
        if (s == null) return 0f;
        float d = s.animation.duration;
        return d <= 0 ? 0f : Math.min(1f, s.tickF / d);
    }

    public float consumeLungeImpulse(UUID playerId) {
        AnimationState s = states.get(playerId);
        if (s == null || s.lungeImpulseFired || s.animation.lunge <= 0f) return 0f;
        s.lungeImpulseFired = true;
        return s.animation.lunge;
    }

    /**
     * Returns interpolated [rx, ry, rz] for the RIGHT_ARM or LEFT_ARM bone, or null if none active.
     * Called by MixinItemInHandRenderer to animate the first-person arm.
     * Does NOT include the VANILLA_RIGHT_ARM_RX offset — that's third-person only.
     */
    public float[] getArmAngles(UUID playerId, boolean mainhand, float partialTick) {
        AnimationState state = mainhand ? states.get(playerId) : offhandStates.get(playerId);
        if (state == null || state.cachedDeltas == null) return null;

        BoneTarget bone = mainhand ? BoneTarget.RIGHT_ARM : BoneTarget.LEFT_ARM;
        int ord = bone.ordinal();
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
     * Returns interpolated [rx, ry, rz] for the WEAPON_HAND bone, or null if none active.
     * Called by MixinItemInHandLayer to rotate the held item GL matrix.
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
        // WEAPON_HAND keyframe values are scaled down as garnish on top of the grip
        final float WEAPON_SCALE = 0.25f;
        if (prev != null) {
            return new float[]{
                (prev[0] + (cur[0] - prev[0]) * partialTick) * WEAPON_SCALE,
                (prev[1] + (cur[1] - prev[1]) * partialTick) * WEAPON_SCALE,
                (prev[2] + (cur[2] - prev[2]) * partialTick) * WEAPON_SCALE
            };
        }
        return new float[]{cur[0] * WEAPON_SCALE, cur[1] * WEAPON_SCALE, cur[2] * WEAPON_SCALE};
    }

    /**
     * Grip rotation for the held item, or null when inactive. The weapon turns
     * from the vanilla hold into the animation's ending orientation over the
     * windup (smoothstep, full by hit-window start) and blends back out after
     * the strike. Returned quaternion is pre-interpolated — apply directly.
     */
    public Quat getGripRotation(UUID playerId, boolean mainhand) {
        AnimationState state = mainhand ? states.get(playerId) : offhandStates.get(playerId);
        if (state == null) return null;
        WeaponGrip grip = state.gripOverride != null ? state.gripOverride : state.animation.grip;
        if (grip.rotation == null) return null;

        // Interpolate the ramp between ticks so the turn runs at the same 40Hz
        // the body animation does instead of stepping at raw tick rate
        float pt = cachedPartialTick.getOrDefault(playerId, 1.0f);
        float tickInterp = Math.max(0f, state.tickF - (1f - pt) * state.speedMultiplier);

        float blend = state.getBlendFactor(tickInterp);
        float hitStart = Math.max(1f, state.animation.hitWindowStart);
        float ramp = Math.min(1f, tickInterp / hitStart);
        ramp = ramp * ramp * (3f - 2f * ramp); // smoothstep
        float factor = Math.min(ramp, blend) * state.animation.gripScale;
        if (factor <= 0.001f) return null;

        return Quat.IDENTITY.slerp(grip.rotation, factor);
    }

    /** Throttled interpolation partial tick for this player (same source the model uses). */
    public float getPartialTick(UUID playerId) {
        return cachedPartialTick.getOrDefault(playerId, 1.0f);
    }

    /** Advances all animation states by one game tick. Called from ClientSetup.onClientTick. */
    public void tickAll() {
        tickMap(states);
        tickMap(offhandStates);
        tickMap(idleStates);
    }

    private static void tickMap(Map<UUID, AnimationState> map) {
        map.entrySet().removeIf(entry -> {
            AnimationState state = entry.getValue();
            if (state.frozen) return false;
            try {
                state.tickF += state.speedMultiplier;
                if (state.isComplete()) return true;
                if (state.tickF > state.animation.duration * 4f) return true;
                state.refreshCache();
                return false;
            } catch (Exception e) {
                BromaxLib.LOGGER.warn("[BHB] Animation tick error for {}, clearing: {}",
                        entry.getKey(), e.getMessage());
                return true;
            }
        });
    }

    public void clear(UUID playerId) {
        states.remove(playerId);
        offhandStates.remove(playerId);
        idleStates.remove(playerId);
        lastInterpNanos.remove(playerId);
        cachedPartialTick.remove(playerId);
    }

    // -------------------------------------------------------------------------
    // Model application — called from MixinPlayerModel every render frame
    // -------------------------------------------------------------------------

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
            BromaxLib.LOGGER.info("[BHB] ModelPlayer mixin confirmed — animation engine active.");
        }

        AnimationState mainhand = states.get(playerId);
        AnimationState offhand  = offhandStates.get(playerId);

        if (!Float.isFinite(partialTick)) partialTick = 1.0f;
        else partialTick = Math.max(0f, Math.min(1f, partialTick));
        if (mainhand != null && mainhand.frozen) {
            cachedPartialTick.put(playerId, 1.0f);
            partialTick = 1.0f;
        }

        long now    = System.nanoTime();
        Long lastNs = lastInterpNanos.get(playerId);
        if (mainhand != null && mainhand.frozen) {
            // keep the frozen partial tick
        } else if (lastNs == null || (now - lastNs) >= INTERP_INTERVAL_NS) {
            lastInterpNanos.put(playerId, now);
            cachedPartialTick.put(playerId, partialTick);
        } else {
            partialTick = cachedPartialTick.getOrDefault(playerId, partialTick);
        }

        AnimationState idle = idleStates.get(playerId);

        // Vanilla torso pose, so the arms and head can be re-attached after BHB moves it
        ModelRenderer torso = model.bipedBody;
        float vbx = torso.rotateAngleX, vby = torso.rotateAngleY, vbz = torso.rotateAngleZ;
        float vpx = torso.rotationPointX, vpy = torso.rotationPointY, vpz = torso.rotationPointZ;

        try {
            if (mainhand == null && offhand == null && idle != null && idle.cachedDeltas != null) {
                applyToModelUnsafe(idle, model, partialTick, false, false, true);
            }
            if (mainhand != null && mainhand.cachedDeltas != null) {
                applyToModelUnsafe(mainhand, model, partialTick, false, false, false);
            }
            if (offhand != null && offhand.cachedDeltas != null) {
                applyToModelUnsafe(offhand, model, partialTick, true, mainhand != null, false);
            }
            attachToTorso(model, vbx, vby, vbz, vpx, vpy, vpz);
            // A real off-hand attack animates the left arm itself
            AnimationState driver = mainhand != null ? mainhand : (offhand == null ? idle : null);
            if (driver != null && driver.cachedDeltas != null && driver.animation.blendMask.contains(BoneTarget.LEFT_ARM)) {
                poseOffArm(model, offArmResolver.apply(driver.animation), driver.getBlendFactor(driver.tickF));
            }
        } catch (Exception e) {
            BromaxLib.LOGGER.error("[BHB] Animation render error for {}, clearing: {}", playerId, e.getMessage());
            states.remove(playerId);
            offhandStates.remove(playerId);
            idleStates.remove(playerId);
        }
    }

    private void applyToModelUnsafe(AnimationState state, ModelBiped model,
                                     float partialTick, boolean mirror, boolean skipBody,
                                     boolean skipHeadLock) {
        if (state.cachedDeltas == null) return;

        boolean isPlayerModel = model instanceof ModelPlayer;
        float blend = state.getBlendFactor(state.tickF);

        for (BoneTarget bone : state.animation.blendMask) {
            if (skipBody && bone != BoneTarget.RIGHT_ARM && bone != BoneTarget.LEFT_ARM) continue;

            float[] cur = state.cachedDeltas[bone.ordinal()];
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

            ModelRenderer main = getMainPart(model, bone, mirror);
            ModelRenderer wear = isPlayerModel ? getWearPart((ModelPlayer) model, bone, mirror) : null;

            switch (bone) {
                case RIGHT_LEG: case LEFT_LEG: {
                    // Add animation delta on top of vanilla walking so legs keep swinging normally
                    float s = mirror ? -1f : 1f;
                    addDelta(main, c[0], s * c[1], s * c[2]);
                    addDelta(wear, c[0], s * c[1], s * c[2]);
                    break;
                }
                case RIGHT_ARM: {
                    setPart(main, VANILLA_RIGHT_ARM_RX - c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    setPart(wear, VANILLA_RIGHT_ARM_RX - c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    break;
                }
                case LEFT_ARM: {
                    setPart(main, -c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    setPart(wear, -c[0], mirror ? -c[1] : c[1], mirror ? -c[2] : c[2]);
                    break;
                }
                case BODY: {
                    if (Float.isNaN(state.lockedBodyRY) && main != null) {
                        state.lockedBodyRY = main.rotateAngleY;
                    }
                    // Lerp body yaw back to current vanilla yaw during blendout
                    float vanillaRY = main != null ? main.rotateAngleY : 0f;
                    float bodyRY = Float.isNaN(state.lockedBodyRY)
                                 ? vanillaRY
                                 : state.lockedBodyRY * blend + vanillaRY * (1f - blend);
                    setBodyLean(main, c[0], bodyRY, mirror ? -c[2] : c[2]);
                    setBodyLean(wear, c[0], bodyRY, mirror ? -c[2] : c[2]);
                    break;
                }
                default: {
                    addDelta(main, c[0], c[1], c[2]);
                    addDelta(wear, c[0], c[1], c[2]);
                    break;
                }
            }
        }

        // Lunge visual
        if (!skipHeadLock && state.animation.lunge > 0f) {
            float d = state.animation.duration;
            float swingProg = d > 0 ? Math.min(1f, state.tickF / d) : 0f;
            float luCurve = (float) Math.sin(Math.PI * swingProg);
            float lu = state.animation.lunge * luCurve * state.getBlendFactor(state.tickF);

            if (lu > 0.001f) {
                ModelRenderer body = model.bipedBody;
                if (body != null) body.rotateAngleX += lu * LUNGE_BODY_RX;

                ModelRenderer strikeArm = mirror ? model.bipedLeftArm  : model.bipedRightArm;
                if (strikeArm != null) strikeArm.rotateAngleX += lu * LUNGE_ARM_RX;

                if (!skipBody) {
                    ModelRenderer frontLeg = mirror ? model.bipedLeftLeg  : model.bipedRightLeg;
                    ModelRenderer backLeg  = mirror ? model.bipedRightLeg : model.bipedLeftLeg;
                    if (frontLeg != null) frontLeg.rotateAngleX += lu * LUNGE_FRONT_LEG_RX;
                    if (backLeg  != null) backLeg.rotateAngleX  += lu * LUNGE_BACK_LEG_RX;
                }

                ModelRenderer offArm = mirror ? model.bipedRightArm : model.bipedLeftArm;
                if (offArm != null) offArm.rotateAngleX += lu * LUNGE_OFFARM_RX;
            }
        }

        // Bone translations
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
                    TRANS_SCRATCH[0] = tCur[0]; TRANS_SCRATCH[1] = tCur[1]; TRANS_SCRATCH[2] = tCur[2];
                }

                int ord = bone.ordinal();
                ModelRenderer main = getMainPart(model, bone, mirror);
                ModelRenderer wear = isPlayerModel ? getWearPart((ModelPlayer) model, bone, mirror) : null;

                float sign = mirror ? -1f : 1f;
                float rpX  = sign * (VANILLA_RP_X[ord] + TRANS_SCRATCH[0]);
                float rpY  = VANILLA_RP_Y[ord] + TRANS_SCRATCH[1];
                float rpZ  = VANILLA_RP_Z[ord] + TRANS_SCRATCH[2];
                applyTranslation(main, rpX, rpY, rpZ);
                applyTranslation(wear, rpX, rpY, rpZ);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Torso attachment
    // -------------------------------------------------------------------------

    private static final float HIP_Y = 12f; // torso cube is 12px tall below its neck pivot
    private static final float TORSO_HIP_SCALE = 0.6f;

    /**
     * In ModelBiped the head and arms are not children of the body: their pivots sit at
     * fixed points, so any torso lean or tilt the animation adds leaves the shoulders behind
     * (arms appear to sprout from the back or side of the chest). This takes the rotation BHB
     * added to the torso, pivots it at the hips so the figure bends at the waist with the legs
     * planted, and carries the head and both shoulders along with it. Arm angles are left as
     * authored: the clock/degree reference describes where the weapon points in the world, so
     * only the shoulder pivots move.
     */
    private static void attachToTorso(ModelBiped model, float vbx, float vby, float vbz,
                                      float vpx, float vpy, float vpz) {
        ModelRenderer torso = model.bipedBody;
        if (Math.abs(torso.rotateAngleX - vbx) < 1e-4f && Math.abs(torso.rotateAngleY - vby) < 1e-4f
                && Math.abs(torso.rotateAngleZ - vbz) < 1e-4f) return;

        Quat vanilla = Quat.rotationZYX(vbz, vby, vbx);
        Quat posed   = Quat.rotationZYX(torso.rotateAngleZ, torso.rotateAngleY, torso.rotateAngleX);
        Quat delta   = posed.mul(vanilla.conjugate());
        // Torso angles were authored around the neck pivot, where they only rock the chest;
        // bent at the hips the same angle tips the whole upper body, so scale it down
        delta = Quat.IDENTITY.slerp(delta, TORSO_HIP_SCALE);
        float[] e = delta.mul(vanilla).eulerZYX();
        torso.rotateAngleX = e[0]; torso.rotateAngleY = e[1]; torso.rotateAngleZ = e[2];

        // Hip point in model space, from the vanilla torso pose
        float[] hip = vanilla.transform(0f, HIP_Y, 0f);
        hip[0] += vpx; hip[1] += vpy; hip[2] += vpz;

        moveAboutHip(torso, delta, hip, vpx, vpy, vpz);
        moveAboutHip(model.bipedHead, delta, hip, model.bipedHead.rotationPointX, model.bipedHead.rotationPointY, model.bipedHead.rotationPointZ);
        moveAboutHip(model.bipedRightArm, delta, hip, model.bipedRightArm.rotationPointX, model.bipedRightArm.rotationPointY, model.bipedRightArm.rotationPointZ);
        moveAboutHip(model.bipedLeftArm, delta, hip, model.bipedLeftArm.rotationPointX, model.bipedLeftArm.rotationPointY, model.bipedLeftArm.rotationPointZ);

        if (model instanceof ModelPlayer) {
            ModelPlayer pm = (ModelPlayer) model;
            ModelBase.copyModelAngles(torso, pm.bipedBodyWear);
            ModelBase.copyModelAngles(model.bipedHead, pm.bipedHeadwear);
            ModelBase.copyModelAngles(model.bipedRightArm, pm.bipedRightArmwear);
            ModelBase.copyModelAngles(model.bipedLeftArm, pm.bipedLeftArmwear);
        }
    }

    private static void moveAboutHip(ModelRenderer part, Quat delta, float[] hip,
                                     float x, float y, float z) {
        float[] p = delta.transform(x - hip[0], y - hip[1], z - hip[2]);
        part.rotationPointX = p[0] + hip[0]; part.rotationPointY = p[1] + hip[1]; part.rotationPointZ = p[2] + hip[2];
    }


    // -------------------------------------------------------------------------
    // Off arm
    // -------------------------------------------------------------------------

    private static final float HAND_REACH = 10f;  // shoulder pivot to fist, model pixels
    private static final float COUNTER_SWING = 0.3f;
    private static final float COUNTER_MAX   = 0.5f;
    private static final float COUNTER_OUT   = -0.12f; // left arm held slightly away from the body

    /**
     * The mass-generated animations set the left arm to a fixed fraction of the right arm
     * (0.5 one-handed, 0.85 two-handed) with the same sign, so as the weapon arm swings across
     * the body the left arm swings outward into a T-pose, and two-handed grips never meet.
     * Replaces it: two-handed weapons reach the left hand to the right hand every frame, and
     * one-handed weapons swing the left arm back as a counterbalance to the strike. Which mode an
     * animation gets comes from {@link #offArmResolver}, set by the weapon mod (BHB keeps paired
     * weapons and bows on their authored left arm).
     * {@code weight} fades the override in and out with the animation's own blend.
     */
    private static void poseOffArm(ModelBiped model, OffArmMode mode, float weight) {
        if (mode == null || mode == OffArmMode.AUTHORED || weight <= 0.001f) return;
        ModelRenderer right = model.bipedRightArm, left = model.bipedLeftArm;
        float tx, ty, tz;
        if (mode == OffArmMode.TWO_HANDED) {
            float[] hand = Quat.rotationZYX(right.rotateAngleZ, right.rotateAngleY, right.rotateAngleX).transform(-1f, HAND_REACH, 0f);
            float dx = hand[0] + right.rotationPointX - left.rotationPointX, dy = hand[1] + right.rotationPointY - left.rotationPointY, dz = hand[2] + right.rotationPointZ - left.rotationPointZ;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-2f) return;
            dx /= len; dy /= len; dz /= len;
            // Arm hangs along +Y; with yRot 0, rotationZYX maps it to (-cos x sin z, cos x cos z, sin x)
            tx = (float) Math.asin(Math.max(-1f, Math.min(1f, dz)));
            tz = (float) Math.atan2(-dx, dy);
            ty = 0f;
        } else {
            float strike = VANILLA_RIGHT_ARM_RX - right.rotateAngleX; // how far the weapon arm is raised forward
            tx = Math.max(-COUNTER_MAX, Math.min(COUNTER_MAX, strike * COUNTER_SWING));
            ty = 0f;
            tz = COUNTER_OUT;
        }
        left.rotateAngleX = lerp(left.rotateAngleX, tx, weight);
        left.rotateAngleY = lerp(left.rotateAngleY, ty, weight);
        left.rotateAngleZ = lerp(left.rotateAngleZ, tz, weight);
        if (model instanceof ModelPlayer) { ModelPlayer pm = (ModelPlayer) model; ModelBase.copyModelAngles(left, pm.bipedLeftArmwear); }
    }

    // -------------------------------------------------------------------------
    // ModelRenderer helpers — 1.19.2 API (xRot/yRot/zRot, x/y/z)
    // -------------------------------------------------------------------------

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static void setPart(ModelRenderer p, float rx, float ry, float rz) {
        if (p == null) return;
        p.rotateAngleX = rx; p.rotateAngleY = ry; p.rotateAngleZ = rz;
    }

    private static void setBodyLean(ModelRenderer p, float rx, float ry, float rz) {
        if (p == null) return;
        p.rotateAngleX = rx; p.rotateAngleY = ry; p.rotateAngleZ = rz;
    }

    private static void addDelta(ModelRenderer p, float dx, float dy, float dz) {
        if (p == null) return;
        p.rotateAngleX += dx; p.rotateAngleY += dy; p.rotateAngleZ += dz;
    }

    private static void applyTranslation(ModelRenderer p, float x, float y, float z) {
        if (p == null) return;
        p.rotationPointX = x; p.rotationPointY = y; p.rotationPointZ = z;
    }

    private static ModelRenderer getMainPart(ModelBiped model, BoneTarget bone, boolean mirror) {
        switch (bone) {
            case HEAD: return model.bipedHead;
            case BODY: return model.bipedBody;
            case RIGHT_ARM: return mirror ? model.bipedLeftArm  : model.bipedRightArm;
            case LEFT_ARM: return mirror ? model.bipedRightArm : model.bipedLeftArm;
            case RIGHT_LEG: return mirror ? model.bipedLeftLeg  : model.bipedRightLeg;
            case LEFT_LEG: return mirror ? model.bipedRightLeg : model.bipedLeftLeg;
            default: return null;
        }
    }

    private static ModelRenderer getWearPart(ModelPlayer model, BoneTarget bone, boolean mirror) {
        switch (bone) {
            case HEAD: return model.bipedHeadwear;
            case BODY: return model.bipedBodyWear;
            case RIGHT_ARM: return mirror ? model.bipedLeftArmwear  : model.bipedRightArmwear;
            case LEFT_ARM: return mirror ? model.bipedRightArmwear : model.bipedLeftArmwear;
            case RIGHT_LEG: return mirror ? model.bipedLeftLegwear   : model.bipedRightLegwear;
            case LEFT_LEG: return mirror ? model.bipedRightLegwear  : model.bipedLeftLegwear;
            default: return null;
        }
    }
}
