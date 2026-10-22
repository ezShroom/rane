package me.shro.rane.admin

import kotlinx.serialization.Serializable
import me.shro.rane.core.Config
import me.shro.rane.core.Fmt
import me.shro.rane.core.Options
import me.shro.rane.core.Perms
import me.shro.rane.core.RaneModule
import me.shro.rane.core.txt
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.server.level.ServerLevel

@Serializable
data class AdminConfig(
    val hazards: Hazards = Hazards(),
    val worldRebuild: WorldRebuildConfig = WorldRebuildConfig(),
    /** Vane-style chat: "name: message" with the name in aqua. */
    val chatFormat: Boolean = true,
    val commands: Commands = Commands(),
) {
    @Serializable
    data class Hazards(
        val disableCreeperExplosions: Boolean = true,
        val disableWitherExplosions: Boolean = true,
        val disableEndermanBlockPickup: Boolean = true,
        val disableDoorBreaking: Boolean = true,
        val disableLightningFire: Boolean = true,
        /** Only allow building withers in these dimensions (if enabled). */
        val restrictWitherSpawning: Boolean = true,
        val witherDimensions: List<String> = listOf("minecraft:the_nether", "minecraft:the_end"),
    )

    @Serializable
    data class WorldRebuildConfig(
        /** Rebuild blocks destroyed by the explosions disabled above, instead of not destroying them. */
        val enabled: Boolean = true,
        val delayMs: Long = 2000,
        /** After n blocks the delay to the next is delay * exp(-n * falloff). */
        val delayFalloff: Double = 0.175,
        val minDelayMs: Long = 50,
    )

    @Serializable
    data class Commands(
        /** `/gm [player]` toggles between survival and creative. */
        val gm: Boolean = true,
        val slimechunk: Boolean = true,
    )
}

object Admin : RaneModule("admin", "Hazard protection, world rebuild after explosions, chat format and handy commands.") {
    lateinit var config: AdminConfig
        private set

    override fun init() {
        config = Config.load("admin", AdminConfig())
        Options.set("admin.chat_format", config.chatFormat)
        WorldRebuild.init()
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            if (config.commands.gm) {
                dispatcher.register(
                    literal("gm").requires(Perms.require("rane.admin.gm"))
                        .executes { ctx -> toggleGamemode(ctx.source.playerOrException, ctx.source) }
                        .then(argument("player", EntityArgument.player())
                            .executes { ctx -> toggleGamemode(EntityArgument.getPlayer(ctx, "player"), ctx.source) })
                )
            }
            if (config.commands.slimechunk) {
                dispatcher.register(
                    literal("slimechunk").requires(Perms.everyone("rane.admin.slimechunk"))
                        .executes { ctx ->
                            val p = ctx.source.playerOrException
                            val level = p.level() as ServerLevel
                            val cp = p.chunkPosition()
                            val slime = WorldgenRandom.seedSlimeChunk(cp.x, cp.z, level.seed, 987234911L).nextInt(10) == 0
                            ctx.source.sendSystemMessage(txt(if (slime) "§aThis is a slime chunk." else "§cThis is not a slime chunk."))
                            1
                        }
                )
            }
        }
    }

    private fun toggleGamemode(player: ServerPlayer, source: net.minecraft.commands.CommandSourceStack): Int {
        val next = if (player.gameMode() == GameType.CREATIVE) GameType.SURVIVAL else GameType.CREATIVE
        player.setGameMode(next)
        source.sendSuccess({ txt("${Fmt.INFO}Gamemode of §b${player.gameProfile.name()}${Fmt.INFO} was set to §a${next.getName()}") }, true)
        return 1
    }
}
