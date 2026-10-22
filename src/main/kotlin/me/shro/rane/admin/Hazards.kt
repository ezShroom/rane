package me.shro.rane.admin

import me.shro.rane.Rane
import me.shro.rane.core.txt
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.ServerExplosion

/** Called from mixins; every check is a no-op when the admin module is off. */
object Hazards {
    private val on get() = Rane.isEnabled("admin")
    private val cfg get() = Admin.config.hazards

    private fun suppressed(source: Entity?): Boolean {
        if (!on || source == null) return false
        return when (source.type) {
            EntityTypes.CREEPER -> cfg.disableCreeperExplosions
            EntityTypes.WITHER, EntityTypes.WITHER_SKULL -> cfg.disableWitherExplosions
            else -> false
        }
    }

    /** Returns true if the explosion's block damage was handled (cancelled or queued for rebuild). */
    @JvmStatic
    fun onExplosionBlocks(explosion: ServerExplosion, blocks: List<BlockPos>): Boolean {
        if (!suppressed(explosion.directSourceEntity)) return false
        if (Admin.config.worldRebuild.enabled) WorldRebuild.rebuild(explosion.level(), blocks)
        return true
    }

    /** Item frames, paintings… shouldn't break from suppressed explosions either. */
    @JvmStatic
    fun protectsHanging(attacker: Entity?): Boolean = suppressed(attacker)

    @JvmStatic
    fun endermanPickup(): Boolean = on && cfg.disableEndermanBlockPickup

    @JvmStatic
    fun doorBreaking(): Boolean = on && cfg.disableDoorBreaking

    @JvmStatic
    fun lightningFire(): Boolean = on && cfg.disableLightningFire

    /** True if a wither may not be built here; tells nearby players why. */
    @JvmStatic
    fun witherProhibited(level: ServerLevel, pos: BlockPos): Boolean {
        if (!on || !cfg.restrictWitherSpawning) return false
        val dim = level.dimension().identifier().toString()
        if (dim in cfg.witherDimensions) return false
        level.players().filter { it.blockPosition().closerThan(pos, 32.0) }
            .forEach { it.sendSystemMessage(txt("§7The wither may not spawn in this world!")) }
        return true
    }
}
