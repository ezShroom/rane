package me.shro.rane.mixin;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import java.util.Collection;
import java.util.List;
import me.shro.rane.item.Recipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds rane's code-defined recipes next to the data pack ones. */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
	@Shadow @Final @Mutable private RecipeMap recipes;
	@Shadow @Final @Mutable private Collection<RecipeHolder<?>> learnableRecipes;

	@org.spongepowered.asm.mixin.Unique
	private HolderLookup.Provider rane$registries;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void rane$keepRegistries(HolderLookup.Provider registries, CallbackInfo ci) {
		this.rane$registries = registries;
	}

	/** Item components are only bound by now, so custom item results can be built. */
	@Inject(method = "finalizeRecipeLoading", at = @At("HEAD"))
	private void rane$addRecipes(net.minecraft.world.flag.FeatureFlagSet features, CallbackInfo ci) {
		if (this.rane$registries == null) return;
		List<RecipeHolder<?>> extra = Recipes.build(this.rane$registries);
		this.rane$registries = null;
		if (extra.isEmpty()) return;
		ImmutableMultimap.Builder<net.minecraft.world.item.crafting.RecipeType<?>, RecipeHolder<?>> byType = ImmutableMultimap.builder();
		java.util.LinkedHashMap<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey = new java.util.LinkedHashMap<>();
		for (RecipeHolder<?> h : this.recipes.values()) {
			byType.put(h.value().getType(), h);
			byKey.put(h.id(), h);
		}
		for (RecipeHolder<?> h : extra) {
			if (byKey.containsKey(h.id())) continue;
			byType.put(h.value().getType(), h);
			byKey.put(h.id(), h);
		}
		this.recipes = RecipeMapAccessor.rane$create(byType.build(), ImmutableMap.copyOf(byKey));
		this.learnableRecipes = this.recipes.values().stream().filter(h -> !h.value().isSpecial()).toList();
	}
}
