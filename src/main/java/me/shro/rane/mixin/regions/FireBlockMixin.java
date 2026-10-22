package me.shro.rane.mixin.regions;

import me.shro.rane.regions.Enforcement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fire goes out instead of spreading in regions that prevent it. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void rane$noFire(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
		if (!Enforcement.fireAllowed(level, pos)) {
			level.removeBlock(pos, false);
			ci.cancel();
		}
	}
}
