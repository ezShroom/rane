package me.shro.rane.item

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore

/** Vanilla behaviours a custom item should not inherit from its base item. */
enum class Inhibit {
    /** Can't stand in for its base item in vanilla recipes (e.g. a sickle isn't a hoe for crafting). */
    VANILLA_RECIPES,
    /** Hoe-based items don't till dirt. */
    HOE_TILL,
    /** Can't be used from the offhand. */
    OFFHAND,
    /** Block items don't place their block. */
    PLACE,
    /** Right-click does nothing vanilla (e.g. paper-based scrolls). */
    USE,
}

/**
 * A custom item: a vanilla [base] item carrying `custom_data {rane:{item:"<id>"}}`, its own
 * `item_model` (from the rane resource pack) and name. Vanilla clients see it as a normal item.
 */
class CustomItem(
    val id: String,
    val base: Item,
    val name: String,
    val model: String = id,
    val lore: List<String> = emptyList(),
    val inhibit: Set<Inhibit> = setOf(Inhibit.VANILLA_RECIPES),
    /** Extra components (durability, attributes, food…). */
    val decorate: (ItemStack) -> Unit = {},
) {
    val modelId: Identifier = Identifier.fromNamespaceAndPath("rane", model)

    fun create(count: Int = 1): ItemStack {
        val stack = ItemStack(base, count)
        apply(stack)
        return stack
    }

    /** (Re)applies this item's identity and look to a stack of the right base item. */
    fun apply(stack: ItemStack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack) { tag ->
            val rane = tag.getCompoundOrEmpty(TAG)
            rane.putString("item", id)
            tag.put(TAG, rane)
        }
        stack.set(DataComponents.ITEM_MODEL, modelId)
        stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.rane.$id", name))
        if (lore.isNotEmpty()) {
            stack.set(DataComponents.LORE, ItemLore(lore.map {
                Component.literal(it.replace('&', '§')).withStyle(Style.EMPTY.withItalic(false))
            }))
        }
        decorate(stack)
    }

    fun matches(stack: ItemStack): Boolean = !stack.isEmpty && CustomItems.idOf(stack) == id

    companion object {
        const val TAG = "rane"
    }
}

object CustomItems {
    private val items = linkedMapOf<String, CustomItem>()

    val all: Collection<CustomItem> get() = items.values

    fun register(item: CustomItem): CustomItem {
        require(item.id !in items) { "duplicate custom item ${item.id}" }
        items[item.id] = item
        return item
    }

    operator fun get(id: String): CustomItem? = items[id]

    fun create(id: String, count: Int = 1): ItemStack = items[id]?.create(count) ?: ItemStack.EMPTY

    fun idOf(stack: ItemStack): String? {
        if (stack.isEmpty) return null
        val data = stack.get(DataComponents.CUSTOM_DATA) ?: return null
        val tag: CompoundTag = data.copyTag()
        return tag.getCompound(CustomItem.TAG).flatMap { it.getString("item") }.orElse(null)
    }

    fun of(stack: ItemStack): CustomItem? = idOf(stack)?.let { items[it] }

    fun isCustom(stack: ItemStack): Boolean = idOf(stack) != null

    fun inhibits(stack: ItemStack, what: Inhibit): Boolean = of(stack)?.inhibit?.contains(what) == true
}
