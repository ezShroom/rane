package me.shro.rane.mixin.admin;

import me.shro.rane.admin.Hazards;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemFrame.class)
public abstract class ItemFrameMixin {
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void rane$protectFromExplosions(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (source.is(DamageTypeTags.IS_EXPLOSION) && Hazards.protectsHanging(source.getDirectEntity())) cir.setReturnValue(false);
	}
}
