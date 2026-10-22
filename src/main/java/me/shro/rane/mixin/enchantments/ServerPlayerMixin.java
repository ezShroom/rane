package me.shro.rane.mixin.enchantments;

import me.shro.rane.enchantments.EnchantmentEffects;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "drop(Z)V", at = @At("HEAD"), cancellable = true)
	private void rane$soulboundDropLock(boolean all, CallbackInfo ci) {
		if (EnchantmentEffects.blockSoulboundDrop((ServerPlayer) (Object) this)) ci.cancel();
	}
}
