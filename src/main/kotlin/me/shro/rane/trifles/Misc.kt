package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import com.mojang.brigadier.Command
import me.shro.rane.Rane
import me.shro.rane.core.Perms
import me.shro.rane.core.Scheduler
import me.shro.rane.core.giveItem
import me.shro.rane.core.msToTicks
import me.shro.rane.core.txt
import me.shro.rane.item.CustomItems
import me.shro.rane.item.ItemHooks
import me.shro.rane.regions.Protection
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.Commands.literal
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.monster.cubemob.Slime
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoorHingeSide
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.storage.LevelData

/** Small vane-trifles features: double doors, chest sorting, path speed, item frames, … */
object Misc {
    private val cfg get() = Trifles.config
    private val sortCooldown = mutableMapOf<BlockPos, Long>()
    private var syncingDoor = false

    fun init() {
        // Faster walking on paths.
        if (cfg.fastWalking.enabled) {
            val blocks = cfg.fastWalking.blocks.mapNotNull { BuiltInRegistries.BLOCK.getOptional(Identifier.parse(it)).orElse(null) }.toSet()
            Scheduler.repeat(10) {
                if (Rane.isEnabled("trifles")) for (p in Rane.server.playerList.players) {
                    if (p.isSpectator || p.isPassenger) continue
                    val below = p.level().getBlockState(p.blockPosition().below()).block
                    val at = p.level().getBlockState(p.blockPosition()).block
                    if (below in blocks || at in blocks) {
                        p.addEffect(MobEffectInstance(MobEffects.SPEED, msToTicks(cfg.fastWalking.durationMs).toInt(), cfg.fastWalking.speedLevel - 1, true, false, true))
                    }
                }
                true
            }
        }

        // Shears toggle item frame visibility.
        if (cfg.invisibleItemFrames) {
            UseEntityCallback.EVENT.register { player, _, hand, entity, _ ->
                if (player !is ServerPlayer || entity !is ItemFrame || !player.getItemInHand(hand).`is`(Items.SHEARS)) return@register InteractionResult.PASS
                if (Rane.isEnabled("regions") && !Protection.mayBuild(player, player.level() as ServerLevel, entity.blockPosition())) return@register InteractionResult.FAIL
                entity.isInvisible = !entity.isInvisible
                entity.level().playSound(null, entity.blockPosition(), SoundEvents.SHEEP_SHEAR, SoundSource.PLAYERS, 0.8f, 1.4f)
                InteractionResult.SUCCESS
            }
        }

        // All recipes unlocked.
        if (cfg.unlockAllRecipes) {
            ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
                server.execute { handler.player.awardRecipes(server.recipeManager.recipes) }
            }
        }

        // Slime in a bucket.
        UseEntityCallback.EVENT.register { player, _, hand, entity, _ ->
            if (player !is ServerPlayer || entity !is Slime || entity.type != EntityTypes.SLIME || entity.size != 1) return@register InteractionResult.PASS
            val held = player.getItemInHand(hand)
            if (!held.`is`(Items.BUCKET) || CustomItems.isCustom(held)) return@register InteractionResult.PASS
            entity.discard()
            held.shrink(1)
            giveItem(player, CustomItems.create("slime_bucket"))
            player.level().playSound(null, player.blockPosition(), SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 1f, 2f)
            InteractionResult.SUCCESS
        }
        ItemHooks.onUseOnBlock("slime_bucket") { player, hand, stack, hit ->
            val level = player.level() as ServerLevel
            val pos = hit.blockPos.relative(hit.direction)
            val slime = EntityTypes.SLIME.create(level, EntitySpawnReason.BUCKET) ?: return@onUseOnBlock InteractionResult.FAIL
            slime.setSize(1, true)
            slime.snapTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5, player.yRot, 0f)
            if (stack.has(DataComponents.CUSTOM_NAME)) slime.customName = stack.get(DataComponents.CUSTOM_NAME)
            level.addFreshEntity(slime)
            stack.shrink(1)
            giveItem(player, ItemStack(Items.BUCKET))
            level.playSound(null, pos, SoundEvents.SLIME_JUMP, SoundSource.PLAYERS, 1f, 2f)
            player.swingHand(hand)
            InteractionResult.SUCCESS
        }
        Scheduler.repeat(20) {
            if (Rane.isEnabled("trifles")) for (p in Rane.server.playerList.players) {
                for (hand in InteractionHand.entries) {
                    val s = p.getItemInHand(hand)
                    if (CustomItems.idOf(s) != "slime_bucket") continue
                    val model = if (isSlimeChunk(p.level() as ServerLevel, p.chunkPosition())) "slime_bucket_excited" else "slime_bucket"
                    val id = Identifier.fromNamespaceAndPath("rane", model)
                    if (s.get(DataComponents.ITEM_MODEL) != id) s.set(DataComponents.ITEM_MODEL, id)
                }
            }
            true
        }

        CommandRegistrationCallback.EVENT.register { d, _, _ ->
            if (cfg.setspawnCommand) {
                d.register(literal("setspawn").requires(Perms.require("rane.trifles.setspawn")).executes { ctx ->
                    val p = ctx.source.playerOrException
                    val level = p.level() as ServerLevel
                    ctx.source.server.setRespawnData(LevelData.RespawnData.of(level.dimension(), p.blockPosition(), p.yRot, p.xRot))
                    ctx.source.sendSuccess({ txt("§aSpawn set to your position.") }, true)
                    Command.SINGLE_SUCCESS
                })
            }
        }
    }

    fun isSlimeChunk(level: ServerLevel, cp: ChunkPos) =
        level.dimension() == Level.OVERWORLD && WorldgenRandom.seedSlimeChunk(cp.x, cp.z, level.seed, 987234911L).nextInt(10) == 0

    // ------------------------------------------------------------------ double doors (DoorBlockMixin)

    @JvmStatic
    fun onDoorChanged(level: Level, pos: BlockPos, player: Player?) {
        if (!cfg.doubleDoors || syncingDoor || level.isClientSide || !Rane.isEnabled("trifles")) return
        if (player != null && player.isShiftKeyDown) return
        val state = level.getBlockState(pos)
        val door = state.block as? DoorBlock ?: return
        val facing = state.getValue(DoorBlock.FACING)
        val hinge = state.getValue(DoorBlock.HINGE)
        // The partner sits on the side away from our hinge.
        val side = if (hinge == DoorHingeSide.LEFT) facing.clockWise else facing.counterClockWise
        val otherPos = pos.relative(side)
        val other = level.getBlockState(otherPos)
        if (other.block !is DoorBlock || other.getValue(DoorBlock.FACING) != facing || other.getValue(DoorBlock.HINGE) == hinge) return
        if (other.getValue(DoorBlock.HALF) != state.getValue(DoorBlock.HALF)) return
        val open = state.getValue(DoorBlock.OPEN)
        if (other.getValue(DoorBlock.OPEN) == open) return
        syncingDoor = true
        try {
            (other.block as DoorBlock).setOpen(player, level, other, otherPos, open)
        } finally {
            syncingDoor = false
        }
    }

    // ------------------------------------------------------------------ chest sorting (ButtonBlockMixin)

    @JvmStatic
    fun onButtonPressed(level: Level, pos: BlockPos, player: Player?) {
        if (!cfg.chestSorting.enabled || level !is ServerLevel || !Rane.isEnabled("trifles")) return
        val now = System.currentTimeMillis()
        if (now - (sortCooldown[pos] ?: 0) < cfg.chestSorting.cooldownMs) return
        sortCooldown[pos.immutable()] = now
        sortCooldown.entries.removeIf { now - it.value > 60_000 }
        val r = cfg.chestSorting.radius
        val seen = mutableSetOf<Container>()
        for (p in BlockPos.betweenClosed(pos.offset(-r, -r, -r), pos.offset(r, r, r))) {
            val bp = p.immutable()
            if (player is ServerPlayer && me.shro.rane.Rane.isEnabled("regions") && !Protection.mayUseContainers(player, level, bp)) continue
            val state = level.getBlockState(bp)
            val container: Container = if (state.block is ChestBlock) {
                ChestBlock.getContainer(state.block as ChestBlock, state, level, bp, true) ?: continue
            } else level.getBlockEntity(bp) as? Container ?: continue
            if (container is net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity || container is net.minecraft.world.level.block.entity.BarrelBlockEntity || state.block is ChestBlock) {
                if (seen.any { it === container || (it is net.minecraft.world.CompoundContainer && it.contains(container)) }) continue
                seen += container
                sort(container)
            }
        }
        if (seen.isNotEmpty()) level.playSound(null, pos, SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.6f, 1.6f)
    }

    fun sort(c: Container) {
        val items = (0 until c.containerSize).map { c.getItem(it) }.filter { !it.isEmpty }.map { it.copy() }
        val merged = mutableListOf<ItemStack>()
        for (s in items) {
            var left = s
            for (m in merged) {
                if (left.isEmpty) break
                if (ItemStack.isSameItemSameComponents(m, left) && m.count < m.maxStackSize) {
                    val move = minOf(left.count, m.maxStackSize - m.count)
                    m.grow(move)
                    left.shrink(move)
                }
            }
            if (!left.isEmpty) merged += left
        }
        merged.sortWith(compareBy<ItemStack>({ BuiltInRegistries.ITEM.getKey(it.item).toString() }, { it.hoverName.string }).thenByDescending { it.count })
        for (i in 0 until c.containerSize) c.setItem(i, merged.getOrElse(i) { ItemStack.EMPTY })
        c.setChanged()
    }

    // ------------------------------------------------------------------ anvil (AnvilMenuMixin)

    @JvmStatic
    fun repairCostLimit(): Int = if (Rane.isEnabled("trifles")) cfg.repairCostLimit else 0
}
