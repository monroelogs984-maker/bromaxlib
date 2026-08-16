package com.bromax.bromaxlib.client;

import com.bromax.bromaxlib.BromaxLib;
import com.bromax.bromaxlib.CommonProxy;
import com.bromax.bromaxlib.animation.AnimationController;
import com.bromax.bromaxlib.animation.AnimationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.UUID;

@SideOnly(Side.CLIENT)
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {}

    @Override
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onClientConnected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        // Fires on Netty IO thread — schedule onto the client thread so
        // getResourceManager() is called with a valid OpenGL/resource context.
        java.util.Collection<ResourceLocation> ids = collectAllAnimationIds();
        Minecraft.getMinecraft().addScheduledTask(() ->
                AnimationRegistry.INSTANCE.preloadAll(ids));
    }

    private static java.util.Collection<ResourceLocation> collectAllAnimationIds() {
        try {
            Class<?> registryClass = Class.forName(
                    "com.bromax.bromaxbattle.weapon.WeaponRegistry");
            Object instance = registryClass.getField("INSTANCE").get(null);
            @SuppressWarnings("unchecked")
            java.util.Collection<ResourceLocation> ids =
                    (java.util.Collection<ResourceLocation>)
                    registryClass.getMethod("getAllAnimationIds").invoke(instance);
            return ids;
        } catch (Exception e) {
            BromaxLib.LOGGER.warn("[BromaxLib] Could not collect animation IDs: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private boolean wasAnimatingLastTick = false;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null || mc.isGamePaused()) return;

        AnimationController.INSTANCE.tickAll();

        UUID pid = mc.player.getUniqueID();
        boolean isAnimating = AnimationController.INSTANCE.isPlaying(pid)
                           || AnimationController.INSTANCE.isOffhandPlaying(pid);

        // Hold-to-keep-attacking: when the current animation finishes and the player
        // is still holding the attack key with a valid target, automatically trigger
        // the next swing. This makes hold-to-attack feel like a real fighting game
        // without needing extra input between strikes.
        if (wasAnimatingLastTick && !isAnimating
                && mc.gameSettings.keyBindAttack.isKeyDown()
                && mc.objectMouseOver != null
                && mc.objectMouseOver.typeOfHit == net.minecraft.util.math.RayTraceResult.Type.ENTITY
                && mc.objectMouseOver.entityHit != null) {
            mc.playerController.attackEntity(mc.player, mc.objectMouseOver.entityHit);
        }

        wasAnimatingLastTick = isAnimating;
    }
}
