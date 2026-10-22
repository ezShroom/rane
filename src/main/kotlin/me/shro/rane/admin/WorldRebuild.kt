package me.shro.rane.admin

import me.shro.rane.core.Scheduler
import me.shro.rane.core.msToTicks
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.util.ProblemReporter
import kotlin.math.exp

/**
 * Instead of cancelling suppressed explosions, the crater appears and then rebuilds itself
 * block by block, accelerating as it goes. Block entities (chest contents etc.) are restored too.
 */
object WorldRebuild {
    private class Snapshot(val pos: BlockPos, val state: BlockState, val blockEntity: CompoundTag?)

    private class Rebuilder(val level: ServerLevel, val blocks: MutableList<Snapshot>) {
        var task: Scheduler.Task? = null
        var built = 0L
    }

    private val active = mutableListOf<Rebuilder>()
    private val cfg get() = Admin.config.worldRebuild

    fun init() {
        ServerLifecycleEvents.SERVER_STOPPING.register { active.toList().forEach(::finishNow) }
    }

    fun rebuild(level: ServerLevel, positions: List<BlockPos>) {
        val snaps = positions.mapNotNull { pos ->
            val state = level.getBlockState(pos)
            if (state.isAir) return@mapNotNull null
            val be = level.getBlockEntity(pos)
            val tag = be?.saveWithFullMetadata(level.registryAccess())
            (be as? Container)?.clearContent()
            Snapshot(pos.immutable(), state, tag)
        }
        if (snaps.isEmpty()) return
        val flags = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE or Block.UPDATE_SUPPRESS_DROPS
        snaps.forEach { level.setBlock(it.pos, Blocks.AIR.defaultBlockState(), flags) }

        // Rebuild from the outside/bottom inwards: furthest from a point above the crater first.
        val cx = snaps.sumOf { it.pos.x.toDouble() } / snaps.size
        val cz = snaps.sumOf { it.pos.z.toDouble() } / snaps.size
        val cy = snaps.maxOf { it.pos.y } + 1.0
        val ordered = snaps.sortedBy { -it.pos.distToCenterSqr(cx, cy, cz) }.toMutableList()
        val r = Rebuilder(level, ordered)
        active += r
        r.task = Scheduler.later(msToTicks(cfg.delayMs)) { step(r) }
    }

    private fun step(r: Rebuilder) {
        if (r.blocks.isEmpty()) {
            active -= r
            return
        }
        place(r.level, r.blocks.removeFirst())
        r.built++
        val delay = maxOf(cfg.minDelayMs, (cfg.delayMs * exp(-r.built * cfg.delayFalloff)).toLong())
        r.task = Scheduler.later(msToTicks(delay)) { step(r) }
    }

    private fun place(level: ServerLevel, s: Snapshot) {
        val current = level.getBlockState(s.pos)
        if (!current.isAir && !current.canBeReplaced()) level.destroyBlock(s.pos, true)
        level.setBlock(s.pos, s.state, Block.UPDATE_ALL)
        if (s.blockEntity != null) {
            level.getBlockEntity(s.pos)?.let { be ->
                ProblemReporter.ScopedCollector(org.slf4j.LoggerFactory.getLogger("rane/rebuild")).use { reporter ->
                    be.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), s.blockEntity))
                }
                be.setChanged()
            }
        }
        val sound = s.state.soundType
        level.playSound(null, s.pos, sound.placeSound, SoundSource.BLOCKS, 1.0f, 0.8f)
    }

    private fun finishNow(r: Rebuilder) {
        r.task?.cancel()
        r.blocks.forEach { place(r.level, it) }
        r.blocks.clear()
        active -= r
    }
}
