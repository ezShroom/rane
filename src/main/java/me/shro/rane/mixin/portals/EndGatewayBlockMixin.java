package me.shro.rane.mixin.portals;

import me.shro.rane.portals.PortalLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Active rane portals are end gateway blocks; they send entities to the connected portal instead. */
@Mixin(EndGatewayBlock.class)
public abstract class EndGatewayBlockMixin {
	@Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
	private void rane$portal(BlockState state, Level level, BlockPos pos, Entity entity, InsideBlockEffectApplier applier, boolean b, CallbackInfo ci) {
		if (PortalLogic.onEntityInside(level, pos, entity)) ci.cancel();
	}
}
