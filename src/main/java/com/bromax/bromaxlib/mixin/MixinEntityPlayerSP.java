package com.bromax.bromaxlib.mixin;

import com.bromax.bromaxlib.animation.AnimationController;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MovementInput;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scales movement input during attack animations.
 *
 * BC does this: during a swing, player movement input is multiplied by 0.5,
 * smoothly eased over swing progress. The "lunge" quality people associate with
 * BC comes from this: the player can still walk toward the target during the
 * swing, but at half speed, creating a committed deliberate stride rather than
 * frantic repositioning. No velocity impulse needed — the player provides the
 * forward intention by holding W, the game just keeps them planted while doing it.
 *
 * func_70636_d = onLivingUpdate()
 * func_78898_a = MovementInput.updatePlayerMoveState() — call we inject AFTER
 * field_71158_b = movementInput field on EntityPlayerSP
 * field_192832_b = moveForward on MovementInput
 * field_78902_a  = moveStrafe on MovementInput
 */
@SideOnly(Side.CLIENT)
@Mixin(EntityPlayerSP.class)
public abstract class MixinEntityPlayerSP {

    @Shadow public MovementInput field_71158_b; // movementInput

    @Inject(method = "func_70636_d",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/util/MovementInput;func_78898_a()V",
                     ordinal = 0,
                     shift = At.Shift.AFTER))
    private void bhb_scaleMovementInput(CallbackInfo ci) {
        EntityPlayerSP self = (EntityPlayerSP)(Object)this;
        if (!AnimationController.INSTANCE.isPlaying(self.getUniqueID())) return;
        if (field_71158_b == null) return;

        // Soft movement restriction during swing (0.85x — barely noticeable, just a hint of commitment)
        float progress = AnimationController.INSTANCE.getAttackSwingProgress(self.getUniqueID());
        float factor = easeMovementFactor(progress);
        field_71158_b.moveForward *= factor;
        field_71158_b.moveStrafe  *= factor;

        // First tick of a new attack: fire a forward velocity impulse scaled by the lunge value.
        // The player physically surges toward the enemy — magnitude controlled per-animation.
        float lungeImpulse = AnimationController.INSTANCE.consumeLungeImpulse(self.getUniqueID());
        if (lungeImpulse > 0f) {
            float speed = lungeImpulse * AnimationController.LUNGE_VELOCITY_SCALE;
            float yawRad = (float) Math.toRadians(self.rotationYaw);
            self.motionX -= Math.sin(yawRad) * speed;
            self.motionZ += Math.cos(yawRad) * speed;
        }
    }

    // Smooth factor: 1.0 at progress=0, 0.85 at peak, back to 1.0 at progress=1.0
    // Barely-there restriction — just enough to feel a slight commitment without locking movement.
    private static float easeMovementFactor(float progress) {
        if (progress <= 0f || progress >= 1f) return 1.0f;
        float minFactor = 0.85f;
        // Ease in for first half, ease out for second half
        float blend;
        if (progress < 0.5f) {
            blend = easeOutCubic(progress * 2f);
        } else {
            blend = easeOutCubic((1f - progress) * 2f);
        }
        return 1.0f - (1.0f - minFactor) * blend;
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }
}
