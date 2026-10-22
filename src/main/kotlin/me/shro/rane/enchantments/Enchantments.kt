package me.shro.rane.enchantments

import com.mojang.serialization.MapCodec
import kotlinx.serialization.Serializable
import me.shro.rane.Rane
import me.shro.rane.core.Config
import me.shro.rane.core.Options
import me.shro.rane.core.RaneModule
import me.shro.rane.item.CustomItem
import me.shro.rane.item.CustomItems
import me.shro.rane.item.Inhibit
import me.shro.rane.item.Recipes
import me.shro.rane.item.Recipes.Result
import net.fabricmc.fabric.api.loot.v3.LootTableEvents
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.Rarity
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.level.storage.loot.LootPool
import net.minecraft.world.level.storage.loot.LootTable
import net.minecraft.world.level.storage.loot.entries.LootItem
import net.minecraft.world.level.storage.loot.functions.LootItemFunction
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition

@Serializable
data class EnchantmentsConfig(
    val angel: Angel = Angel(),
    val wings: Wings = Wings(),
    val takeOffStrength: List<Double> = listOf(0.2, 0.4, 0.6),
    val grapplingHook: GrapplingHook = GrapplingHook(),
    val lightning: Lightning = Lightning(),
    /** A second drop of a soulbound item within this window actually drops it. */
    val soulboundDropWindowMs: Long = 2000,
    /** Find tomes and enchanted tomes in structure chests, like vane. */
    val lootInChests: Boolean = true,
) {
    @Serializable
    data class Angel(val acceleration: Double = 0.1, val speed: List<Double> = listOf(0.7, 1.1, 1.4, 1.7, 2.0))

    @Serializable
    data class Wings(val cooldownMs: List<Int> = listOf(7000, 5000, 3500, 2800), val strength: List<Double> = listOf(0.4, 0.47, 0.54, 0.6))

    @Serializable
    data class GrapplingHook(val idealDistance: Double = 16.0, val strength: List<Double> = listOf(1.6, 2.1, 2.7))

    @Serializable
    data class Lightning(
        /** Off by default, like in vane. */
        val enabled: Boolean = false,
        val extraDamage: Int = 4,
        val protectWielder: Boolean = true,
        val worksInRain: Boolean = true,
    )
}

/** Custom enchantments (data-driven, so vanilla clients show them) obtained from ancient tomes. */
object Enchantments : RaneModule("enchantments", "Custom enchantments like Angel, Wings, Soulbound and Unbreakable, found in ancient tomes.") {
    lateinit var config: EnchantmentsConfig
        private set

    fun key(name: String): ResourceKey<Enchantment> = ResourceKey.create(Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath("rane", name))

    fun holder(name: String): Holder<Enchantment>? =
        Rane.server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key(name)).orElse(null)

    fun level(stack: ItemStack, name: String): Int {
        if (stack.isEmpty) return 0
        val h = holder(name) ?: return 0
        return EnchantmentHelper.getItemEnchantmentLevel(h, stack)
    }

    fun <T> perLevel(list: List<T>, level: Int): T = list.getOrElse(level - 1) { list.last() }

    override fun init() {
        config = Config.load("enchantments", EnchantmentsConfig())
        Options.set("enchantments.lightning", config.lightning.enabled)
        registerTomes()
        recipes()
        if (config.lootInChests) loot()
        EnchantmentEffects.init()
    }

    // ------------------------------------------------------------------ tomes

    private val tomes = listOf(
        Triple("ancient_tome", "Ancient Tome", Rarity.UNCOMMON),
        Triple("ancient_tome_of_knowledge", "Ancient Tome of Knowledge", Rarity.RARE),
        Triple("ancient_tome_of_the_gods", "Ancient Tome of the Gods", Rarity.EPIC),
    )

    private fun registerTomes() {
        for ((id, name, rarity) in tomes) {
            CustomItems.register(CustomItem(id, Items.BOOK, name, inhibit = setOf(Inhibit.VANILLA_RECIPES)) { s ->
                s.remove(DataComponents.ENCHANTABLE)
                s.set(DataComponents.RARITY, rarity)
            })
            CustomItems.register(CustomItem("enchanted_$id", Items.ENCHANTED_BOOK, "Enchanted $name", inhibit = setOf(Inhibit.VANILLA_RECIPES)) { s ->
                s.set(DataComponents.RARITY, rarity)
            })
        }
    }

    /** Enchanted tome holding [enchantment] at level 1 (as recipe result JSON). */
    private fun tomeResult(tome: String, enchantment: String) =
        Result("enchanted_$tome", extraComponents = mapOf("minecraft:stored_enchantments" to mapOf("rane:$enchantment" to 1)))

    private fun recipes() {
        Recipes.shapeless("ancient_tome_of_knowledge", Result("ancient_tome_of_knowledge"),
            "rane:ancient_tome", "minecraft:feather", "minecraft:blaze_rod", "minecraft:ghast_tear")
        Recipes.shaped("ancient_tome_of_the_gods", Result("ancient_tome_of_the_gods"), " S ", "EBE", " N ",
            key = mapOf('B' to "rane:ancient_tome_of_knowledge", 'E' to "minecraft:enchanted_book", 'S' to "minecraft:nether_star", 'N' to "minecraft:nautilus_shell"))

        val k = "ancient_tome_of_knowledge"
        val g = "ancient_tome_of_the_gods"
        Recipes.shaped("angel", tomeResult(g, "angel"), "PRP", "MBM", "MDM", key = mapOf('B' to "rane:$g",
            'M' to "minecraft:phantom_membrane", 'D' to "minecraft:dragon_breath", 'P' to "minecraft:pufferfish_bucket", 'R' to "minecraft:firework_rocket"))
        Recipes.shaped("wings", tomeResult(k, "wings"), "M M", "DBD", "R R", key = mapOf('B' to "rane:$k",
            'M' to "minecraft:phantom_membrane", 'D' to "minecraft:dispenser", 'R' to "minecraft:firework_rocket"))
        Recipes.shaped("take_off", tomeResult(g, "take_off"), "MBM", "PSP", key = mapOf('B' to "rane:$g",
            'M' to "minecraft:phantom_membrane", 'P' to "minecraft:piston", 'S' to "minecraft:slime_block"))
        Recipes.shaped("grappling_hook", tomeResult(k, "grappling_hook"), "H", "L", "B", key = mapOf('B' to "rane:$k",
            'L' to "minecraft:lead", 'H' to "minecraft:tripwire_hook"))
        Recipes.shaped("hell_bent", tomeResult(k, "hell_bent"), "M", "B", "T", key = mapOf('B' to "rane:$k",
            'T' to "minecraft:turtle_helmet", 'M' to "minecraft:music_disc_pigstep"))
        Recipes.shaped("leafchopper", tomeResult(k, "leafchopper"), " S ", "SBS", " S ", key = mapOf('B' to "rane:$k", 'S' to "minecraft:shears"))
        if (config.lightning.enabled) {
            Recipes.shaped("lightning", tomeResult(k, "lightning"), "R R", "UTU", " B ", key = mapOf('T' to "rane:$k",
                'R' to "minecraft:lightning_rod", 'B' to "minecraft:beacon", 'U' to "minecraft:totem_of_undying"))
        }
        Recipes.shaped("rake", tomeResult(k, "rake"), " H ", "HBH", " H ", key = mapOf('B' to "rane:$k", 'H' to "minecraft:golden_hoe"))
        Recipes.shaped("seeding", tomeResult(k, "seeding"), "1 7", "2B6", "345", key = mapOf('B' to "rane:$k",
            '1' to "minecraft:pumpkin_seeds", '2' to "minecraft:carrot", '3' to "minecraft:wheat_seeds", '4' to "minecraft:nether_wart",
            '5' to "minecraft:beetroot_seeds", '6' to "minecraft:potato", '7' to "minecraft:melon_seeds"))
        Recipes.shaped("soulbound", tomeResult(g, "soulbound"), "CQC", "OBE", "RGT", key = mapOf('B' to "rane:$g",
            'C' to "minecraft:iron_chain", 'Q' to "minecraft:writable_book", 'O' to "minecraft:bone", 'R' to "minecraft:enchanted_book",
            'G' to "minecraft:ghast_tear", 'T' to "minecraft:totem_of_undying", 'E' to "minecraft:ender_eye"))
        Recipes.shaped("unbreakable", tomeResult(g, "unbreakable"), "WAW", "NBN", "TST", key = mapOf('B' to "rane:$g",
            'W' to "minecraft:wither_rose", 'A' to "minecraft:enchanted_golden_apple", 'N' to "minecraft:netherite_ingot",
            'T' to "minecraft:totem_of_undying", 'S' to "minecraft:nether_star"))
    }

    // ------------------------------------------------------------------ loot

    /** Turns a plain stack into a custom item (optionally an enchanted tome) when loot is rolled. */
    private class CustomItemFunction(val id: String, val enchantment: String?, val min: Int = 1, val max: Int = 1) : LootItemFunction {
        override fun codec(): MapCodec<out LootItemFunction> = MapCodec.unit(this)

        override fun apply(stack: ItemStack, ctx: LootContext): ItemStack {
            val count = if (max > min) min + ctx.random.nextInt(max - min + 1) else min
            if (count <= 0) return ItemStack.EMPTY
            val out = CustomItems.create(id, count)
            if (enchantment != null) {
                val h = ctx.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key(enchantment)).orElse(null) ?: return out
                val e = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
                e.set(h, 1)
                out.set(DataComponents.STORED_ENCHANTMENTS, e.toImmutable())
            }
            return out
        }
    }

    private fun chest(path: String): ResourceKey<LootTable> = ResourceKey.create(Registries.LOOT_TABLE, Identifier.withDefaultNamespace(path))

    private data class Drop(val tables: List<String>, val chance: Double, val min: Int, val max: Int, val item: String, val enchantment: String? = null)

    private fun loot() {
        val tomeTables = listOf("chests/abandoned_mineshaft", "chests/bastion_bridge", "chests/bastion_hoglin_stable", "chests/bastion_other",
            "chests/bastion_treasure", "chests/buried_treasure", "chests/desert_pyramid", "chests/end_city_treasure", "gameplay/fishing/treasure",
            "chests/igloo_chest", "chests/jungle_temple", "chests/nether_bridge", "chests/pillager_outpost", "chests/ruined_portal",
            "chests/shipwreck_treasure", "chests/stronghold_library", "chests/underwater_ruin_big", "chests/underwater_ruin_small",
            "chests/village/village_temple", "chests/woodland_mansion")
        val explore = listOf("chests/buried_treasure", "chests/pillager_outpost", "chests/ruined_portal", "chests/shipwreck_treasure",
            "chests/stronghold_library", "chests/underwater_ruin_big", "chests/underwater_ruin_small", "chests/village/village_temple", "chests/woodland_mansion")
        val bastion = listOf("chests/bastion_bridge", "chests/bastion_hoglin_stable", "chests/bastion_other", "chests/bastion_treasure")
        val drops = listOf(
            Drop(tomeTables, 1.0 / 5, 0, 2, "ancient_tome"),
            Drop(listOf("chests/ancient_city"), 1.0 / 20, 0, 2, "ancient_tome"),
            Drop(tomeTables, 1.0 / 35, 1, 1, "ancient_tome_of_knowledge"),
            Drop(tomeTables, 1.0 / 200, 1, 1, "ancient_tome_of_the_gods"),
            Drop(listOf("chests/ancient_city"), 1.0 / 150, 1, 1, "ancient_tome_of_the_gods"),
            Drop(explore, 1.0 / 250, 1, 1, "enchanted_ancient_tome_of_the_gods", "angel"),
            Drop(explore, 1.0 / 110, 1, 1, "enchanted_ancient_tome_of_knowledge", "wings"),
            Drop(listOf("chests/bastion_treasure"), 1.0 / 10, 1, 1, "enchanted_ancient_tome_of_knowledge", "wings"),
            Drop(explore, 1.0 / 150, 1, 1, "enchanted_ancient_tome_of_the_gods", "take_off"),
            Drop(bastion, 1.0 / 50, 1, 1, "enchanted_ancient_tome_of_knowledge", "hell_bent"),
            Drop(listOf("chests/bastion_treasure"), 1.0 / 15, 1, 1, "enchanted_ancient_tome_of_the_gods", "soulbound"),
            Drop(listOf("chests/abandoned_mineshaft"), 1.0 / 120, 1, 1, "enchanted_ancient_tome_of_the_gods", "unbreakable"),
            Drop(listOf("chests/bastion_treasure"), 1.0 / 30, 1, 1, "enchanted_ancient_tome_of_the_gods", "unbreakable"),
        )
        val byTable = drops.flatMap { d -> d.tables.map { chest(it) to d } }.groupBy({ it.first }, { it.second })
        LootTableEvents.MODIFY.register { key, builder, source, _ ->
            if (!source.isBuiltin) return@register
            val list = byTable[key] ?: return@register
            for (d in list) {
                val base = if (d.enchantment != null) Items.ENCHANTED_BOOK else Items.BOOK
                builder.withPool(LootPool.lootPool()
                    .setRolls(Holder.direct(net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue(1)))
                    .`when`(LootItemRandomChanceCondition.randomChance(d.chance.toFloat()))
                    .add(LootItem.lootTableItem(base)
                        .apply(LootItemFunction.Builder { CustomItemFunction(d.item, d.enchantment, d.min, d.max) })))
            }
        }
    }
}
