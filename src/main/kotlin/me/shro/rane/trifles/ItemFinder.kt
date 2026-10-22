package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import com.mojang.brigadier.arguments.IntegerArgumentType
import me.shro.rane.core.Perms
import me.shro.rane.core.Scheduler
import me.shro.rane.core.txt
import me.shro.rane.mixin.EntityAccessor
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.Commands.argument
import net.minecraft.commands.Commands.literal
import net.minecraft.commands.arguments.item.ItemArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** `/finditem <item>` highlights nearby containers and entities holding that item (only for you). */
object ItemFinder {
    private val cfg get() = Trifles.config.itemFinder

    fun init() {
        if (!cfg.enabled) return
        CommandRegistrationCallback.EVENT.register { d, registryAccess, _ ->
            d.register(literal("finditem").requires(Perms.everyone("rane.trifles.finditem"))
                .then(argument("item", ItemArgument.item(registryAccess)).executes { ctx ->
                    val item = ItemArgument.getItem(ctx, "item").item().value()
                    val found = find(ctx.source.playerOrException, item)
                    ctx.source.sendSystemMessage(txt(if (found > 0) "§aFound §b$found§a place(s) with that item nearby." else "§7No nearby containers have that item."))
                    found
                }))
        }
    }

    private fun matches(stack: ItemStack, item: Item): Boolean {
        if (stack.isEmpty) return false
        if (stack.`is`(item)) return true
        // Look one level into shulker boxes, pouches, backpacks and bundles.
        stack.get(DataComponents.CONTAINER)?.let { c -> if (c.nonEmptyItemCopyStream().anyMatch { it.`is`(item) }) return true }
        stack.get(DataComponents.BUNDLE_CONTENTS)?.let { b -> if (b.items().any { it.`is`(item) }) return true }
        return false
    }

    fun find(player: ServerPlayer, item: Item): Int {
        val level = player.level() as ServerLevel
        val cp = player.chunkPosition()
        val r = cfg.chunkRadius
        val blocks = mutableListOf<BlockPos>()
        for (cx in cp.x - r..cp.x + r) for (cz in cp.z - r..cp.z + r) {
            val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
            for ((pos, be) in chunk.blockEntities) {
                val c = be as? Container ?: continue
                if ((0 until c.containerSize).any { matches(c.getItem(it), item) }) blocks += pos
            }
        }
        val entities = mutableListOf<Entity>()
        if (cfg.searchEntities) {
            val box = AABB(((cp.x - r) * 16).toDouble(), level.minY.toDouble(), ((cp.z - r) * 16).toDouble(),
                ((cp.x + r + 1) * 16).toDouble(), level.maxY.toDouble(), ((cp.z + r + 1) * 16).toDouble())
            for (e in level.getEntities(player, box)) {
                val has = when (e) {
                    is ItemEntity -> matches(e.item, item)
                    is ItemFrame -> matches(e.item, item)
                    is Container -> (0 until e.containerSize).any { matches(e.getItem(it), item) }
                    is ServerPlayer -> (0 until e.inventory.containerSize).any { matches(e.inventory.getItem(it), item) }
                    is LivingEntity -> net.minecraft.world.entity.EquipmentSlot.entries.any { matches(e.getItemBySlot(it), item) }
                    else -> false
                }
                if (has) entities += e
            }
        }
        blocks.forEach { indicateBlock(player, level, it) }
        entities.forEach { indicateEntity(player, it) }
        val total = blocks.size + entities.size
        player.notifySound(if (total > 0) SoundEvents.AMETHYST_BLOCK_HIT else SoundEvents.UI_BUTTON_CLICK.value(), 1f, 1.3f)
        return total
    }

    /** A glowing invisible shulker that only [player] can see, for a few seconds. */
    private fun indicateBlock(player: ServerPlayer, level: ServerLevel, pos: BlockPos) {
        val shulker = EntityTypes.SHULKER.create(level, EntitySpawnReason.COMMAND) ?: return
        shulker.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        shulker.isInvisible = true
        shulker.setGlowingTag(true)
        player.connection.send(ClientboundAddEntityPacket(shulker.id, shulker.uuid, shulker.x, shulker.y, shulker.z, 0f, 0f, EntityTypes.SHULKER, 0, Vec3.ZERO, 0.0))
        shulker.entityData.nonDefaultValues?.let { player.connection.send(ClientboundSetEntityDataPacket(shulker.id, it)) }
        Scheduler.later(100) { player.connection.send(ClientboundRemoveEntitiesPacket(shulker.id)) }
    }

    private fun indicateEntity(player: ServerPlayer, e: Entity) {
        val key = EntityAccessor.rane_sharedFlags()
        val flags = e.entityData.get(key)
        player.connection.send(ClientboundSetEntityDataPacket(e.id, listOf(SynchedEntityData.DataValue.create(key, (flags.toInt() or 0x40).toByte()))))
        Scheduler.later(100) {
            if (!e.isRemoved) player.connection.send(ClientboundSetEntityDataPacket(e.id, listOf(SynchedEntityData.DataValue.create(key, e.entityData.get(key)))))
        }
    }
}
