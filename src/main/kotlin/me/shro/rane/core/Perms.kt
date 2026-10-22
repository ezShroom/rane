package me.shro.rane.core

import me.lucko.fabric.api.permissions.v0.Permissions
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionLevel
import java.util.function.Predicate

/**
 * Permission checks go through fabric-permissions-api, so LuckPerms can manage them; without a
 * permissions mod they fall back to the given op level.
 */
object Perms {
    fun check(player: ServerPlayer, node: String, fallback: PermissionLevel = PermissionLevel.GAMEMASTERS): Boolean =
        Permissions.check(player, node, fallback)

    fun check(source: CommandSourceStack, node: String, fallback: PermissionLevel = PermissionLevel.GAMEMASTERS): Boolean =
        Permissions.check(source, node, fallback)

    fun require(node: String, fallback: PermissionLevel = PermissionLevel.GAMEMASTERS): Predicate<CommandSourceStack> =
        Predicate { check(it, node, fallback) }

    /** Granted to everyone unless a permissions plugin says otherwise. */
    fun everyone(node: String): Predicate<CommandSourceStack> = Predicate { Permissions.check(it, node, true) }
}
