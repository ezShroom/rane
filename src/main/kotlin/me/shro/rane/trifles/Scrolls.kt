package me.shro.rane.trifles

import me.shro.rane.core.swingHand
import me.shro.rane.core.notifySound
import me.shro.rane.core.displayName
import me.shro.rane.core.Fmt
import me.shro.rane.core.msToTicks
import me.shro.rane.core.plain
import me.shro.rane.core.txt
import me.shro.rane.item.CustomItem
import me.shro.rane.item.CustomItems
import me.shro.rane.item.ItemHooks
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.phys.Vec3

/** Portal scrolls: home, unstable, spawn, lodestone and death. */
object Scrolls {
    private val cfg get() = Trifles.config.scrolls

    private data class Target(val level: ServerLevel, val pos: Vec3, val yaw: Float, val pitch: Float)

    private val scrollIds = listOf("home_scroll", "unstable_scroll", "spawn_scroll", "lodestone_scroll", "death_scroll")

    fun init() {
        for (id in scrollIds) {
            ItemHooks.onUse(id) { player, hand, stack -> use(player, hand, stack, id) }
        }
        // Lodestone binding happens before the generic use handler gets a chance.
        ItemHooks.onUseOnBlock("lodestone_scroll") { player, hand, stack, hit ->
            val level = player.level() as ServerLevel
            if (player.isShiftKeyDown && level.getBlockState(hit.blockPos).`is`(Blocks.LODESTONE)) {
                bindLodestone(stack, level, hit.blockPos)
                player.actionBar("${Fmt.OK}Scroll bound to this lodestone.")
                level.playSound(null, hit.blockPos, SoundEvents.LODESTONE_COMPASS_LOCK, SoundSource.PLAYERS, 1f, 1f)
                InteractionResult.SUCCESS
            } else use(player, hand, stack, "lodestone_scroll")
        }
        for (id in scrollIds.filter { it != "lodestone_scroll" }) {
            ItemHooks.onUseOnBlock(id) { player, hand, stack, _ ->
                // Don't hijack right-clicking chests, doors etc.
                if (!player.isShiftKeyDown) InteractionResult.PASS else use(player, hand, stack, id)
            }
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, _, _, taken, _ ->
            if (entity is ServerPlayer && taken > 0 && cfg.damageCooldownMs > 0) cooldownAll(entity, cfg.damageCooldownMs)
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            if (entity is ServerPlayer) {
                Trifles.store.edit {
                    val d = Trifles.playerData(entity.uuid)
                    d.lastDeath = entity.storedPos()
                    d.lastDeathTime = System.currentTimeMillis()
                }
            }
        }
    }

    private fun cooldownMs(id: String) = when (id) {
        "home_scroll" -> cfg.homeCooldownMs
        "unstable_scroll" -> cfg.unstableCooldownMs
        "spawn_scroll" -> cfg.spawnCooldownMs
        "lodestone_scroll" -> cfg.lodestoneCooldownMs
        else -> cfg.deathCooldownMs
    }

    private fun cooldownAll(player: ServerPlayer, ms: Long) {
        val ticks = msToTicks(ms).toInt()
        val cds = player.cooldowns
        // Only ever extend an existing cooldown.
        val probe = CustomItems.create("spawn_scroll")
        val remaining = (cds.getCooldownPercent(probe, 0f) * ticks).toInt()
        if (!cds.isOnCooldown(probe) || remaining < ticks) cds.addCooldown(TriflesItems.SCROLL_GROUP, ticks)
    }

    private fun use(player: ServerPlayer, hand: InteractionHand, stack: ItemStack, id: String): InteractionResult {
        if (player.cooldowns.isOnCooldown(stack)) return InteractionResult.FAIL
        val target = target(player, stack, id) ?: return InteractionResult.FAIL
        val from = player.storedPos()
        teleport(player, target)
        Trifles.store.edit { Trifles.playerData(player.uuid).lastScrollFrom = from }
        cooldownAll(player, cooldownMs(id))
        stack.hurtAndBreak(1, player, if (hand == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND)
        player.swingHand(hand)
        return InteractionResult.SUCCESS
    }

    private fun target(player: ServerPlayer, stack: ItemStack, id: String): Target? {
        val server = player.level().server
        when (id) {
            "home_scroll" -> {
                val cfgR = player.respawnConfig?.respawnData()
                val level = cfgR?.let { server.getLevel(it.dimension()) }
                val pos = cfgR?.pos()
                val stand = if (level != null && pos != null) standPos(level, pos) else null
                if (stand == null) {
                    player.actionBar("§7You have no home bed or charged respawn anchor.")
                    return null
                }
                return Target(level!!, stand, player.yRot, player.xRot)
            }
            "unstable_scroll" -> {
                val p = Trifles.playerData(player.uuid).lastScrollFrom
                if (p == null) { player.actionBar("§7You haven't used a scroll yet."); return null }
                return p.toTarget(server) ?: run { player.actionBar("§7That place is out of reach."); null }
            }
            "spawn_scroll" -> {
                val r = server.respawnData
                val level = server.getLevel(r.dimension()) ?: server.overworld()
                val surface = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, r.pos())
                return Target(level, Vec3.atBottomCenterOf(surface), r.yaw(), r.pitch())
            }
            "lodestone_scroll" -> {
                val bound = boundLodestone(stack)
                if (bound == null) { player.actionBar("§7This scroll isn't bound to a lodestone yet."); return null }
                val level = server.getLevel(bound.first)
                if (level == null || !level.getBlockState(bound.second).`is`(Blocks.LODESTONE)) {
                    player.actionBar("§cThe bound lodestone is gone.")
                    return null
                }
                return Target(level, Vec3(bound.second.x + 0.5, bound.second.y + 1.005, bound.second.z + 0.5), player.yRot, player.xRot)
            }
            else -> {
                val data = Trifles.playerData(player.uuid)
                val fresh = System.currentTimeMillis() - data.lastDeathTime <= cfg.deathScrollWindowMinutes * 60_000
                val t = if (fresh) data.lastDeath?.toTarget(server) else null
                if (t == null) { player.actionBar("§7You haven't died recently."); return null }
                Trifles.store.edit { data.lastDeath = null; data.lastDeathTime = 0 }
                return t
            }
        }
    }

    private fun standPos(level: ServerLevel, pos: BlockPos): Vec3? {
        val state = level.getBlockState(pos)
        if (state.block is BedBlock) {
            return BedBlock.findStandUpPosition(net.minecraft.world.entity.EntityTypes.PLAYER, level, pos, state.getValue(BedBlock.FACING), 0f).orElse(null)
        }
        if (state.block is RespawnAnchorBlock && state.getValue(RespawnAnchorBlock.CHARGE) > 0) {
            return RespawnAnchorBlock.findStandUpPosition(net.minecraft.world.entity.EntityTypes.PLAYER, level, pos).orElse(null)
        }
        return null
    }

    private fun teleport(player: ServerPlayer, t: Target) {
        val fromLevel = player.level() as ServerLevel
        val from = player.position()
        fromLevel.playSound(null, from.x, from.y, from.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 1.1f)
        fromLevel.playSound(null, from.x, from.y, from.z, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.PLAYERS, 1f, 1f)
        fromLevel.sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1, from.z, 200, 1.0, 2.0, 1.0, 1.0)
        player.teleportTo(t.level, t.pos.x, t.pos.y, t.pos.z, emptySet(), t.yaw, t.pitch, true)
        t.level.playSound(null, t.pos.x, t.pos.y, t.pos.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1f, 1.1f)
        t.level.playSound(null, t.pos.x, t.pos.y, t.pos.z, SoundEvents.END_PORTAL_FRAME_FILL, SoundSource.PLAYERS, 1f, 1f)
        t.level.sendParticles(ParticleTypes.END_ROD, t.pos.x, t.pos.y + 1, t.pos.z, 100, 1.0, 2.0, 1.0, 1.0)
    }

    private fun bindLodestone(stack: ItemStack, level: ServerLevel, pos: BlockPos) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack) { tag ->
            val rane = tag.getCompoundOrEmpty(CustomItem.TAG)
            rane.put("lodestone", CompoundTag().apply {
                putString("dim", level.dimension().identifier().toString())
                putInt("x", pos.x); putInt("y", pos.y); putInt("z", pos.z)
            })
            tag.put(CustomItem.TAG, rane)
        }
        val lore = listOf("§7Bound to lodestone at", "§b${pos.x}, ${pos.y}, ${pos.z}§7 (${level.dimension().identifier().path})")
        stack.set(DataComponents.LORE, ItemLore(lore.map { plain(it) }))
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    }

    private fun boundLodestone(stack: ItemStack): Pair<ResourceKey<net.minecraft.world.level.Level>, BlockPos>? {
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
        val l = tag.getCompound(CustomItem.TAG).flatMap { it.getCompound("lodestone") }.orElse(null) ?: return null
        val dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(l.getStringOr("dim", "minecraft:overworld")))
        return dim to BlockPos(l.getIntOr("x", 0), l.getIntOr("y", 0), l.getIntOr("z", 0))
    }

    private fun ServerPlayer.storedPos() = StoredPos(level().dimension().identifier().toString(), x, y, z, yRot, xRot)

    private fun StoredPos.toTarget(server: net.minecraft.server.MinecraftServer): Target? {
        val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension))) ?: return null
        return Target(level, Vec3(x, y, z), yaw, pitch)
    }

    private fun ServerPlayer.actionBar(s: String) = sendOverlayMessage(txt(s))
}
