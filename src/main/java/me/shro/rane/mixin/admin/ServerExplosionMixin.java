package me.shro.rane.mixin.admin;

import java.util.List;
import me.shro.rane.admin.Hazards;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Inject(method = "interactWithBlocks", at = @At("HEAD"), cancellable = true)
	private void rane$suppress(List<BlockPos> blocks, CallbackInfo ci) {
		ServerExplosion self = (ServerExplosion) (Object) this;
		me.shro.rane.regions.Enforcement.filterExplosion(self.level(), blocks);
		blocks.removeIf(p -> me.shro.rane.portals.Portals.INSTANCE.portalAt(self.level(), p) != null);
		if (Hazards.onExplosionBlocks(self, blocks)) ci.cancel();
	}
}
