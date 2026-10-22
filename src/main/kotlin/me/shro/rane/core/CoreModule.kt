package me.shro.rane.core

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import me.shro.rane.Rane
import me.shro.rane.item.CustomItems
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.EntityArgument

/** Shared infrastructure plus the `/rane` command. */
object CoreModule : RaneModule("core", "Shared infrastructure and the /rane command.") {
    override fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("rane")
                    .requires(Perms.everyone("rane.command"))
                    .executes { ctx ->
                        ctx.source.sendSystemMessage(txt("§6rane§7 — a Fabric port of vane. Modules: §b" + Rane.enabled.joinToString("§7, §b") { it.id }))
                        1
                    }
                    .then(
                        literal("give")
                            .requires(Perms.require("rane.command.give"))
                            .then(argument("targets", EntityArgument.players())
                                .then(argument("item", StringArgumentType.word())
                                    .suggests { _, b -> SharedSuggestionProvider.suggest(CustomItems.all.map { it.id }, b) }
                                    .executes { ctx -> give(ctx, 1) }
                                    .then(argument("count", IntegerArgumentType.integer(1, 6400))
                                        .executes { ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "count")) })))
                    )
            )
        }
    }

    private fun give(ctx: com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack>, count: Int): Int {
        val id = StringArgumentType.getString(ctx, "item")
        val item = CustomItems[id] ?: run {
            ctx.source.sendFailure(txt("Unknown rane item: $id"))
            return 0
        }
        val players = EntityArgument.getPlayers(ctx, "targets")
        for (p in players) {
            var left = count
            while (left > 0) {
                val stack = item.create(1)
                val n = minOf(left, stack.maxStackSize)
                stack.count = n
                left -= n
                giveItem(p, stack)
            }
        }
        ctx.source.sendSuccess({ txt("Gave $count ${item.name} to ${players.size} player(s)") }, true)
        return players.size
    }
}
