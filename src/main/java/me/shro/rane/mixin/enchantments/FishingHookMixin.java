package me.shro.rane.mixin.enchantments;

import me.shro.rane.enchantments.EnchantmentEffects;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
	@Inject(method = "retrieve", at = @At("HEAD"))
	private void rane$grapple(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
		FishingHook self = (FishingHook) (Object) this;
		if (!self.level().isClientSide()) EnchantmentEffects.onReel(self, rod);
	}
}
