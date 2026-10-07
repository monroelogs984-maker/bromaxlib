package com.bromax.bromaxlib.mixin;

import com.bromax.bromaxlib.animation.AnimationController;
import com.bromax.bromaxlib.animation.Quat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Applies the grip and WEAPON_HAND rotation to held items.
 *
 * func_187462_a = ItemRenderer.renderItemSide(EntityLivingBase, ItemStack, TransformType, boolean leftHanded).
 * Both paths render through it:
 *  - third person: LayerHeldItem.renderHeldItem has already moved into the hand and applied its
 *    X -90 / Y 180 / translate, so the GL matrix here is the hand frame the grip presets are
 *    computed in (same frame as the modern ItemInHandLayer injection);
 *  - first person: renderItemInFirstPerson's arm-position matrix.
 *
 * (The old MixinLayerHeldItem targeted func_188364_a, which is renderEnchantedGlint, and lived in
 * an optional config, so third-person weapon rotation silently never applied.)
 */
@SideOnly(Side.CLIENT)
@Mixin(net.minecraft.client.renderer.ItemRenderer.class)
public abstract class MixinItemRenderer {

    // Item-frame offset toward the grip end of the blade, so long weapons turn in the fist
    private static final float GRIP_PIVOT = 0.18f;

    @Inject(method = "func_187462_a", at = @At("HEAD"))
    private void bhb_weaponRot(EntityLivingBase entity, ItemStack stack,
                               ItemCameraTransforms.TransformType transformType,
                               boolean leftHand, CallbackInfo ci) {
        boolean firstPerson = transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND
                || transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND;
        boolean thirdPerson = transformType == ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND
                || transformType == ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND;
        if (!firstPerson && !thirdPerson) return;
        if (!(entity instanceof AbstractClientPlayer)) return;
        AbstractClientPlayer player = (AbstractClientPlayer) entity;
        UUID id = player.getUniqueID();
        EnumHandSide side = leftHand ? EnumHandSide.LEFT : EnumHandSide.RIGHT;
        boolean mainhand = side == player.getPrimaryHand();

        if (firstPerson) {
            float partial = Minecraft.getMinecraft().getRenderPartialTicks();
            float[] angles = AnimationController.INSTANCE.getWeaponAngles(id, mainhand, partial);
            if (angles == null) return;
            rotate(angles);
            return;
        }

        Quat grip = AnimationController.INSTANCE.getGripRotation(id, mainhand);
        float[] angles = AnimationController.INSTANCE.getWeaponAngles(
                id, mainhand, AnimationController.INSTANCE.getPartialTick(id));
        if (grip == null && angles == null) return;
        GlStateManager.translate(-GRIP_PIVOT, -GRIP_PIVOT, 0f);
        if (grip != null) grip.applyGl();
        if (angles != null) rotate(angles);
        GlStateManager.translate(GRIP_PIVOT, GRIP_PIVOT, 0f);
    }

    private static void rotate(float[] angles) {
        if (Math.abs(angles[0]) > 0.001f) GlStateManager.rotate((float) Math.toDegrees(angles[0]), 1f, 0f, 0f);
        if (Math.abs(angles[1]) > 0.001f) GlStateManager.rotate((float) Math.toDegrees(angles[1]), 0f, 1f, 0f);
        if (Math.abs(angles[2]) > 0.001f) GlStateManager.rotate((float) Math.toDegrees(angles[2]), 0f, 0f, 1f);
    }
}
