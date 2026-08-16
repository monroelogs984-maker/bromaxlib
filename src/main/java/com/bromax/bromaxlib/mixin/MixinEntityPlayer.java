package com.bromax.bromaxlib.mixin;

import com.bromax.bromaxlib.accessor.IEntityPlayerAttack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes EntityPlayer.attackTargetEntityWithCurrentItem (func_71059_n) via a
 * public bridge so CombatHandler can re-fire delayed hits with full vanilla
 * processing (enchantments, crits, knockback) intact.
 *
 * @Invoker failed at runtime because it tries to match the MCP name
 * "attackTargetEntityWithCurrentItem" against the obfuscated class, where the
 * method no longer exists by that name. @Shadow with the SRG name (func_71059_n)
 * produces a build-time warning but remaps correctly to the obfuscated name at
 * runtime, matching the pattern already used in MixinRenderPlayer.
 */
@Mixin(EntityPlayer.class)
public abstract class MixinEntityPlayer implements IEntityPlayerAttack {

    // func_71059_n = attackTargetEntityWithCurrentItem(Entity)
    // Warning at build time is expected — SRG name resolves to the obfuscated
    // method at runtime via MixinBooter's SRG→obf remapping table.
    @Shadow
    protected abstract void func_71059_n(Entity targetEntity);

    @Override
    public void bhb_attackTargetEntityWithCurrentItem(Entity target) {
        func_71059_n(target);
    }
}
