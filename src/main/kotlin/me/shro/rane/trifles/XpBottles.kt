package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import me.shro.rane.item.CustomItems
import me.shro.rane.item.ItemHooks
import me.shro.rane.core.giveItem
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import java.util.UUID
import kotlin.math.roundToInt

/** Bottle experience into small/medium/large bottles and drink it back later. */
object XpBottles {
    private val lastDrink = mutableMapOf<UUID, Long>()
    private val sizes get() = listOf(
        "small_xp_bottle" to Trifles.config.xpBottles.smallLevels,
        "medium_xp_bottle" to Trifles.config.xpBottles.mediumLevels,
        "large_xp_bottle" to Trifles.config.xpBottles.largeLevels,
    )

    fun levelToExp(level: Int): Int = when {
        level > 30 -> (4.5 * level * level - 162.5 * level + 2220).toInt()
        level > 15 -> (2.5 * level * level - 40.5 * level + 360).toInt()
        else -> level * level + 6 * level
    }

    fun totalExp(p: ServerPlayer) = levelToExp(p.experienceLevel) + (p.xpNeededForNextLevel * p.experienceProgress).roundToInt()

    fun init() {
        ItemHooks.onUse("empty_xp_bottle") { player, hand, stack ->
            if (System.currentTimeMillis() - (lastDrink[player.uuid] ?: 0) < 1000) return@onUse InteractionResult.FAIL
            val loss = Trifles.config.xpBottles.lossPercentage.coerceIn(0.0, 0.999)
            val have = totalExp(player)
            val (id, cost) = sizes
                .map { (id, lv) -> id to ((1.0 / (1.0 - loss)) * levelToExp(lv)).toInt() }
                .filter { (_, c) -> c in 1..have }
                .maxByOrNull { it.second } ?: return@onUse InteractionResult.FAIL
            player.giveExperiencePoints(-cost)
            stack.shrink(1)
            giveItem(player, CustomItems.create(id))
            player.level().playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 1f, 4f)
            player.swingHand(hand)
            InteractionResult.SUCCESS
        }
    }

    /** From `ItemStackMixin` when a drink finishes. */
    @JvmStatic
    fun onFinishUsing(stack: ItemStack, entity: LivingEntity) {
        if (entity !is ServerPlayer) return
        val levels = sizes.firstOrNull { it.first == CustomItems.idOf(stack) }?.second ?: return
        lastDrink[entity.uuid] = System.currentTimeMillis()
        entity.giveExperiencePoints(levelToExp(levels))
        entity.level().playSound(null, entity.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1f, 1f)
    }
}
