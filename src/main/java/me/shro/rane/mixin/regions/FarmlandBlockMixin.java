package me.shro.rane.mixin.regions;

import me.shro.rane.regions.Enforcement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {
	@Inject(method = "fallOn", at = @At("HEAD"), cancellable = true)
	private void rane$noTrample(Level level, BlockState state, BlockPos pos, Entity entity, double fall, CallbackInfo ci) {
		if (level instanceof ServerLevel sl && !Enforcement.trampleAllowed(sl, pos, entity)) {
			entity.causeFallDamage(fall, 1.0f, entity.damageSources().fall());
			ci.cancel();
		}
	}
}
