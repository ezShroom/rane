package me.shro.rane.core

import eu.pb4.sgui.api.elements.GuiElementBuilder
import eu.pb4.sgui.api.gui.AnvilInputGui
import eu.pb4.sgui.api.gui.SimpleGui
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** Small helpers on top of sgui for chest menus, text prompts and paying with items. */
object Menus {
    fun button(item: Item, name: String, vararg lore: String, onClick: (eu.pb4.sgui.api.ClickType) -> Unit = {}): GuiElementBuilder =
        GuiElementBuilder(item).setName(plain(name)).setLore(lore.map { plain(it) }).hideDefaultTooltip()
            .setCallback { _, type, _, _ -> onClick(type) }

    fun button(stack: ItemStack, name: String, vararg lore: String, onClick: (eu.pb4.sgui.api.ClickType) -> Unit = {}): GuiElementBuilder =
        GuiElementBuilder.from(stack).setName(plain(name)).setLore(lore.map { plain(it) })
            .setCallback { _, type, _, _ -> onClick(type) }

    fun chest(player: ServerPlayer, rows: Int, title: String): SimpleGui {
        val type = when (rows) { 1 -> MenuType.GENERIC_9x1; 2 -> MenuType.GENERIC_9x2; 3 -> MenuType.GENERIC_9x3; 4 -> MenuType.GENERIC_9x4; 5 -> MenuType.GENERIC_9x5; else -> MenuType.GENERIC_9x6 }
        return SimpleGui(type, player, false).also { it.setTitle(txt(title)) }
    }

    fun prompt(player: ServerPlayer, title: String, initial: String, onDone: (String) -> Unit) {
        val gui = object : AnvilInputGui(player, false) {}
        gui.setTitle(txt(title))
        gui.setDefaultInputValue(initial)
        gui.setSlot(2, GuiElementBuilder(Items.DYE.lime()).setName(plain("§aConfirm")).setCallback { _, _, _, _ ->
            val text = gui.input.trim()
            gui.close()
            if (text.isNotEmpty()) onDone(text)
        })
        gui.open()
    }

    fun confirm(player: ServerPlayer, title: String, onYes: () -> Unit, onNo: () -> Unit) {
        val gui = chest(player, 1, title)
        gui.setSlot(2, button(Items.DYED_TERRACOTTA.lime(), "§aYes") { gui.close(); onYes() })
        gui.setSlot(6, button(Items.DYED_TERRACOTTA.red(), "§cNo") { gui.close(); onNo() })
        gui.open()
    }

    fun item(id: String): Item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElse(Items.DIAMOND)

    fun count(player: ServerPlayer, item: Item): Int {
        val inv = player.inventory
        return (0 until inv.containerSize).map { inv.getItem(it) }
            .filter { it.`is`(item) && !me.shro.rane.item.CustomItems.isCustom(it) }.sumOf { it.count }
    }

    /** Takes [amount] of [item]; false (and nothing taken) if the player can't afford it. */
    fun pay(player: ServerPlayer, item: Item, amount: Int): Boolean {
        if (amount <= 0 || player.hasInfiniteMaterials()) return true
        if (count(player, item) < amount) return false
        var left = amount
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            if (left == 0) break
            val s = inv.getItem(i)
            if (!s.`is`(item) || me.shro.rane.item.CustomItems.isCustom(s)) continue
            val take = minOf(left, s.count)
            s.shrink(take)
            left -= take
        }
        return true
    }

    fun refund(player: ServerPlayer, item: Item, amount: Int) {
        var left = amount
        while (left > 0) {
            val n = minOf(left, item.defaultMaxStackSize)
            giveItem(player, ItemStack(item, n))
            left -= n
        }
    }
}
