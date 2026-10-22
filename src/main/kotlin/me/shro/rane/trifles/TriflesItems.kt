package me.shro.rane.trifles

import me.shro.rane.item.CustomItem
import me.shro.rane.item.CustomItems
import me.shro.rane.item.Inhibit
import me.shro.rane.item.Recipes
import me.shro.rane.item.Recipes.Result
import net.minecraft.core.GlobalPos
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.ItemUseAnimation
import net.minecraft.world.item.Items
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.component.Consumable
import net.minecraft.world.item.component.ItemAttributeModifiers
import net.minecraft.world.item.component.LodestoneTracker
import net.minecraft.world.item.component.UseCooldown
import net.minecraft.world.item.component.UseRemainder
import net.minecraft.world.level.Level
import net.minecraft.sounds.SoundEvents
import java.util.Optional

object TriflesItems {
    val SCROLL_GROUP: Identifier = Identifier.fromNamespaceAndPath("rane", "scroll")
    private val cfg get() = Trifles.config

    private fun durability(n: Int): (ItemStack) -> Unit = { s ->
        s.set(DataComponents.MAX_DAMAGE, n)
        s.set(DataComponents.MAX_STACK_SIZE, 1)
        s.set(DataComponents.DAMAGE, s.getOrDefault(DataComponents.DAMAGE, 0))
    }

    private fun scroll(id: String, name: String, uses: Int, lore: List<String>) = CustomItems.register(CustomItem(
        id, Items.WARPED_FUNGUS_ON_A_STICK, name, lore = lore,
        inhibit = setOf(Inhibit.VANILLA_RECIPES, Inhibit.OFFHAND),
    ) { s ->
        durability(uses)(s)
        s.set(DataComponents.USE_COOLDOWN, UseCooldown(0.0001f, Optional.of(SCROLL_GROUP)))
        s.set(DataComponents.RARITY, Rarity.UNCOMMON)
    })

    private fun sickle(id: String, name: String, base: Item, stats: () -> TriflesConfig.Sickle) = CustomItems.register(CustomItem(
        id, base, name, model = id, inhibit = setOf(Inhibit.VANILLA_RECIPES, Inhibit.HOE_TILL, Inhibit.OFFHAND),
    ) { s ->
        val st = stats()
        val mods = ItemAttributeModifiers.builder()
            .add(Attributes.ATTACK_DAMAGE, AttributeModifier(Item.BASE_ATTACK_DAMAGE_ID, st.attackDamage, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
            .add(Attributes.ATTACK_SPEED, AttributeModifier(Item.BASE_ATTACK_SPEED_ID, st.attackSpeed - 4.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
            .build()
        s.set(DataComponents.ATTRIBUTE_MODIFIERS, mods)
    })

    private fun xpBottle(id: String, name: String, levels: () -> Int) = CustomItems.register(CustomItem(
        id, Items.HONEY_BOTTLE, name, inhibit = setOf(Inhibit.VANILLA_RECIPES, Inhibit.OFFHAND),
    ) { s ->
        s.set(DataComponents.ITEM_NAME, net.minecraft.network.chat.Component.translatableWithFallback("item.rane.$id", name).withStyle(net.minecraft.ChatFormatting.YELLOW))
        s.set(DataComponents.CONSUMABLE, Consumable.builder().consumeSeconds(1.6f).animation(ItemUseAnimation.DRINK)
            .sound(SoundEvents.GENERIC_DRINK).hasConsumeParticles(false).build())
        s.remove(DataComponents.FOOD)
        s.set(DataComponents.USE_REMAINDER, UseRemainder(ItemStackTemplate.fromNonEmptyStack(CustomItems.create("empty_xp_bottle"))))
        s.set(DataComponents.MAX_STACK_SIZE, 16)
        s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        s.set(DataComponents.LORE, net.minecraft.world.item.component.ItemLore(listOf(
            me.shro.rane.core.plain("§7Contains ${levels()} levels of experience"))))
    })

    fun register() {
        CustomItems.register(CustomItem("papyrus_scroll", Items.PAPER, "Papyrus Scroll",
            lore = listOf("§7The base for every portal scroll.")))
        scroll("home_scroll", "Home Scroll", 25, listOf("§7Teleports you to your bed."))
        scroll("unstable_scroll", "Unstable Scroll", 25, listOf("§7Teleports you back to where", "§7you last used a scroll."))
        scroll("spawn_scroll", "Spawn Scroll", 40, listOf("§7Teleports you to the world spawn."))
        scroll("lodestone_scroll", "Lodestone Scroll", 15, listOf("§7Sneak + right-click a lodestone to bind."))
        scroll("death_scroll", "Death Scroll", 2, listOf("§7Teleports you to where you", "§7recently died."))

        sickle("wooden_sickle", "Wooden Sickle", Items.WOODEN_HOE) { cfg.sickles.wooden }
        sickle("stone_sickle", "Stone Sickle", Items.STONE_HOE) { cfg.sickles.stone }
        sickle("iron_sickle", "Iron Sickle", Items.IRON_HOE) { cfg.sickles.iron }
        sickle("golden_sickle", "Golden Sickle", Items.GOLDEN_HOE) { cfg.sickles.golden }
        sickle("diamond_sickle", "Diamond Sickle", Items.DIAMOND_HOE) { cfg.sickles.diamond }
        sickle("netherite_sickle", "Netherite Sickle", Items.NETHERITE_HOE) { cfg.sickles.netherite }

        CustomItems.register(CustomItem("file", Items.WARPED_FUNGUS_ON_A_STICK, "File",
            lore = listOf("§7Right-click fences, walls, panes and", "§7stairs to reshape them. Sneak to flip stairs."),
            inhibit = setOf(Inhibit.VANILLA_RECIPES, Inhibit.OFFHAND), decorate = durability(4000)))
        CustomItems.register(CustomItem("trowel", Items.WARPED_FUNGUS_ON_A_STICK, "Trowel",
            inhibit = setOf(Inhibit.VANILLA_RECIPES, Inhibit.OFFHAND)) { s ->
            durability(800)(s)
            Trowel.updateLore(s)
        })

        CustomItems.register(CustomItem("empty_xp_bottle", Items.GLASS_BOTTLE, "Empty Experience Bottle",
            lore = listOf("§7Right-click to bottle your experience."),
            inhibit = setOf(Inhibit.VANILLA_RECIPES, Inhibit.OFFHAND)))
        xpBottle("small_xp_bottle", "Small Experience Bottle") { cfg.xpBottles.smallLevels }
        xpBottle("medium_xp_bottle", "Medium Experience Bottle") { cfg.xpBottles.mediumLevels }
        xpBottle("large_xp_bottle", "Large Experience Bottle") { cfg.xpBottles.largeLevels }

        CustomItems.register(CustomItem("reinforced_elytra", Items.ELYTRA, "Reinforced Elytra",
            inhibit = setOf(Inhibit.VANILLA_RECIPES)) { s ->
            s.set(DataComponents.MAX_DAMAGE, 864)
            val mods = ItemAttributeModifiers.builder()
                .add(Attributes.ARMOR, AttributeModifier(Identifier.fromNamespaceAndPath("rane", "reinforced_elytra"), cfg.reinforcedElytraArmor, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.CHEST)
                .build()
            s.set(DataComponents.ATTRIBUTE_MODIFIERS, mods)
        })

        CustomItems.register(CustomItem("north_compass", Items.COMPASS, "North Compass",
            lore = listOf("§7Always points north."), inhibit = setOf(Inhibit.VANILLA_RECIPES)) { s ->
            pointNorth(s, Level.OVERWORLD)
        })

        CustomItems.register(CustomItem("slime_bucket", Items.SLIME_BALL, "Slime in a Bucket",
            lore = listOf("§7It jumps around in slime chunks.", "§7Right-click a block to release it."),
            inhibit = emptySet()) { s -> s.set(DataComponents.MAX_STACK_SIZE, 1) })

        CustomItems.register(CustomItem("pouch", Items.DROPPER, "Pouch",
            lore = listOf("§7Right-click to open."), inhibit = setOf(Inhibit.VANILLA_RECIPES, Inhibit.PLACE)) { s ->
            s.set(DataComponents.MAX_STACK_SIZE, 1)
        })
        CustomItems.register(CustomItem("backpack", Items.SHULKER_BOX, "Backpack",
            lore = listOf("§7Right-click to open."), inhibit = setOf(Inhibit.VANILLA_RECIPES, Inhibit.PLACE)))

        recipes()
    }

    fun pointNorth(s: ItemStack, dimension: ResourceKey<Level>) {
        s.set(DataComponents.LODESTONE_TRACKER, LodestoneTracker(Optional.of(GlobalPos.of(dimension, BlockPos(0, 64, -29_999_000))), false))
    }

    private fun recipes() {
        val scrollKey = mapOf('P' to "rane:papyrus_scroll", 'E' to "minecraft:ender_pearl")
        Recipes.shaped("papyrus_scroll", Result("papyrus_scroll"), "RPP", "PEP", "PPG",
            key = mapOf('P' to "minecraft:paper", 'R' to "minecraft:rabbit_hide", 'E' to "minecraft:echo_shard", 'G' to "minecraft:gold_nugget"))
        Recipes.shaped("home_scroll", Result("home_scroll"), "ABC", "EPE",
            key = scrollKey + mapOf('A' to "minecraft:campfire", 'B' to "minecraft:goat_horn", 'C' to "#minecraft:beds"))
        Recipes.shaped("unstable_scroll", Result("unstable_scroll"), "ABA", "EPE",
            key = mapOf('P' to "rane:papyrus_scroll", 'E' to "minecraft:chorus_fruit", 'A' to "minecraft:amethyst_shard", 'B' to "minecraft:compass"))
        Recipes.shaped("spawn_scroll", Result("spawn_scroll"), "ABA", "EPE",
            key = scrollKey + mapOf('A' to "minecraft:wheat_seeds", 'B' to "#minecraft:saplings"))
        Recipes.shaped("lodestone_scroll", Result("lodestone_scroll"), "ABA", "EPE",
            key = scrollKey + mapOf('A' to "minecraft:amethyst_shard", 'B' to "minecraft:netherite_ingot"))
        Recipes.shaped("death_scroll", Result("death_scroll"), "ABA", "EPE",
            key = scrollKey + mapOf('A' to "minecraft:bone", 'B' to "minecraft:recovery_compass"))

        val sickle = arrayOf(" MM", "  M", " S ")
        Recipes.shaped("wooden_sickle", Result("wooden_sickle"), *sickle, category = "equipment", key = mapOf('M' to "#minecraft:planks", 'S' to "minecraft:stick"))
        Recipes.shaped("stone_sickle", Result("stone_sickle"), *sickle, category = "equipment", key = mapOf('M' to "#minecraft:stone_tool_materials", 'S' to "minecraft:stick"))
        Recipes.shaped("iron_sickle", Result("iron_sickle"), *sickle, category = "equipment", key = mapOf('M' to "minecraft:iron_ingot", 'S' to "minecraft:stick"))
        Recipes.shaped("golden_sickle", Result("golden_sickle"), *sickle, category = "equipment", key = mapOf('M' to "minecraft:gold_ingot", 'S' to "minecraft:stick"))
        Recipes.shaped("diamond_sickle", Result("diamond_sickle"), *sickle, category = "equipment", key = mapOf('M' to "minecraft:diamond", 'S' to "minecraft:stick"))
        Recipes.smithing("netherite_sickle", Result("netherite_sickle"), "minecraft:netherite_upgrade_smithing_template", "rane:diamond_sickle", "minecraft:netherite_ingot")

        Recipes.shaped("file", Result("file"), " M", "S ", category = "equipment", key = mapOf('M' to "minecraft:iron_ingot", 'S' to "minecraft:stick"))
        Recipes.shaped("trowel", Result("trowel"), "  S", "MM ", category = "equipment", key = mapOf('M' to "minecraft:iron_ingot", 'S' to "minecraft:stick"))
        Recipes.shapeless("empty_xp_bottle", Result("empty_xp_bottle"), "minecraft:experience_bottle", "minecraft:glass_bottle", "minecraft:gold_nugget")
        Recipes.smithing("reinforced_elytra", Result("reinforced_elytra"), null, "minecraft:elytra", "minecraft:netherite_ingot")
        Recipes.shaped("north_compass", Result("north_compass"), " M ", "MRM", " M ", category = "equipment",
            key = mapOf('M' to "minecraft:copper_ingot", 'R' to "minecraft:redstone"))
        Recipes.shaped("pouch", Result("pouch"), "SLS", "L L", "LLL", category = "equipment",
            key = mapOf('S' to "minecraft:string", 'L' to "minecraft:rabbit_hide"))
        Recipes.smithing("backpack", Result("backpack"), null, "#minecraft:shulker_boxes", "minecraft:leather_chestplate")
    }
}
