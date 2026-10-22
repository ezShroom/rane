package me.shro.rane.mixin.portals;

import me.shro.rane.portals.PortalLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LeverBlock.class)
public abstract class LeverBlockMixin {
	@Inject(method = "pull", at = @At("RETURN"))
	private void rane$portal(BlockState state, Level level, BlockPos pos, Player player, CallbackInfo ci) {
		PortalLogic.onLever(level, pos, player);
	}
}
