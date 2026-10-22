package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import me.shro.rane.core.txt
import me.shro.rane.item.CustomItems
import me.shro.rane.item.ItemHooks
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.ItemTags
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickAction
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents

/** Pouch (9 slots) and backpack (27 slots): portable storage kept in the item itself. */
object Storage {
    private val ids = setOf("pouch", "backpack")

    fun isStorage(s: ItemStack) = !s.isEmpty && (CustomItems.idOf(s) in ids || s.`is`(ItemTags.SHULKER_BOXES) || s.has(DataComponents.BUNDLE_CONTENTS))

    fun init() {
        for (id in ids) {
            ItemHooks.onUse(id) { player, _, stack -> open(player, stack, id) }
            ItemHooks.onUseOnBlock(id) { player, _, stack, _ -> open(player, stack, id) }
            // Right-click the pouch in your inventory with an item to drop it in.
            ItemHooks.onStackedOnMe(id) { me, carried, _, action, player, carriedAccess ->
                if (action != ClickAction.SECONDARY || carried.isEmpty || me.count != 1) return@onStackedOnMe false
                if (isStorage(carried)) return@onStackedOnMe true
                val size = if (id == "pouch") 9 else 27
                val items = NonNullList.withSize(size, ItemStack.EMPTY)
                me.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items)
                val c = SimpleContainer(*items.toTypedArray())
                val left = c.addItem(carried.copy())
                if (left.count == carried.count) return@onStackedOnMe true
                me.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(c.items))
                carriedAccess.set(left)
                player.notifySound(SoundEvents.BUNDLE_INSERT, 0.8f, 1f)
                true
            }
        }
    }

    private fun open(player: ServerPlayer, stack: ItemStack, id: String): InteractionResult {
        if (stack.count != 1) {
            player.sendOverlayMessage(txt("§7Unstack it first."))
            return InteractionResult.FAIL
        }
        val pouch = id == "pouch"
        val title = stack.get(DataComponents.CUSTOM_NAME) ?: stack.hoverName
        player.openMenu(SimpleMenuProvider({ syncId, inv, _ ->
            ItemStorageMenu(if (pouch) MenuType.GENERIC_3x3 else MenuType.GENERIC_9x3, syncId, inv, stack, if (pouch) 9 else 27)
        }, title))
        player.level().playSound(null, player.blockPosition(), SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.PLAYERS, 1f, 1.2f)
        return InteractionResult.SUCCESS
    }

    /** A container whose contents live in [stack]'s container component (written on every change). */
    private class ItemContainer(val stack: ItemStack, size: Int) : SimpleContainer(size) {
        init {
            val items = NonNullList.withSize(size, ItemStack.EMPTY)
            stack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items)
            items.forEachIndexed { i, s -> super.setItem(i, s) }
        }

        override fun setChanged() {
            super.setChanged()
            stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items))
        }

        override fun canPlaceItem(slot: Int, item: ItemStack) = !isStorage(item)
    }

    private class ItemStorageMenu(type: MenuType<*>, syncId: Int, private val inv: Inventory, private val stack: ItemStack, size: Int) :
        AbstractContainerMenu(type, syncId) {
        private val container = ItemContainer(stack, size)
        private val size = size

        init {
            val cols = if (size == 9) 3 else 9
            val rows = size / cols
            val x0 = if (size == 9) 62 else 8
            for (r in 0 until rows) for (c in 0 until cols) {
                addSlot(object : Slot(container, c + r * cols, x0 + c * 18, 18 + r * 18) {
                    override fun mayPlace(item: ItemStack) = !isStorage(item)
                })
            }
            val y0 = if (size == 9) 84 else 84
            for (r in 0 until 3) for (c in 0 until 9) addSlot(Slot(inv, c + r * 9 + 9, 8 + c * 18, y0 + r * 18))
            for (c in 0 until 9) addSlot(Slot(inv, c, 8 + c * 18, y0 + 58))
        }

        /** The menu closes if the item leaves the player's inventory or is replaced. */
        override fun stillValid(player: Player) = !stack.isEmpty && stack.count == 1 &&
            (0 until inv.containerSize).any { inv.getItem(it) === stack }

        override fun clicked(slotIndex: Int, button: Int, input: ContainerInput, player: Player) {
            if (slotIndex in slots.indices && slots[slotIndex].item === stack) return
            if (input == ContainerInput.SWAP && (button in 0..8 && inv.getItem(button) === stack || button == 40 && inv.getItem(Inventory.SLOT_OFFHAND) === stack)) return
            super.clicked(slotIndex, button, input, player)
        }

        override fun quickMoveStack(player: Player, index: Int): ItemStack {
            val slot = slots.getOrNull(index) ?: return ItemStack.EMPTY
            if (!slot.hasItem()) return ItemStack.EMPTY
            val s = slot.item
            val copy = s.copy()
            if (index < size) {
                if (!moveItemStackTo(s, size, slots.size, true)) return ItemStack.EMPTY
            } else {
                if (isStorage(s) || !moveItemStackTo(s, 0, size, false)) return ItemStack.EMPTY
            }
            if (s.isEmpty) slot.setByPlayer(ItemStack.EMPTY) else slot.setChanged()
            return copy
        }

        override fun removed(player: Player) {
            super.removed(player)
            container.setChanged()
            player.level().playSound(null, player.blockPosition(), SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 0.6f, 0.8f)
        }
    }
}
