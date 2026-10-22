package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import me.shro.rane.item.CustomItems
import me.shro.rane.item.ItemHooks
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BeetrootBlock
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.loot.LootParams
import net.minecraft.world.level.storage.loot.parameters.LootContextParams
import net.minecraft.world.phys.Vec3

/**
 * Right-click a fully grown crop to harvest and replant it; sickles do the same in a radius.
 * Harvesting yields 1–3 drops (biased upwards) and never drops the seed item.
 */
object Sickles {
    private val radius = mapOf(
        "wooden_sickle" to { Trifles.config.sickles.wooden.harvestRadius },
        "stone_sickle" to { Trifles.config.sickles.stone.harvestRadius },
        "iron_sickle" to { Trifles.config.sickles.iron.harvestRadius },
        "golden_sickle" to { Trifles.config.sickles.golden.harvestRadius },
        "diamond_sickle" to { Trifles.config.sickles.diamond.harvestRadius },
        "netherite_sickle" to { Trifles.config.sickles.netherite.harvestRadius },
    )

    fun init() {
        for ((id, r) in radius) {
            ItemHooks.onUseOnBlock(id) { player, hand, stack, hit ->
                val level = player.level() as ServerLevel
                if (!isHarvestable(level.getBlockState(hit.blockPos))) return@onUseOnBlock InteractionResult.FAIL // no tilling
                var harvested = 0
                val rr = r()
                for (pos in BlockPos.betweenClosed(hit.blockPos.offset(-rr, -1, -rr), hit.blockPos.offset(rr, 1, rr))) {
                    if (pos.distSqr(hit.blockPos) > (rr + 0.5) * (rr + 0.5) + 1) continue
                    if (harvest(level, player, pos.immutable())) harvested++
                }
                if (harvested > 0) {
                    stack.hurtAndBreak(1, player, if (hand == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND)
                    player.swingHand(hand)
                    level.playSound(null, hit.blockPos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1f, 1.2f)
                }
                InteractionResult.SUCCESS
            }
        }
        if (Trifles.config.harvesting) {
            ItemHooks.onAnyUseOnBlock { player, hand, stack, hit ->
                if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown) return@onAnyUseOnBlock InteractionResult.PASS
                if (CustomItems.isCustom(stack) && CustomItems.idOf(stack) in radius) return@onAnyUseOnBlock InteractionResult.PASS
                // Bonemeal etc. on a grown crop should still harvest; anything placeable shouldn't.
                val level = player.level() as ServerLevel
                if (!isHarvestable(level.getBlockState(hit.blockPos))) return@onAnyUseOnBlock InteractionResult.PASS
                if (harvest(level, player, hit.blockPos)) {
                    player.swingHand(hand)
                    level.playSound(null, hit.blockPos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 1f, 1f)
                    InteractionResult.SUCCESS
                } else InteractionResult.PASS
            }
        }
    }

    fun isHarvestable(state: BlockState): Boolean = when (val b = state.block) {
        is CropBlock -> b.isMaxAge(state)
        is NetherWartBlock -> state.getValue(NetherWartBlock.AGE) >= 3
        is CocoaBlock -> state.getValue(CocoaBlock.AGE) >= 2
        else -> false
    }

    private fun replanted(state: BlockState): BlockState = when (val b = state.block) {
        is CropBlock -> b.getStateForAge(0)
        is NetherWartBlock -> state.setValue(NetherWartBlock.AGE, 0)
        is CocoaBlock -> state.setValue(CocoaBlock.AGE, 0)
        else -> state
    }

    /** Harvests and replants a single grown crop; returns false if there was nothing to do. */
    fun harvest(level: ServerLevel, player: ServerPlayer, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        if (!isHarvestable(state)) return false
        if (!level.mayInteract(player, pos)) return false
        if (me.shro.rane.Rane.isEnabled("regions") && !me.shro.rane.regions.Protection.mayBuild(player, level, pos)) return false
        val seed = state.block.asItem().takeIf { it != net.minecraft.world.item.Items.AIR }
        val drops = Block.getDrops(state, level, pos, null, player, player.mainHandItem)
        // Keep the produce, drop the seeds (unless the produce *is* the seed, like potatoes).
        val produce = drops.filter { it.item != seed || isSeedProduce(state) }
        val bonus = when (level.random.nextInt(10)) { in 0..2 -> 1; in 3..7 -> 2; else -> 3 }
        val result = mutableListOf<ItemStack>()
        if (produce.isNotEmpty()) {
            val first = produce.first().copyWithCount(bonus)
            result += first
            produce.drop(1).filter { !net.minecraft.world.item.ItemStack.isSameItem(it, first) }.forEach { result += it }
        }
        level.setBlock(pos, replanted(state), Block.UPDATE_ALL)
                result.forEach { Block.popResource(level, pos, it) }
        level.levelEvent(2001, pos, Block.getId(state))
        return true
    }

    private fun isSeedProduce(state: BlockState) = state.block.asItem().let {
        it == net.minecraft.world.item.Items.CARROT || it == net.minecraft.world.item.Items.POTATO ||
            it == net.minecraft.world.item.Items.NETHER_WART || it == net.minecraft.world.item.Items.COCOA_BEANS
    }
}
