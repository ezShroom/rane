package me.shro.rane.bedtime

import kotlinx.serialization.Serializable
import me.shro.rane.core.Config
import me.shro.rane.core.RaneModule
import me.shro.rane.core.Scheduler
import me.shro.rane.core.txt
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.ceil

@Serializable
data class BedtimeConfig(
    /** Fraction of (non-spectator) players in a world that must sleep to skip the night. */
    val sleepThreshold: Double = 0.5,
    /** Time of day to advance to; 1000 is just after sunrise. */
    val targetTime: Long = 1000,
    /** Ticks over which the sky smoothly turns to morning. */
    val interpolationTicks: Long = 100,
)

/** Skip the night once enough players sleep, with a smooth sunrise and sleep/wake announcements. */
object Bedtime : RaneModule("bedtime", "Skip the night when a share of players sleep, with a smooth sunrise.") {
    lateinit var config: BedtimeConfig
        private set

    private val sleepers = mutableMapOf<ServerLevel, MutableSet<UUID>>()
    private val skipping = mutableSetOf<ServerLevel>()

    override fun init() {
        config = Config.load("bedtime", BedtimeConfig())
        EntitySleepEvents.START_SLEEPING.register { entity, _ ->
            val player = entity as? ServerPlayer ?: return@register
            Scheduler.nextTick {
                if (!player.isSleeping) return@nextTick
                val level = player.level() as ServerLevel
                sleepers.getOrPut(level) { mutableSetOf() } += player.uuid
                announce(level, player, "is sleeping")
                if (enoughSleeping(level)) Scheduler.later(98) { check(level) }
            }
        }
        EntitySleepEvents.STOP_SLEEPING.register { entity, _ ->
            val player = entity as? ServerPlayer ?: return@register
            val level = player.level() as ServerLevel
            if (sleepers[level]?.remove(player.uuid) == true && level !in skipping) announce(level, player, "feels rested")
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val level = handler.player.level() as ServerLevel
            sleepers[level]?.remove(handler.player.uuid)
            if (enoughSleeping(level)) Scheduler.later(98) { check(level) }
        }
    }

    private fun potential(level: ServerLevel) = level.players().count { !it.isSpectator }

    private fun count(level: ServerLevel) = sleepers[level]?.count { id -> level.getPlayerByUUID(id)?.isSleeping == true } ?: 0

    private fun enoughSleeping(level: ServerLevel): Boolean {
        val p = potential(level)
        val c = count(level)
        return c > 0 && p > 0 && c.toDouble() / p >= config.sleepThreshold
    }

    private fun announce(level: ServerLevel, player: ServerPlayer, what: String) {
        val required = ceil(potential(level) * config.sleepThreshold).toInt()
        val msg = txt("§6${player.gameProfile.name()}§r $what ${count(level)}/$required")
        level.players().forEach { it.sendOverlayMessage(msg) }
    }

    private fun check(level: ServerLevel) {
        if (level in skipping || !enoughSleeping(level)) return
        val clock = level.dimensionType().defaultClock().orElse(null) ?: return
        val clocks = level.server.clockManager()
        val dayTime = Math.floorMod(level.defaultClockTime, 24000L)
        val delta = Math.floorMod(config.targetTime - dayTime, 24000L)
        skipping += level
        val steps = maxOf(1L, config.interpolationTicks)
        var done = 0L
        Scheduler.repeat(1, 1) {
            val next = delta * (done + 1) / steps - delta * done / steps
            if (next > 0) clocks.addTicks(clock, next.toInt())
            done++
            if (done >= steps) {
                skipping -= level
                false
            } else true
        }
        level.resetWeatherCycle()
        sleepers[level]?.clear()
        level.players().filter { it.isSleeping }.forEach { it.stopSleepInBed(false, true) }
    }
}
