package me.shro.rane.greylist

import com.mojang.brigadier.arguments.StringArgumentType
import kotlinx.serialization.Serializable
import me.shro.rane.Rane
import me.shro.rane.core.Config
import me.shro.rane.core.Perms
import me.shro.rane.core.RaneModule
import me.shro.rane.core.Scheduler
import me.shro.rane.core.Store
import me.shro.rane.core.txt
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.GameType
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class GreylistConfig(
    /** Shown on the verification web pages. */
    val serverName: String = "Minecraft server",
    /** OAuth2 client id and secret from the Discord developer portal (no bot needed). */
    val clientId: String = "",
    /** Leave empty to read it from the RANE_DISCORD_CLIENT_SECRET environment variable. */
    val clientSecret: String = "",
    /**
     * Where the verification web server is reachable from players' browsers, without a trailing
     * slash. `<publicUrl>/callback` must be added as a redirect in the Discord developer portal.
     */
    val publicUrl: String = "",
    val bindAddress: String = "0.0.0.0",
    val port: Int = 10001,
    /** Players must be in at least one of these Discord servers (guild ids) when they verify. */
    val requiredGuilds: List<String> = emptyList(),
    /** Optional invite shown to people who aren't in a required server yet. */
    val inviteUrl: String = "",
    /** How long a verification link stays valid. A fresh one is made whenever the old one runs out. */
    val linkExpiryMinutes: Long = 30,
    val restrictions: Restrictions = Restrictions(),
    /** Server commands run (as the console) after a player verifies; `{player}` and `{discord}` are replaced. */
    val onLinkCommands: List<String> = emptyList(),
    /** Server commands run after an admin unlinks a player. */
    val onUnlinkCommands: List<String> = emptyList(),
) {
    @Serializable
    data class Restrictions(
        /** Put greylisted players in adventure mode while they look around. */
        val adventureMode: Boolean = true,
        val allowChat: Boolean = false,
        /** Greylisted players can't be hurt (they can't defend themselves either). */
        val invulnerable: Boolean = true,
        /** Remind on the action bar every this many seconds (0 = off). */
        val reminderSeconds: Int = 30,
    )
}

@Serializable
data class Link(val discordId: String, val discordName: String, val playerName: String, val linkedAt: Long)

@Serializable
data class GreylistData(
    val links: MutableMap<String, Link> = mutableMapOf(),
    /** Players we moved to adventure mode, so we only restore those. */
    val parked: MutableSet<String> = mutableSetOf(),
)

/**
 * Discord-backed greylist. New players can look around but not touch anything until they click the
 * link in chat and sign in with Discord; if they're in one of the required servers at that moment,
 * the account is approved for good. The approval is stored per world.
 */
object Greylist : RaneModule("greylist", "Players must prove they're in your Discord server (via Discord sign-in) before they can play.") {
    lateinit var config: GreylistConfig
        private set
    val store = Store.of("greylist") { GreylistData() }

    private class Pending(val token: String, val uuid: UUID, val name: String, val expires: Long)

    private val pendingByToken = ConcurrentHashMap<String, Pending>()
    private val pendingByPlayer = ConcurrentHashMap<UUID, Pending>()
    private val random = SecureRandom()
    private var web: DiscordOAuth? = null

    fun isLinked(uuid: UUID) = store.data.links.containsKey(uuid.toString())

    /** Greylisted = not linked and not exempt. */
    fun isGreylisted(player: Player): Boolean {
        if (!Rane.isEnabled(id)) return false
        val sp = player as? ServerPlayer ?: return false
        if (isLinked(sp.uuid)) return false
        return !Perms.check(sp, "rane.greylist.bypass", PermissionLevel.GAMEMASTERS)
    }

    override fun init() {
        config = Config.load("greylist", GreylistConfig())

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> Scheduler.nextTick { onJoin(handler.player) } }

        val deny = { player: Player -> if (isGreylisted(player)) InteractionResult.FAIL else InteractionResult.PASS }
        UseBlockCallback.EVENT.register { p, _, _, _ -> deny(p) }
        UseItemCallback.EVENT.register { p, _, _ -> deny(p) }
        UseEntityCallback.EVENT.register { p, _, _, _, _ -> deny(p) }
        AttackBlockCallback.EVENT.register { p, _, _, _, _ -> deny(p) }
        AttackEntityCallback.EVENT.register { p, _, _, _, _ -> deny(p) }
        PlayerBlockBreakEvents.BEFORE.register { _, p, _, _, _ -> !isGreylisted(p) }
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { _, sender, _ ->
            if (config.restrictions.allowChat || !isGreylisted(sender)) true
            else { remind(sender, chat = true); false }
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            !(config.restrictions.invulnerable && entity is ServerPlayer && isGreylisted(entity))
        }
        if (config.restrictions.reminderSeconds > 0) {
            Scheduler.repeat(config.restrictions.reminderSeconds * 20L) {
                if (Rane.isEnabled(id)) Rane.server.playerList.players.filter(::isGreylisted).forEach { remind(it, chat = false) }
                true
            }
        }

        CommandRegistrationCallback.EVENT.register { d, _, _ ->
            d.register(literal("greylist").requires(Perms.require("rane.greylist.admin"))
                .then(literal("status").then(argument("player", StringArgumentType.word())
                    .suggests { c, b -> SharedSuggestionProvider.suggest(c.source.onlinePlayerNames, b) }
                    .executes { ctx ->
                        val name = StringArgumentType.getString(ctx, "player")
                        val link = store.data.links.values.firstOrNull { it.playerName.equals(name, true) }
                        ctx.source.sendSystemMessage(txt(
                            if (link == null) "§e$name§7 is not linked." else "§e$name§7 is linked to Discord §b${link.discordName}§7 (${link.discordId})."))
                        1
                    }))
                .then(literal("approve").then(argument("player", StringArgumentType.word())
                    .suggests { c, b -> SharedSuggestionProvider.suggest(c.source.onlinePlayerNames, b) }
                    .executes { ctx ->
                        val name = StringArgumentType.getString(ctx, "player")
                        val p = ctx.source.server.playerList.getPlayerByName(name)
                        if (p == null) { ctx.source.sendFailure(txt("$name must be online to be approved.")); return@executes 0 }
                        link(p.uuid, p.gameProfile.name(), "manual", "approved by ${ctx.source.textName}")
                        ctx.source.sendSuccess({ txt("§aApproved §e$name§a without Discord.") }, true)
                        1
                    }))
                .then(literal("unlink").then(argument("player", StringArgumentType.word())
                    .suggests { _, b -> SharedSuggestionProvider.suggest(store.data.links.values.map { it.playerName }, b) }
                    .executes { ctx ->
                        val name = StringArgumentType.getString(ctx, "player")
                        val entry = store.data.links.entries.firstOrNull { it.value.playerName.equals(name, true) }
                        if (entry == null) { ctx.source.sendFailure(txt("$name is not linked.")); return@executes 0 }
                        store.edit { links.remove(entry.key) }
                        runCommands(config.onUnlinkCommands, entry.value.playerName, entry.value.discordName)
                        ctx.source.server.playerList.getPlayer(UUID.fromString(entry.key))?.let { onJoin(it) }
                        ctx.source.sendSuccess({ txt("§eUnlinked $name.") }, true)
                        1
                    })))
            d.register(literal("verify").requires(Perms.everyone("rane.greylist.verify")).executes { ctx ->
                val p = ctx.source.playerOrException
                if (!isGreylisted(p)) ctx.source.sendSystemMessage(txt("§aYou're already verified."))
                else remind(p, chat = true)
                1
            })
        }
    }

    override fun serverStarted(server: MinecraftServer) {
        val secret = config.clientSecret.ifBlank { System.getenv("RANE_DISCORD_CLIENT_SECRET").orEmpty() }
        val missing = listOfNotNull(
            "clientId".takeIf { config.clientId.isBlank() },
            "clientSecret".takeIf { secret.isBlank() },
            "publicUrl".takeIf { config.publicUrl.isBlank() },
            "requiredGuilds".takeIf { config.requiredGuilds.isEmpty() },
        )
        if (missing.isNotEmpty()) {
            log.warn("Discord verification is off until config/rane/greylist.json sets {}. Players can only be approved with /greylist approve.",
                missing.joinToString())
            return
        }
        web = try {
            DiscordOAuth(config.copy(clientSecret = secret), log, ::pendingForWeb, ::onVerified).also { it.start() }
        } catch (e: Exception) {
            log.error("Could not start the verification web server on port {}", config.port, e)
            null
        }
    }

    override fun serverStopping(server: MinecraftServer) {
        web?.stop()
        web = null
    }

    // ------------------------------------------------------------------ in game

    private fun onJoin(player: ServerPlayer) {
        if (!Rane.isEnabled(id) || player.hasDisconnected()) return
        val key = player.uuid.toString()
        if (!isGreylisted(player)) {
            store.data.links[key]?.takeIf { it.playerName != player.gameProfile.name() }?.let {
                store.edit { links[key] = it.copy(playerName = player.gameProfile.name()) }
            }
            if (key in store.data.parked) release(player)
            return
        }
        if (config.restrictions.adventureMode && player.gameMode() != GameType.ADVENTURE) {
            player.setGameMode(GameType.ADVENTURE)
            store.edit { parked += key }
        }
        remind(player, chat = true)
    }

    private fun pendingFor(player: ServerPlayer): Pending {
        val now = System.currentTimeMillis()
        pendingByPlayer[player.uuid]?.takeIf { it.expires > now }?.let { return it }
        pendingByPlayer.remove(player.uuid)?.let { pendingByToken.remove(it.token) }
        pendingByToken.values.removeIf { it.expires <= now }
        val bytes = ByteArray(18).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val p = Pending(token, player.uuid, player.gameProfile.name(), now + config.linkExpiryMinutes * 60_000)
        pendingByToken[token] = p
        pendingByPlayer[player.uuid] = p
        return p
    }

    private fun pendingForWeb(token: String) = pendingByToken[token]
        ?.takeIf { it.expires > System.currentTimeMillis() && !isLinked(it.uuid) }
        ?.let { DiscordOAuth.PendingVerification(it.token, it.name) }

    private fun onVerified(p: DiscordOAuth.PendingVerification, user: DiscordOAuth.DiscordUser, allowed: Boolean) {
        Rane.server.execute {
            val pending = pendingByToken[p.token] ?: return@execute
            if (!allowed) {
                log.info("{} signed in as Discord {} ({}) but isn't in a required server", pending.name, user.name, user.id)
                Rane.server.playerList.getPlayer(pending.uuid)?.sendSystemMessage(
                    txt("§cDiscord account §b${user.name}§c isn't in our Discord server. Join it, then click the link again."))
                return@execute
            }
            link(pending.uuid, pending.name, user.id, user.name)
        }
    }

    private fun remind(player: ServerPlayer, chat: Boolean) {
        if (web == null) {
            if (chat) player.sendSystemMessage(txt("§6§lGreylist active.§r§7 You can look around, but an admin needs to approve you before you can play."))
            else player.sendOverlayMessage(txt("§7Waiting for an admin to approve you"))
            return
        }
        if (!chat) {
            player.sendOverlayMessage(txt("§7Click the link in chat to verify with Discord §8(/verify)"))
            return
        }
        val url = web!!.verifyUrl(pendingFor(player).token)
        val button = Component.literal("[Verify with Discord]").withStyle { s ->
            s.withColor(0x5865F2).withBold(true)
                .withClickEvent(ClickEvent.OpenUrl(URI(url)))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("Opens Discord sign-in in your browser")))
        }
        val lines = Component.empty()
            .append(txt("§6§lGreylist active.§r§7 You can look around, but you need to verify before you can build, chat or pick things up.\n"))
            .append(txt("§7Sign in with Discord to show you're in our server: "))
            .append(button)
        if (config.inviteUrl.isNotBlank()) {
            lines.append(txt("\n§7Not in it yet? ")).append(Component.literal("[Join our Discord]").withStyle { s ->
                s.withColor(ChatFormatting.AQUA).withClickEvent(ClickEvent.OpenUrl(URI(config.inviteUrl)))
            })
        }
        player.sendSystemMessage(lines)
    }

    private fun release(player: ServerPlayer) {
        val key = player.uuid.toString()
        if (key in store.data.parked) {
            store.edit { parked -= key }
            if (player.gameMode() == GameType.ADVENTURE) player.setGameMode(player.level().server.defaultGameType)
        }
    }

    /** Must run on the server thread. */
    private fun link(uuid: UUID, playerName: String, discordId: String, discordName: String) {
        store.edit { links[uuid.toString()] = Link(discordId, discordName, playerName, System.currentTimeMillis()) }
        pendingByPlayer.remove(uuid)?.let { pendingByToken.remove(it.token) }
        runCommands(config.onLinkCommands, playerName, discordName)
        Rane.server.playerList.getPlayer(uuid)?.let { p ->
            release(p)
            p.sendSystemMessage(txt(if (discordId == "manual") "§aYou've been approved. Have fun!" else "§aYou're verified (Discord §b$discordName§a). Have fun!"))
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.2f)
        }
        log.info("Approved {} ({}) via Discord {} ({})", playerName, uuid, discordName, discordId)
    }

    private fun runCommands(commands: List<String>, player: String, discord: String) {
        val server = Rane.server
        for (c in commands) {
            val cmd = c.replace("{player}", player).replace("{discord}", discord).removePrefix("/")
            server.commands.performPrefixedCommand(server.createCommandSourceStack(), cmd)
        }
    }
}
