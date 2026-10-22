package me.shro.rane.mixin.trifles;

import me.shro.rane.trifles.Misc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Double doors open and close together. */
@Mixin(DoorBlock.class)
public abstract class DoorBlockMixin {
	@Inject(method = "useWithoutItem", at = @At("RETURN"))
	private void rane$used(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		Misc.onDoorChanged(level, pos, player);
	}

	@Inject(method = "neighborChanged", at = @At("RETURN"))
	private void rane$redstone(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean moved, CallbackInfo ci) {
		Misc.onDoorChanged(level, pos, null);
	}
}
