package me.shro.rane.mixin;

import me.shro.rane.item.CustomItems;
import me.shro.rane.item.Inhibit;
import net.fabricmc.fabric.api.recipe.v1.ingredient.FabricIngredient;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom items don't count as their base item in plain vanilla ingredients. */
@Mixin(Ingredient.class)
public abstract class IngredientMixin {
	@Inject(method = "test(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
	private void rane$rejectCustomItems(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (((FabricIngredient) this).getCustomIngredient() != null) return;
		if (CustomItems.INSTANCE.inhibits(stack, Inhibit.VANILLA_RECIPES)) cir.setReturnValue(false);
	}
}
