package com.bromax.bromaxlib.mixin;

import com.bromax.bromaxlib.animation.AnimationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Injects animation bone application into ModelPlayer.setRotationAngles.
 * Fires after vanilla has set all bone positions so our values take priority.
 * Targeting the model directly means it works regardless of which renderer is
 * active — FoamFix, FermiumASM, or any other pipeline mod can't block it.
 */
@Mixin(ModelPlayer.class)
public abstract class MixinModelPlayer {

    // func_78087_a = setRotationAngles (SRG name, works in both dev and production)
    @Inject(method = "func_78087_a", at = @At("RETURN"))
    private void bhb_applyAnimation(
            float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch, float scaleFactor,
            Entity entityIn, CallbackInfo ci) {

        if (!(entityIn instanceof EntityPlayer)) return;
        EntityPlayer player = (EntityPlayer) entityIn;
        UUID id = player.getUniqueID();
        if (!AnimationController.INSTANCE.hasActiveState(id)) return;

        float partial = Minecraft.getMinecraft().getRenderPartialTicks();
        AnimationController.INSTANCE.applyToModel(id, (ModelPlayer)(Object)this, partial);
    }
}
