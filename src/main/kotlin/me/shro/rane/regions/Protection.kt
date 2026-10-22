package me.shro.rane.regions

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

/** Shared protection checks other modules use before changing the world on a player's behalf. */
object Protection {
    @JvmStatic
    fun mayBuild(player: ServerPlayer, level: ServerLevel, pos: BlockPos): Boolean = Regions.mayBuild(player, level, pos)

    @JvmStatic
    fun mayUseContainers(player: ServerPlayer, level: ServerLevel, pos: BlockPos): Boolean = Regions.mayUseContainers(player, level, pos)
}
