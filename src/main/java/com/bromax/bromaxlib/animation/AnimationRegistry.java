package com.bromax.bromaxlib.animation;

import com.bromax.bromaxlib.BromaxLib;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.InputStreamReader;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

@SideOnly(Side.CLIENT)
public class AnimationRegistry {
    public static final AnimationRegistry INSTANCE = new AnimationRegistry();

    private final Map<ResourceLocation, AnimationDefinition> registry = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<ResourceLocation> failed = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    // JsonParser is stateless — share one instance rather than allocating on every load.
    private static final JsonParser PARSER = new JsonParser();

    /**
     * Eagerly loads every animation in the given ID set. Called once at world-enter
     * with the full set of IDs referenced by weapon attributes, so that no disk reads
     * ever happen during combat.
     */
    public void preloadAll(Collection<ResourceLocation> ids) {
        int loaded = 0, skipped = 0;
        for (ResourceLocation id : ids) {
            if (id == null) continue;
            if (registry.containsKey(id) || failed.contains(id)) { skipped++; continue; }
            AnimationDefinition def = load(id);
            if (def != null) { registry.put(id, def); loaded++; }
            else             { failed.add(id); }
        }
        BromaxLib.LOGGER.info("[BromaxLib] Preloaded {} animations ({} skipped, {} failed)",
                loaded, skipped, failed.size());
    }

    public AnimationDefinition get(ResourceLocation id) {
        if (id == null) return null;
        if (failed.contains(id)) return null;
        AnimationDefinition cached = registry.get(id);
        if (cached != null) return cached;
        AnimationDefinition loaded = load(id);
        if (loaded != null) {
            registry.put(id, loaded);
        } else {
            failed.add(id);
        }
        return loaded;
    }

    private AnimationDefinition load(ResourceLocation id) {
        ResourceLocation filePath = new ResourceLocation(
            id.getResourceDomain(), "animations/" + id.getResourcePath() + ".json");
        // Use try-with-resources so the IResource (and its underlying InputStream) are
        // always closed — previously they were leaked on every first animation load.
        try (IResource resource = Minecraft.getMinecraft().getResourceManager().getResource(filePath)) {
            JsonObject json = PARSER.parse(new InputStreamReader(resource.getInputStream()))
                .getAsJsonObject();
            return AnimationDefinition.fromJson(id, json);
        } catch (Exception e) {
            BromaxLib.LOGGER.error("Failed to load animation '{}': {}", id, e.getMessage());
            return null;
        }
    }
}
