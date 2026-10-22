package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import me.shro.rane.Rane
import me.shro.rane.core.plain
import me.shro.rane.item.CustomItem
import me.shro.rane.item.CustomItems
import me.shro.rane.item.ItemHooks
import me.shro.rane.regions.Protection
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.ClickAction
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.context.UseOnContext

/** The trowel places a random block from a chosen inventory row ("feed source"). */
object Trowel {
    enum class Feed(val label: String, val slots: IntRange) {
        HOTBAR("Hotbar", 0..8),
        FIRST_ROW("First Inventory Row", 9..17),
        SECOND_ROW("Second Inventory Row", 18..26),
        THIRD_ROW("Third Inventory Row", 27..35),
    }

    private fun feed(stack: ItemStack): Feed {
        val i = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getCompound(CustomItem.TAG)?.map { it.getIntOr("feed", 0) }?.orElse(0) ?: 0
        return Feed.entries.getOrElse(i) { Feed.HOTBAR }
    }

    fun updateLore(stack: ItemStack) {
        stack.set(DataComponents.LORE, ItemLore(listOf(
            plain("§7Places a random block from your"),
            plain("§7feed source: §a${feed(stack).label}"),
            plain("§8Right-click it in your inventory to change."),
        )))
    }

    fun init() {
        ItemHooks.onStackedOnMe("trowel") { me, carried, _, action, player, _ ->
            if (action != ClickAction.SECONDARY || !carried.isEmpty) return@onStackedOnMe false
            val next = Feed.entries[(feed(me).ordinal + 1) % Feed.entries.size]
            CustomData.update(DataComponents.CUSTOM_DATA, me) { tag ->
                val r = tag.getCompoundOrEmpty(CustomItem.TAG)
                r.putInt("feed", next.ordinal)
                tag.put(CustomItem.TAG, r)
            }
            updateLore(me)
            player.notifySound(SoundEvents.UI_BUTTON_CLICK.value(), 1f, 2f)
            true
        }
        ItemHooks.onUseOnBlock("trowel") { player, hand, trowel, hit ->
            if (hand != InteractionHand.MAIN_HAND) return@onUseOnBlock InteractionResult.FAIL
            val level = player.level() as ServerLevel
            val target = hit.blockPos.relative(hit.direction)
            if (Rane.isEnabled("regions") && !Protection.mayBuild(player, level, target)) return@onUseOnBlock InteractionResult.FAIL
            val inv = player.inventory
            val candidates = feed(trowel).slots.filter { slot ->
                val s = inv.getItem(slot)
                !s.isEmpty && s.item is BlockItem && !s.`is`(ItemTags.SHULKER_BOXES) && !CustomItems.isCustom(s)
            }.shuffled(java.util.Random(level.random.nextLong()))
            for (slot in candidates) {
                val block = inv.getItem(slot)
                val before = block.count
                val result = block.useOn(UseOnContext(level, player, InteractionHand.MAIN_HAND, block, hit))
                if (player.hasInfiniteMaterials()) block.count = before
                player.connection.send(ClientboundBlockUpdatePacket(level, hit.blockPos))
                player.connection.send(ClientboundBlockUpdatePacket(level, target))
                if (result.consumesAction()) {
                    trowel.hurtAndBreak(1, player, EquipmentSlot.MAINHAND)
                    player.swingHand(hand)
                    return@onUseOnBlock InteractionResult.SUCCESS
                }
            }
            player.notifySound(SoundEvents.UI_STONECUTTER_SELECT_RECIPE, 1f, 2f)
            InteractionResult.FAIL
        }
    }
}
