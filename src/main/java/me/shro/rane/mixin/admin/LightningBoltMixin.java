package me.shro.rane.mixin.admin;

import me.shro.rane.admin.Hazards;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightningBolt.class)
public abstract class LightningBoltMixin {
	@Inject(method = "spawnFire", at = @At("HEAD"), cancellable = true)
	private void rane$noFire(int count, CallbackInfo ci) {
		if (Hazards.lightningFire()) ci.cancel();
	}
}
