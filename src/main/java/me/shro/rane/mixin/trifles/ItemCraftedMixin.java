package me.shro.rane.mixin.trifles;

import me.shro.rane.item.CustomItems;
import me.shro.rane.trifles.TriflesItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A north compass works in the dimension it was crafted in. */
@Mixin(Item.class)
public abstract class ItemCraftedMixin {
	@Inject(method = "onCraftedBy", at = @At("HEAD"))
	private void rane$crafted(ItemStack stack, Player player, CallbackInfo ci) {
		if (!player.level().isClientSide() && "north_compass".equals(CustomItems.INSTANCE.idOf(stack))) {
			TriflesItems.INSTANCE.pointNorth(stack, player.level().dimension());
		}
	}
}
