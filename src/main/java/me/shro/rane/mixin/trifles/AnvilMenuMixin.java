package me.shro.rane.mixin.trifles;

import me.shro.rane.trifles.Misc;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Too Expensive!" no more: anvil costs are capped instead. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
	@Shadow @Final private DataSlot cost;

	@ModifyConstant(method = "createResult", constant = @Constant(intValue = 40, ordinal = 2))
	private int rane$neverTooExpensive(int original) {
		return Misc.repairCostLimit() > 0 ? Integer.MAX_VALUE : original;
	}

	@Inject(method = "createResult", at = @At("TAIL"))
	private void rane$capCost(CallbackInfo ci) {
		int limit = Misc.repairCostLimit();
		if (limit > 0 && this.cost.get() > limit) this.cost.set(limit);
	}
}
