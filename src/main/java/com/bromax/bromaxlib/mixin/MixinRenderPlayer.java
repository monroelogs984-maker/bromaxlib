package com.bromax.bromaxlib.mixin;

import com.bromax.bromaxlib.animation.AnimationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes first-person arm animation.
 *
 * MixinModelPlayer fires at the RETURN of setRotationAngles and sets arm bone angles.
 * But RenderPlayer.renderRightArm / renderLeftArm then do:
 *   1. setRotationAngles(0, 0, ...) — our mixin fires, sets angles
 *   2. bipedRightArm.rotateAngleX = 0  ← resets the most important axis
 *   3. bipedRightArm.render(...)
 *
 * We inject BEFORE the render() call to re-apply the animation after the reset.
 *
 * func_177138_b = renderRightArm(AbstractClientPlayer)
 * func_177139_c = renderLeftArm(AbstractClientPlayer)
 * func_78785_a  = ModelRenderer.render(float)
 */
@SideOnly(Side.CLIENT)
@Mixin(RenderPlayer.class)
public abstract class MixinRenderPlayer {

    // func_177087_b = getMainModel()
    @Shadow public abstract ModelPlayer func_177087_b();

    @Inject(method = "func_177138_b",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/model/ModelRenderer;func_78785_a(F)V",
                     ordinal = 0, shift = At.Shift.BEFORE))
    private void bhb_firstPersonRightArm(AbstractClientPlayer player, CallbackInfo ci) {
        float partial = Minecraft.getMinecraft().getRenderPartialTicks();
        AnimationController.INSTANCE.applyFirstPersonArm(
                player.getUniqueID(), func_177087_b(), false, partial);
    }

    @Inject(method = "func_177139_c",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/model/ModelRenderer;func_78785_a(F)V",
                     ordinal = 0, shift = At.Shift.BEFORE))
    private void bhb_firstPersonLeftArm(AbstractClientPlayer player, CallbackInfo ci) {
        float partial = Minecraft.getMinecraft().getRenderPartialTicks();
        AnimationController.INSTANCE.applyFirstPersonArm(
                player.getUniqueID(), func_177087_b(), true, partial);
    }
}
