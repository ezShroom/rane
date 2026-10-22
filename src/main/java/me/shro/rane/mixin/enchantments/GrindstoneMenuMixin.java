package me.shro.rane.mixin.enchantments;

import me.shro.rane.item.CustomItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Grinding an enchanted tome gives back the plain tome. */
@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneMenuMixin {
	@Shadow @Final private Container resultSlots;

	@Inject(method = "createResult", at = @At("TAIL"))
	private void rane$tomes(CallbackInfo ci) {
		ItemStack result = this.resultSlots.getItem(0);
		String id = CustomItems.INSTANCE.idOf(result);
		if (id == null || !id.startsWith("enchanted_ancient_tome")) return;
		var stored = result.get(DataComponents.STORED_ENCHANTMENTS);
		if (stored != null && !stored.isEmpty()) return;
		this.resultSlots.setItem(0, CustomItems.INSTANCE.create(id.substring("enchanted_".length()), result.getCount()));
	}
}
