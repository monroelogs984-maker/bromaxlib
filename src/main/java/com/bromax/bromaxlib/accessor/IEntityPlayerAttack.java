package com.bromax.bromaxlib.accessor;

import net.minecraft.entity.Entity;

/** Implemented by EntityPlayer via MixinEntityPlayer. */
public interface IEntityPlayerAttack {
    void bhb_attackTargetEntityWithCurrentItem(Entity target);
}
