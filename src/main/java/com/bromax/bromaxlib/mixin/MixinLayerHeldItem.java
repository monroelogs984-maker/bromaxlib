package com.bromax.bromaxlib.mixin;

import com.bromax.bromaxlib.animation.AnimationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * func_188364_a = private renderHeldItem(AbstractClientPlayer, ItemStack,
 *                     TransformType, EnumHandSide, ModelRenderer, float)
 * func_78791_b  = ModelRenderer.postRender(float)
 *
 * Inject AFTER postRender inside renderHeldItem so we're already inside the
 * arm-local GL matrix when we apply the weapon rotation. Called once per hand.
 * We determine mainhand vs offhand from the EnumHandSide parameter.
 */
@Mixin(net.minecraft.client.renderer.entity.layers.LayerHeldItem.class)
public abstract class MixinLayerHeldItem {

    @Inject(method = "func_188364_a",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/model/ModelRenderer;func_78791_b(F)V",
                     ordinal = 0,
                     shift = At.Shift.AFTER))
    private void bhb_weaponRot(AbstractClientPlayer player, ItemStack stack,
            ItemCameraTransforms.TransformType transformType,
            EnumHandSide handSide, ModelRenderer renderer, float scale,
            CallbackInfo ci) {
        boolean mainhand = (handSide == player.getPrimaryHand());
        UUID id = player.getUniqueID();
        float partial = Minecraft.getMinecraft().getRenderPartialTicks();
        float[] angles = AnimationController.INSTANCE.getWeaponAngles(id, mainhand, partial);
        if (angles == null) return;
        if (angles[0] != 0f) GlStateManager.rotate((float) Math.toDegrees(angles[0]), 1f, 0f, 0f);
        if (angles[1] != 0f) GlStateManager.rotate((float) Math.toDegrees(angles[1]), 0f, 1f, 0f);
        if (angles[2] != 0f) GlStateManager.rotate((float) Math.toDegrees(angles[2]), 0f, 0f, 1f);
    }
}
