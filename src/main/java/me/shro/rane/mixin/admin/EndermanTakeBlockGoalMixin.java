package me.shro.rane.mixin.admin;

import me.shro.rane.admin.Hazards;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.world.entity.monster.Enderman$EndermanTakeBlockGoal")
public abstract class EndermanTakeBlockGoalMixin {
	@Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
	private void rane$noPickup(CallbackInfoReturnable<Boolean> cir) {
		if (Hazards.endermanPickup()) cir.setReturnValue(false);
	}
}
