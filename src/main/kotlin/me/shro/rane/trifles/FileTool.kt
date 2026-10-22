package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import me.shro.rane.Rane
import me.shro.rane.item.ItemHooks
import me.shro.rane.regions.Protection
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CrossCollisionBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TripWireBlock
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.StairsShape
import net.minecraft.world.level.block.state.properties.WallSide
import net.minecraft.world.phys.BlockHitResult
import kotlin.math.abs

/** The file reshapes fences, panes, walls and stairs without breaking them. */
object FileTool {
    private val ADDED: SoundEvent = SoundEvents.UI_STONECUTTER_TAKE_RESULT
    private val REMOVED: SoundEvent = SoundEvents.GRINDSTONE_USE

    fun init() {
        ItemHooks.onUseOnBlock("file") { player, hand, stack, hit ->
            val level = player.level() as ServerLevel
            val pos = hit.blockPos
            if (Rane.isEnabled("regions") && !Protection.mayBuild(player, level, pos)) return@onUseOnBlock InteractionResult.FAIL
            val state = level.getBlockState(pos)
            val (next, sound) = (if (player.isShiftKeyDown) {
                if (state.block is StairBlock) flipStairs(state) else null
            } else when (state.block) {
                is WallBlock -> wall(state, hit)
                is CrossCollisionBlock, is TripWireBlock -> crossing(state, hit)
                is StairBlock -> stairShape(state)
                else -> null
            }) ?: return@onUseOnBlock InteractionResult.PASS
            level.setBlock(pos, next, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
            level.playSound(null, pos, sound, SoundSource.BLOCKS, 1f, 1f)
            stack.hurtAndBreak(1, player, if (hand == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND)
            player.swingHand(hand)
            InteractionResult.SUCCESS
        }
    }

    /** The horizontal side of the block nearest to where it was clicked. */
    private fun nearestSide(hit: BlockHitResult): Pair<Direction, Double> {
        val p = hit.location.subtract(net.minecraft.world.phys.Vec3.atCenterOf(hit.blockPos))
        return if (abs(p.x) > abs(p.z)) (if (p.x > 0) Direction.EAST else Direction.WEST) to abs(p.x)
        else (if (p.z > 0) Direction.SOUTH else Direction.NORTH) to abs(p.z)
    }

    private fun sideProperty(d: Direction): BooleanProperty? = when (d) {
        Direction.NORTH -> BlockStateProperties.NORTH
        Direction.EAST -> BlockStateProperties.EAST
        Direction.SOUTH -> BlockStateProperties.SOUTH
        Direction.WEST -> BlockStateProperties.WEST
        else -> null
    }

    private fun wallProperty(d: Direction): EnumProperty<WallSide>? = when (d) {
        Direction.NORTH -> BlockStateProperties.NORTH_WALL
        Direction.EAST -> BlockStateProperties.EAST_WALL
        Direction.SOUTH -> BlockStateProperties.SOUTH_WALL
        Direction.WEST -> BlockStateProperties.WEST_WALL
        else -> null
    }

    private fun crossing(state: BlockState, hit: BlockHitResult): Pair<BlockState, SoundEvent>? {
        val face = if (hit.direction.axis.isHorizontal) hit.direction else nearestSide(hit).first
        val prop = sideProperty(face) ?: return null
        if (!state.hasProperty(prop)) return null
        val has = state.getValue(prop)
        return state.setValue(prop, !has) to if (has) REMOVED else ADDED
    }

    private fun wall(state: BlockState, hit: BlockHitResult): Pair<BlockState, SoundEvent>? {
        val (near, dominance) = nearestSide(hit)
        if (hit.direction == Direction.UP) {
            if (dominance <= 0.2) {
                val up = state.getValue(WallBlock.UP)
                return state.setValue(WallBlock.UP, !up) to if (up) REMOVED else ADDED
            }
            val prop = wallProperty(near) ?: return null
            return when (state.getValue(prop)) {
                WallSide.NONE -> null
                WallSide.LOW -> state.setValue(prop, WallSide.TALL) to ADDED
                WallSide.TALL -> state.setValue(prop, WallSide.LOW) to REMOVED
            }
        }
        val face = if (hit.direction.axis.isHorizontal) hit.direction else near
        val prop = wallProperty(face) ?: return null
        val current = state.getValue(prop)
        if (current != WallSide.NONE) return state.setValue(prop, WallSide.NONE) to REMOVED
        val opposite = state.getValue(wallProperty(face.opposite)!!)
        return state.setValue(prop, if (opposite == WallSide.NONE) WallSide.LOW else opposite) to ADDED
    }

    private fun flipStairs(state: BlockState): Pair<BlockState, SoundEvent> {
        val half = state.getValue(StairBlock.HALF)
        return state.setValue(StairBlock.HALF, if (half == Half.BOTTOM) Half.TOP else Half.BOTTOM) to ADDED
    }

    private val shapes = listOf(StairsShape.STRAIGHT, StairsShape.INNER_LEFT, StairsShape.INNER_RIGHT, StairsShape.OUTER_LEFT, StairsShape.OUTER_RIGHT)

    private fun stairShape(state: BlockState): Pair<BlockState, SoundEvent> {
        val cur = state.getValue(StairBlock.SHAPE)
        val next = shapes[(shapes.indexOf(cur) + 1) % shapes.size]
        val sound = if (next == StairsShape.INNER_LEFT || next == StairsShape.INNER_RIGHT) ADDED else REMOVED
        return state.setValue(StairBlock.SHAPE, next) to sound
    }
}
