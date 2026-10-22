package me.shro.rane.mixin.trifles;

import me.shro.rane.trifles.Misc;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pressing a button sorts the chests around it. */
@Mixin(ButtonBlock.class)
public abstract class ButtonBlockMixin {
	@Inject(method = "press", at = @At("RETURN"))
	private void rane$sort(BlockState state, Level level, BlockPos pos, Player player, CallbackInfo ci) {
		Misc.onButtonPressed(level, pos, player);
	}
}
