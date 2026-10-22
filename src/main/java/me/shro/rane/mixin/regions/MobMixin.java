package me.shro.rane.mixin.regions;

import me.shro.rane.regions.Enforcement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** No natural monster spawns in regions that prevent them. */
@Mixin(Mob.class)
public abstract class MobMixin {
	@Inject(method = "checkSpawnRules", at = @At("HEAD"), cancellable = true)
	private void rane$noMonsters(LevelAccessor level, EntitySpawnReason reason, CallbackInfoReturnable<Boolean> cir) {
		Mob self = (Mob) (Object) this;
		if (!(self instanceof Enemy) || !(self.level() instanceof ServerLevel sl)) return;
		if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION) return;
		if (!Enforcement.monstersAllowed(sl, self.blockPosition())) cir.setReturnValue(false);
	}
}
