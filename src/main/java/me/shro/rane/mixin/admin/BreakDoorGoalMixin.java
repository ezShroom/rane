package me.shro.rane.mixin.admin;

import me.shro.rane.admin.Hazards;
import net.minecraft.world.entity.ai.goal.BreakDoorGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BreakDoorGoal.class)
public abstract class BreakDoorGoalMixin {
	@Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
	private void rane$noDoorBreaking(CallbackInfoReturnable<Boolean> cir) {
		if (Hazards.doorBreaking()) cir.setReturnValue(false);
	}
}
