package me.shro.rane.mixin.admin;

import me.shro.rane.admin.Hazards;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WitherSkullBlock.class)
public abstract class WitherSkullBlockMixin {
	@Shadow
	private static BlockPattern getOrCreateWitherFull() {
		throw new AssertionError();
	}

	@Inject(method = "checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V", at = @At("HEAD"), cancellable = true)
	private static void rane$restrictWither(Level level, BlockPos pos, SkullBlockEntity skull, CallbackInfo ci) {
		if (!(level instanceof ServerLevel serverLevel)) return;
		if (getOrCreateWitherFull().find(level, pos) == null) return;
		if (Hazards.witherProhibited(serverLevel, pos)) ci.cancel();
	}
}
