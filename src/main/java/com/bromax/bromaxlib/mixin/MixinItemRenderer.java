package com.bromax.bromaxlib.mixin;

import com.bromax.bromaxlib.animation.AnimationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies WEAPON_HAND rotation to the held item in first-person view.
 *
 * func_187462_a = private renderItemInFirstPerson(EntityLivingBase, ItemStack,
 *                     ItemCameraTransforms.TransformType, boolean leftHand)
 *
 * We inject at HEAD so we're called before func_187462_a applies its own GL
 * transforms. The GL state at this point is the arm-position matrix set up by
 * the caller (func_187457_a), which is the correct frame for weapon rotation.
 * We guard on FIRST_PERSON_* TransformType so this never fires for third-person
 * rendering (which is handled by MixinLayerHeldItem instead).
 */
@SideOnly(Side.CLIENT)
@Mixin(net.minecraft.client.renderer.ItemRenderer.class)
public abstract class MixinItemRenderer {

    @Inject(method = "func_187462_a", at = @At("HEAD"))
    private void bhb_fpWeaponRot(EntityLivingBase entity, ItemStack stack,
                                   ItemCameraTransforms.TransformType transformType,
                                   boolean leftHand, CallbackInfo ci) {
        if (transformType != ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND
                && transformType != ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;

        boolean mainhand = (transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND);
        float partial = mc.getRenderPartialTicks();
        float[] angles = AnimationController.INSTANCE.getWeaponAngles(mc.player.getUniqueID(), mainhand, partial);
        if (angles == null) return;
        if (angles[0] != 0f) GlStateManager.rotate((float) Math.toDegrees(angles[0]), 1f, 0f, 0f);
        if (angles[1] != 0f) GlStateManager.rotate((float) Math.toDegrees(angles[1]), 0f, 1f, 0f);
        if (angles[2] != 0f) GlStateManager.rotate((float) Math.toDegrees(angles[2]), 0f, 0f, 1f);
    }
}
