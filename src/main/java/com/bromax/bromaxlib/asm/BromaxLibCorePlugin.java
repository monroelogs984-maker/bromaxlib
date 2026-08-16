package com.bromax.bromaxlib.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.SortingIndex(Integer.MIN_VALUE + 10)
@IFMLLoadingPlugin.TransformerExclusions({"com.bromax.bromaxlib.asm"})
public class BromaxLibCorePlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    public BromaxLibCorePlugin() {}

    // MixinBooter scans coremods implementing IEarlyMixinLoader and calls this
    // during its tweaker phase — no MixinBootstrap.init() needed here.
    @Override
    public List<String> getMixinConfigs() {
        java.util.List<String> configs = new java.util.ArrayList<>();
        configs.add("bromaxlib.mixins.json");
        configs.add("bromaxlib.optional.mixins.json");
        return configs;
    }

    @Override
    public String[] getASMTransformerClass() { return new String[0]; }

    @Override
    public String getModContainerClass() { return null; }

    @Nullable
    @Override
    public String getSetupClass() { return null; }

    @Override
    public void injectData(Map<String, Object> data) {}

    @Override
    public String getAccessTransformerClass() { return null; }
}
