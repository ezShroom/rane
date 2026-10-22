package me.shro.rane.greylist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import java.security.SecureRandom
import java.util.UUID

@Serializable
data class GreylistConfig(
    /** Bot token. Leave empty to read it from the RANE_DISCORD_TOKEN environment variable. */
    val botToken: String = "",
    /** Discord server (guild) ids where `/link` works. Commands are only registered there. */
    val allowedGuilds: List<String> = emptyList(),
    val commandName: String = "link",
    val codeLength: Int = 6,
    val codeExpiryMinutes: Long = 60,
    /** How many Minecraft accounts one Discord account may link. 0 = unlimited. */
    val maxAccountsPerDiscordUser: Int = 1,
    val restrictions: Restrictions = Restrictions(),
    /** Server commands run (as the console) after a player links; `{player}` and `{discord}` are replaced. */
    val onLinkCommands: List<String> = emptyList(),
    /** Server commands run after an admin unlinks a player. */
    val onUnlinkCommands: List<String> = emptyList(),
    val discordInviteHint: String = "",
) {
    @Serializable
    data class Restrictions(
        /** Put greylisted players in adventure mode while they look around. */
        val adventureMode: Boolean = true,
        val allowChat: Boolean = false,
        /** Greylisted players can't be hurt (they can't defend themselves either). */
        val invulnerable: Boolean = true,
        /** Re-show the code on the action bar every this many seconds (0 = off). */
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
 * Discord-backed greylist. New players can look around but not touch anything until they run
 * `/link <code>` in an allowed Discord server; the association is stored per world.
 */
object Greylist : RaneModule("greylist", "Players must link a Discord account (with a code shown in chat) before they can play.") {
    lateinit var config: GreylistConfig
        private set
    val store = Store.of("greylist") { GreylistData() }

    private class Pending(val code: String, val uuid: UUID, val name: String, val expires: Long)

    private val pendingByCode = mutableMapOf<String, Pending>()
    private val pendingByPlayer = mutableMapOf<UUID, Pending>()
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private var bot: DiscordGateway? = null

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
            d.register(literal("linkcode").requires(Perms.everyone("rane.greylist.code")).executes { ctx ->
                val p = ctx.source.playerOrException
                if (!isGreylisted(p)) ctx.source.sendSystemMessage(txt("§aYou're already verified."))
                else remind(p, chat = true)
                1
            })
        }
    }

    override fun serverStarted(server: MinecraftServer) {
        val token = config.botToken.ifBlank { System.getenv("RANE_DISCORD_TOKEN").orEmpty() }
        if (token.isBlank()) {
            log.warn("No Discord bot token set (config/rane/greylist.json or RANE_DISCORD_TOKEN). Players can only be approved with /greylist approve.")
            return
        }
        if (config.allowedGuilds.isEmpty()) log.warn("No allowedGuilds configured; /${config.commandName} won't be available anywhere.")
        bot = DiscordGateway(token, ::registerCommands, ::onInteraction).also { it.start() }
    }

    override fun serverStopping(server: MinecraftServer) {
        bot?.stop()
        bot = null
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

    private fun codeFor(player: ServerPlayer): Pending {
        val now = System.currentTimeMillis()
        pendingByPlayer[player.uuid]?.takeIf { it.expires > now }?.let { return it }
        pendingByPlayer.remove(player.uuid)?.let { pendingByCode.remove(it.code) }
        pendingByCode.values.removeIf { it.expires <= now }
        var code: String
        do {
            code = (1..config.codeLength).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        } while (code in pendingByCode)
        val p = Pending(code, player.uuid, player.gameProfile.name(), now + config.codeExpiryMinutes * 60_000)
        pendingByCode[code] = p
        pendingByPlayer[player.uuid] = p
        return p
    }

    private fun remind(player: ServerPlayer, chat: Boolean) {
        val code = codeFor(player).code
        val command = "/${config.commandName} $code"
        if (!chat) {
            player.sendOverlayMessage(txt("§7Verify on Discord: §b$command"))
            return
        }
        val codeText = Component.literal(code).withStyle { s ->
            s.withColor(ChatFormatting.AQUA).withBold(true)
                .withClickEvent(ClickEvent.CopyToClipboard(command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal("Click to copy $command")))
        }
        val lines = Component.empty()
            .append(txt("§6§lWelcome!§r§7 You can look around, but you need to verify before you can play.\n"))
            .append(txt("§7Run §f/${config.commandName} "))
            .append(codeText)
            .append(txt("§7 in our Discord server to link your account."))
        if (config.discordInviteHint.isNotBlank()) lines.append(txt("\n§7${config.discordInviteHint}"))
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
        pendingByPlayer.remove(uuid)?.let { pendingByCode.remove(it.code) }
        runCommands(config.onLinkCommands, playerName, discordName)
        Rane.server.playerList.getPlayer(uuid)?.let { p ->
            release(p)
            p.sendSystemMessage(txt("§aYou're verified! Linked to Discord account §b$discordName§a. Have fun!"))
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7f, 1.2f)
        }
        log.info("Linked {} ({}) to Discord {} ({})", playerName, uuid, discordName, discordId)
    }

    private fun runCommands(commands: List<String>, player: String, discord: String) {
        val server = Rane.server
        for (c in commands) {
            val cmd = c.replace("{player}", player).replace("{discord}", discord).removePrefix("/")
            server.commands.performPrefixedCommand(server.createCommandSourceStack(), cmd)
        }
    }

    // ------------------------------------------------------------------ discord

    private fun registerCommands(appId: String, botGuilds: List<String>) {
        val cmd = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("name", config.commandName)
                addProperty("description", "Link your Minecraft account using the code shown in game")
                addProperty("type", 1)
                add("contexts", JsonArray().apply { add(0) })
                add("options", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", 3)
                        addProperty("name", "code")
                        addProperty("description", "The code shown to you in Minecraft")
                        addProperty("required", true)
                        addProperty("min_length", config.codeLength)
                        addProperty("max_length", config.codeLength)
                    })
                })
            })
        }
        for (guild in config.allowedGuilds) {
            if (guild !in botGuilds) log.warn("The bot is not in allowed guild {}; invite it there for /{} to show up", guild, config.commandName)
            bot?.restAsync("PUT", "/applications/$appId/guilds/$guild/commands", cmd)
        }
    }

    private fun onInteraction(i: JsonObject) {
        if (i["type"].asInt != 2) return
        val data = i["data"].asJsonObject
        if (data["name"].asString != config.commandName) return
        val gateway = bot ?: return
        val guild = i["guild_id"]?.takeUnless { it.isJsonNull }?.asString
        if (guild == null || guild !in config.allowedGuilds) {
            gateway.replyEphemeral(i, "Linking isn't available here.")
            return
        }
        val user = i["member"].asJsonObject["user"].asJsonObject
        val discordId = user["id"].asString
        val discordName = user["global_name"]?.takeUnless { it.isJsonNull }?.asString ?: user["username"].asString
        val code = data["options"].asJsonArray.first { it.asJsonObject["name"].asString == "code" }
            .asJsonObject["value"].asString.trim().uppercase()

        // Everything touching game state happens on the server thread; reply from there.
        Rane.server.execute {
            val now = System.currentTimeMillis()
            val pending = pendingByCode[code]?.takeIf { it.expires > now }
            when {
                pending == null -> gateway.replyEphemeral(i, "That code isn't valid (or has expired). Rejoin the server to get a new one.")
                config.maxAccountsPerDiscordUser > 0 &&
                    store.data.links.count { it.value.discordId == discordId } >= config.maxAccountsPerDiscordUser ->
                    gateway.replyEphemeral(i, "Your Discord account is already linked to the maximum number of Minecraft accounts.")
                else -> {
                    link(pending.uuid, pending.name, discordId, discordName)
                    gateway.replyEphemeral(i, "Linked! You can now play on the server as **${pending.name}**.")
                }
            }
        }
    }
}
