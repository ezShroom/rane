package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.brigadier.arguments.StringArgumentType
import eu.pb4.sgui.api.ClickType
import eu.pb4.sgui.api.elements.GuiElementBuilder
import eu.pb4.sgui.api.gui.AnvilInputGui
import eu.pb4.sgui.api.gui.SimpleGui
import me.shro.rane.core.Perms
import me.shro.rane.core.giveItem
import me.shro.rane.core.plain
import me.shro.rane.core.txt
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream

/** `/heads`: over 32,000 decorative heads, bought with bones. */
object Heads {
    data class Head(val id: String, val name: String, val category: String, val tags: List<String>, val hash: String)

    val library: List<Head> by lazy {
        val stream = Heads::class.java.getResourceAsStream("/rane/head_library.json.gz") ?: return@lazy emptyList()
        GZIPInputStream(stream).reader().use { r ->
            fun com.google.gson.JsonObject.str(k: String) = get(k)?.takeUnless { it.isJsonNull }?.asString
            JsonParser.parseReader(r).asJsonArray.mapNotNull { e ->
                val o = e.asJsonObject
                val hash = o.str("hash") ?: return@mapNotNull null
                val name = o.str("name") ?: return@mapNotNull null
                Head(o.str("id") ?: hash, name, o.str("category") ?: "misc",
                    o.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { t -> t.takeUnless { it.isJsonNull }?.asString?.trim('"') } ?: emptyList(),
                    hash)
            }
        }
    }
    private val categories by lazy { listOf("all") + library.map { it.category }.distinct().sorted() }
    private val cfg get() = Trifles.config.heads
    private val currency: Item get() = BuiltInRegistries.ITEM.getOptional(Identifier.parse(cfg.currency)).orElse(Items.BONE)

    fun init() {
        if (!cfg.enabled) return
        CommandRegistrationCallback.EVENT.register { d, _, _ ->
            d.register(literal("heads").requires(Perms.everyone("rane.trifles.heads"))
                .executes { ctx -> open(ctx.source.playerOrException, "all", ""); 1 }
                .then(argument("search", StringArgumentType.greedyString()).executes { ctx ->
                    open(ctx.source.playerOrException, "all", StringArgumentType.getString(ctx, "search")); 1
                }))
        }
    }

    fun headStack(h: Head, count: Int = 1): ItemStack {
        val stack = ItemStack(Items.PLAYER_HEAD, count)
        val textures = Base64.getEncoder().encodeToString("""{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/${h.hash}"}}}""".toByteArray())
        val profile = GameProfile(UUID.nameUUIDFromBytes(h.hash.toByteArray()), "rane_head",
            com.mojang.authlib.properties.PropertyMap(com.google.common.collect.ImmutableMultimap.of("textures", Property("textures", textures))))
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
        stack.set(DataComponents.ITEM_NAME, Component.literal(h.name))
        return stack
    }

    private fun filtered(category: String, search: String): List<Head> {
        val q = search.trim().lowercase()
        return library.filter { h ->
            (category == "all" || h.category == category) &&
                (q.isEmpty() || h.name.lowercase().contains(q) || h.tags.any { it.lowercase().contains(q) })
        }
    }

    fun open(player: ServerPlayer, category: String, search: String, page: Int = 0) {
        try {
            openUnsafe(player, category, search, page)
        } catch (e: Exception) {
            Trifles.log.error("Could not open the heads menu", e)
            player.sendSystemMessage(me.shro.rane.core.txt("§cThe heads menu failed to open; see the server log."))
        }
    }

    private fun openUnsafe(player: ServerPlayer, category: String, search: String, page: Int) {
        val heads = filtered(category, search)
        val pages = maxOf(1, (heads.size + 44) / 45)
        val p = page.coerceIn(0, pages - 1)
        val gui = SimpleGui(MenuType.GENERIC_9x6, player, false)
        gui.setTitle(txt("§8Heads ${if (search.isNotBlank()) "\"$search\" " else ""}§7(${p + 1}/$pages)"))
        heads.drop(p * 45).take(45).forEachIndexed { i, h ->
            val price = if (cfg.price > 0) "§7Price: §e${cfg.price} ${currency.displayName}§7 each" else "§aFree"
            gui.setSlot(i, GuiElementBuilder.from(headStack(h))
                .setLore(listOf(plain("§8${h.category}"), plain(price), plain("§8Click: 1 · Shift: 64 · 1-9: that many")))
                .setCallback { _, type, _, _ ->
                    val n = when {
                        type.shift -> 64
                        type.numKey -> type.value
                        else -> 1
                    }
                    buy(player, h, n)
                })
        }
        val nav = { item: Item, name: String -> GuiElementBuilder(item).setName(plain(name)) }
        if (p > 0) gui.setSlot(45, nav(Items.ARROW, "§ePrevious page").setCallback { _, _, _, _ -> open(player, category, search, p - 1) })
        gui.setSlot(47, nav(Items.BOOK, "§eCategory: §b$category").setLore(listOf(plain("§7Click to cycle, right-click to go back")))
            .setCallback { _, type, _, _ ->
                val i = categories.indexOf(category)
                val next = if (type.isRight) categories[(i - 1 + categories.size) % categories.size] else categories[(i + 1) % categories.size]
                open(player, next, search, 0)
            })
        gui.setSlot(49, nav(Items.NAME_TAG, "§eSearch").setLore(listOf(plain("§7Current: §f${search.ifBlank { "—" }}"), plain("§7Right-click to clear")))
            .setCallback { _, type, _, _ ->
                if (type.isRight) open(player, category, "", 0) else searchPrompt(player, category, search)
            })
        gui.setSlot(51, nav(currency, "§7You have §e${countCurrency(player)}§7 ${currency.displayName}"))
        if (p < pages - 1) gui.setSlot(53, nav(Items.ARROW, "§eNext page").setCallback { _, _, _, _ -> open(player, category, search, p + 1) })
        gui.open()
    }

    private fun searchPrompt(player: ServerPlayer, category: String, current: String) {
        val gui = object : AnvilInputGui(player, false) {}
        gui.setTitle(txt("Search heads"))
        gui.setDefaultInputValue(current)
        gui.setSlot(2, GuiElementBuilder(Items.SPYGLASS).setName(plain("§aSearch")).setCallback { _, _, _, _ ->
            open(player, category, gui.input, 0)
        })
        gui.open()
    }

    private fun countCurrency(player: ServerPlayer): Int {
        val inv = player.inventory
        return (0 until inv.containerSize).map { inv.getItem(it) }.filter { it.`is`(currency) && !me.shro.rane.item.CustomItems.isCustom(it) }.sumOf { it.count }
    }

    private fun buy(player: ServerPlayer, h: Head, n: Int) {
        val cost = cfg.price * n
        if (cost > 0 && !player.hasInfiniteMaterials()) {
            if (countCurrency(player) < cost) {
                player.sendOverlayMessage(txt("§cYou need $cost ${currency.displayName}."))
                return
            }
            var left = cost
            val inv = player.inventory
            for (i in 0 until inv.containerSize) {
                val s = inv.getItem(i)
                if (left == 0) break
                if (!s.`is`(currency) || me.shro.rane.item.CustomItems.isCustom(s)) continue
                val take = minOf(left, s.count)
                s.shrink(take)
                left -= take
            }
        }
        giveItem(player, headStack(h, n))
        player.notifySound(SoundEvents.UI_STONECUTTER_TAKE_RESULT, 1f, 1f)
    }
}
