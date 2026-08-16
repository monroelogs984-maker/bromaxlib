package com.bromax.bromaxlib;

import com.bromax.bromaxlib.animation.AnimationRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = BromaxLib.MOD_ID, name = BromaxLib.MOD_NAME, version = BromaxLib.VERSION)
public class BromaxLib {
    public static final String MOD_ID = "bromaxlib";
    public static final String MOD_NAME = "BROMAX's Lib";
    public static final String VERSION = "1.0.0";

    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    @SidedProxy(
        clientSide = "com.bromax.bromaxlib.client.ClientProxy",
        serverSide = "com.bromax.bromaxlib.CommonProxy"
    )
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
