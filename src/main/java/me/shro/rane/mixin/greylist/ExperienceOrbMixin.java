package me.shro.rane.mixin.greylist;

import me.shro.rane.greylist.Greylist;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Greylisted players can't pick things up. */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
	@Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
	private void rane$noPickup(Player player, CallbackInfo ci) {
		if (Greylist.INSTANCE.isGreylisted(player)) ci.cancel();
	}
}
