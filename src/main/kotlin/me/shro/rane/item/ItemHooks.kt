package me.shro.rane.item

import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.SlotAccess
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ClickAction
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult

/**
 * Behaviour for custom items, keyed by custom item id. Handlers run server-side only and return
 * [InteractionResult.PASS] to let vanilla (and other handlers) continue.
 */
object ItemHooks {
    fun interface Use { fun use(player: ServerPlayer, hand: InteractionHand, stack: ItemStack): InteractionResult }
    fun interface UseOnBlock { fun use(player: ServerPlayer, hand: InteractionHand, stack: ItemStack, hit: BlockHitResult): InteractionResult }
    fun interface UseOnEntity { fun use(player: ServerPlayer, hand: InteractionHand, stack: ItemStack, entity: Entity): InteractionResult }

    /** Inventory click with [carried] on a slot holding [me]; return true if handled (vanilla skipped). */
    fun interface StackedOnMe {
        fun handle(me: ItemStack, carried: ItemStack, slot: Slot, action: ClickAction, player: Player, carriedAccess: SlotAccess): Boolean
    }

    private val use = mutableMapOf<String, Use>()
    private val useOnBlock = mutableMapOf<String, UseOnBlock>()
    private val useOnEntity = mutableMapOf<String, UseOnEntity>()
    private val stackedOnMe = mutableMapOf<String, StackedOnMe>()
    /** Block interactions independent of the held item (harvesting, doors, buttons…), in order. */
    private val anyUseOnBlock = mutableListOf<UseOnBlock>()

    fun onUse(id: String, h: Use) { use[id] = h }
    fun onUseOnBlock(id: String, h: UseOnBlock) { useOnBlock[id] = h }
    fun onUseOnEntity(id: String, h: UseOnEntity) { useOnEntity[id] = h }
    fun onStackedOnMe(id: String, h: StackedOnMe) { stackedOnMe[id] = h }
    fun onAnyUseOnBlock(h: UseOnBlock) { anyUseOnBlock += h }

    private var registered = false

    fun init() {
        if (registered) return
        registered = true
        UseItemCallback.EVENT.register { p, _, hand ->
            val player = p as? ServerPlayer ?: return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            val item = CustomItems.of(stack) ?: return@register InteractionResult.PASS
            if (hand == InteractionHand.OFF_HAND && Inhibit.OFFHAND in item.inhibit) return@register InteractionResult.FAIL
            use[item.id]?.use(player, hand, stack)
                ?: if (Inhibit.USE in item.inhibit) InteractionResult.FAIL else InteractionResult.PASS
        }
        UseBlockCallback.EVENT.register { p, _, hand, hit ->
            val player = p as? ServerPlayer ?: return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            val item = CustomItems.of(stack)
            if (item != null) {
                if (hand == InteractionHand.OFF_HAND && Inhibit.OFFHAND in item.inhibit) return@register InteractionResult.FAIL
                val r = useOnBlock[item.id]?.use(player, hand, stack, hit) ?: InteractionResult.PASS
                if (r != InteractionResult.PASS) return@register r
            }
            for (h in anyUseOnBlock) {
                val r = h.use(player, hand, stack, hit)
                if (r != InteractionResult.PASS) return@register r
            }
            if (item != null && Inhibit.PLACE in item.inhibit && !player.isSecondaryUseActive) {
                // Let the block be used (chests, doors…) but never place a custom block item.
                return@register InteractionResult.PASS
            }
            InteractionResult.PASS
        }
        UseEntityCallback.EVENT.register { p, _, hand, entity, _ ->
            val player = p as? ServerPlayer ?: return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            val item = CustomItems.of(stack) ?: return@register InteractionResult.PASS
            useOnEntity[item.id]?.use(player, hand, stack, entity) ?: InteractionResult.PASS
        }
    }

    /** From `ItemMixin`. */
    @JvmStatic
    fun stackedOnMe(me: ItemStack, carried: ItemStack, slot: Slot, action: ClickAction, player: Player, access: SlotAccess): Boolean {
        if (player !is ServerPlayer) return false
        val id = CustomItems.idOf(me) ?: return false
        return stackedOnMe[id]?.handle(me, carried, slot, action, player, access) ?: false
    }
}
