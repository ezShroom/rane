package me.shro.rane.enchantments

import me.shro.rane.Rane
import me.shro.rane.core.Scheduler
import me.shro.rane.core.msToTicks
import me.shro.rane.core.swingHand
import me.shro.rane.core.txt
import me.shro.rane.regions.Protection
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.DamageTypeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.StemBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.PI
import kotlin.math.exp

object EnchantmentEffects {
    private val cfg get() = Enchantments.config
    private val wasGliding = mutableSetOf<UUID>()
    private val wasSneaking = mutableSetOf<UUID>()
    private val wingsReady = mutableMapOf<UUID, Long>()
    private val keptOnDeath = mutableMapOf<UUID, List<Pair<Int, ItemStack>>>()
    private val dropConfirm = mutableMapOf<UUID, Long>()

    private fun on() = Rane.isEnabled("enchantments")

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (!on()) return@register
            for (p in server.playerList.players) elytraTick(p)
        }

        // Hell bent and unbreakable are pure data (see data/rane/enchantment). Lightning protection:
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { e, source, _ ->
            !(on() && cfg.lightning.protectWielder && e is ServerPlayer && source.`is`(DamageTypeTags.IS_LIGHTNING) &&
                Enchantments.level(e.mainHandItem, "lightning") > 0)
        }

        AttackEntityCallback.EVENT.register { player, level, hand, target, _ ->
            if (!on() || player !is ServerPlayer || level !is ServerLevel) return@register InteractionResult.PASS
            if (Enchantments.level(player.getItemInHand(hand), "lightning") > 0 && level.isRaining &&
                (cfg.lightning.worksInRain || level.isThundering) &&
                target.blockY >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, target.blockX, target.blockZ)) {
                val bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED)
                if (bolt != null) {
                    bolt.snapTo(target.position())
                    bolt.setCause(player)
                    level.addFreshEntity(bolt)
                    target.invulnerableTime = 0
                    target.hurtServer(level, level.damageSources().playerAttack(player), cfg.lightning.extraDamage.toFloat())
                }
            }
            InteractionResult.PASS
        }

        // Leafchopper: leaves pop instantly.
        AttackBlockCallback.EVENT.register { player, level, hand, pos, _ ->
            if (!on() || player !is ServerPlayer || level !is ServerLevel || hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val state = level.getBlockState(pos)
            if (state.block !is LeavesBlock || state.getValue(LeavesBlock.PERSISTENT)) return@register InteractionResult.PASS
            if (Enchantments.level(player.mainHandItem, "leafchopper") == 0) return@register InteractionResult.PASS
            if (Rane.isEnabled("regions") && !Protection.mayBuild(player, level, pos)) return@register InteractionResult.PASS
            level.destroyBlock(pos, true, player)
            level.playSound(null, pos, SoundEvents.GRASS_BREAK, SoundSource.BLOCKS, 1f, 1f)
            InteractionResult.SUCCESS
        }

        UseBlockCallback.EVENT.register { player, level, hand, hit ->
            if (!on() || player !is ServerPlayer || level !is ServerLevel) return@register InteractionResult.PASS
            val stack = player.getItemInHand(hand)
            if (!stack.`is`(ItemTags.HOES)) return@register InteractionResult.PASS
            val pos = hit.blockPos
            val state = level.getBlockState(pos)
            val seeding = Enchantments.level(stack, "seeding")
            val rake = Enchantments.level(stack, "rake")
            val done = when {
                seeding > 0 && seedFor(state.block) != null -> sow(player, level, pos, seeding)
                rake > 0 && state.`is`(Blocks.FARMLAND) -> till(player, level, pos, rake)
                else -> return@register InteractionResult.PASS
            }
            if (done) {
                stack.hurtAndBreak(1, player, if (hand == InteractionHand.MAIN_HAND) EquipmentSlot.MAINHAND else EquipmentSlot.OFFHAND)
                player.swingHand(hand)
            }
            InteractionResult.SUCCESS
        }

        // Soulbound items come back after respawning.
        ServerPlayerEvents.COPY_FROM.register { old, new, alive ->
            if (alive) return@register
            val kept = keptOnDeath.remove(old.uuid) ?: return@register
            for ((slot, stack) in kept) {
                if (new.inventory.getItem(slot).isEmpty) new.inventory.setItem(slot, stack) else new.inventory.placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY)
            }
        }
    }

    // ------------------------------------------------------------------ elytra: angel, wings, take off

    private fun elytraTick(p: ServerPlayer) {
        val gliding = p.isFallFlying
        val sneaking = p.isShiftKeyDown
        val startedGliding = gliding && wasGliding.add(p.uuid)
        if (!gliding) wasGliding.remove(p.uuid)
        val startedSneaking = sneaking && wasSneaking.add(p.uuid)
        if (!sneaking) wasSneaking.remove(p.uuid)
        if (!gliding) return
        val chest = p.getItemBySlot(EquipmentSlot.CHEST)

        if (startedGliding && !sneaking) {
            val lvl = Enchantments.level(chest, "take_off")
            if (lvl > 0) boost(p, chest, Enchantments.perLevel(cfg.takeOffStrength, lvl))
        }
        if (startedSneaking) {
            val lvl = Enchantments.level(chest, "wings")
            if (lvl > 0 && System.currentTimeMillis() >= (wingsReady[p.uuid] ?: 0)) {
                val cd = Enchantments.perLevel(cfg.wings.cooldownMs, lvl).toLong()
                wingsReady[p.uuid] = System.currentTimeMillis() + cd
                p.cooldowns.addCooldown(chest, msToTicks(cd).toInt())
                boost(p, chest, Enchantments.perLevel(cfg.wings.strength, lvl))
            }
        }
        if (sneaking) {
            val lvl = Enchantments.level(chest, "angel")
            if (lvl > 0) {
                val dir = p.lookAngle
                val vel = p.deltaMovement
                val angle = if (vel.lengthSqr() < 1e-8) 0.0 else Math.acos((dir.dot(vel) / (dir.length() * vel.length())).coerceIn(-1.0, 1.0))
                val delta = cfg.angel.acceleration * (1.0 - angle / PI)
                val speed = Enchantments.perLevel(cfg.angel.speed, lvl)
                val nv = vel.scale(1.0 - delta).add(dir.normalize().scale(delta * speed))
                p.deltaMovement = nv
                p.needsSync = true
                (p.level() as ServerLevel).sendParticles(ParticleTypes.FIREWORK, p.x, p.y, p.z, 0, -nv.x, -nv.y, -nv.z, 0.4)
            }
        }
    }

    private fun boost(p: ServerPlayer, chest: ItemStack, factor: Double) {
        p.deltaMovement = p.deltaMovement.add(p.lookAngle.normalize().scale(factor))
        p.needsSync = true
        val level = p.level() as ServerLevel
        level.playSound(null, p.blockPosition(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.4f, 2f)
        level.sendParticles(ParticleTypes.FIREWORK, p.x, p.y, p.z, 16, 0.4, 0.4, 0.4, 0.05)
        chest.hurtAndBreak(1 + (Math.random() * 2).toInt(), p, EquipmentSlot.CHEST)
    }

    // ------------------------------------------------------------------ grappling hook (FishingHookMixin)

    @JvmStatic
    fun onReel(hook: FishingHook, rod: ItemStack) {
        if (!on()) return
        val player = hook.playerOwner as? ServerPlayer ?: return
        val lvl = maxOf(Enchantments.level(player.mainHandItem, "grappling_hook"), Enchantments.level(player.offhandItem, "grappling_hook"))
        if (lvl == 0) return
        val box = hook.boundingBox.inflate(0.2)
        if (!hook.level().getBlockCollisions(null, box).iterator().hasNext()) return
        val dir = hook.position().subtract(player.position())
        val dist = dir.length()
        if (dist < 0.01) return
        val att = dist / cfg.grapplingHook.idealDistance
        val mul = Enchantments.perLevel(cfg.grapplingHook.strength, lvl) * exp(1.0 - att) * att
        var v = dir.normalize().scale(mul).add(0.0, 0.2, 0.0)
        if (player.y - hook.y > 0) v = Vec3(v.x, 0.2, v.z)
        player.fallDistance = 0.0
        player.deltaMovement = player.deltaMovement.add(v)
        player.needsSync = true
    }

    // ------------------------------------------------------------------ seeding & rake

    private fun seedFor(block: Block) = when (block) {
        Blocks.WHEAT -> Items.WHEAT_SEEDS
        Blocks.CARROTS -> Items.CARROT
        Blocks.POTATOES -> Items.POTATO
        Blocks.BEETROOTS -> Items.BEETROOT_SEEDS
        Blocks.NETHER_WART -> Items.NETHER_WART
        Blocks.PUMPKIN_STEM, Blocks.ATTACHED_PUMPKIN_STEM -> Items.PUMPKIN_SEEDS
        Blocks.MELON_STEM, Blocks.ATTACHED_MELON_STEM -> Items.MELON_SEEDS
        Blocks.TORCHFLOWER_CROP -> Items.TORCHFLOWER_SEEDS
        else -> null
    }

    private fun plantFor(block: Block) = when (block) {
        Blocks.ATTACHED_PUMPKIN_STEM -> Blocks.PUMPKIN_STEM
        Blocks.ATTACHED_MELON_STEM -> Blocks.MELON_STEM
        else -> block
    }

    /** Positions in a growing circle around [center] (same height), nearest first. */
    private fun circle(center: BlockPos, radius: Int) = (-radius..radius).flatMap { dx -> (-radius..radius).map { dz -> center.offset(dx, 0, dz) } }
        .filter { it.distSqr(center) <= (radius + 0.5) * (radius + 0.5) }
        .sortedBy { it.distSqr(center) }

    private fun sow(player: ServerPlayer, level: ServerLevel, pos: BlockPos, lvl: Int): Boolean {
        val crop = level.getBlockState(pos).block
        val seed = seedFor(crop) ?: return false
        val plant = plantFor(crop)
        val soil = if (plant == Blocks.NETHER_WART) Blocks.SOUL_SAND else Blocks.FARMLAND
        val target = circle(pos, lvl).firstOrNull { p ->
            level.getBlockState(p.below()).`is`(soil) && level.getBlockState(p).isAir &&
                (!Rane.isEnabled("regions") || Protection.mayBuild(player, level, p))
        } ?: return false
        if (!player.hasInfiniteMaterials()) {
            val inv = player.inventory
            val slot = (0 until inv.containerSize).firstOrNull { inv.getItem(it).`is`(seed) && !me.shro.rane.item.CustomItems.isCustom(inv.getItem(it)) }
            if (slot == null) {
                player.sendOverlayMessage(txt("§7You have no ${ItemStack(seed).hoverName.string} to sow."))
                return false
            }
            inv.getItem(slot).shrink(1)
        }
        level.setBlock(target, plant.defaultBlockState(), Block.UPDATE_ALL)
        level.playSound(null, target, SoundEvents.CROP_PLANTED, SoundSource.BLOCKS, 1f, 1f)
        return true
    }

    private fun till(player: ServerPlayer, level: ServerLevel, pos: BlockPos, lvl: Int): Boolean {
        val target = circle(pos, lvl).firstOrNull { p ->
            val s = level.getBlockState(p)
            (s.`is`(Blocks.DIRT) || s.`is`(Blocks.GRASS_BLOCK) || s.`is`(Blocks.DIRT_PATH)) && level.getBlockState(p.above()).isAir &&
                (!Rane.isEnabled("regions") || Protection.mayBuild(player, level, p))
        } ?: return false
        level.setBlock(target, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL)
        level.playSound(null, target, SoundEvents.HOE_TILL.value(), SoundSource.BLOCKS, 1f, 1f)
        return true
    }

    // ------------------------------------------------------------------ soulbound (InventoryMixin, ServerPlayerMixin)

    /** Called before a dying player's inventory is dropped; removes and remembers soulbound items. */
    @JvmStatic
    fun keepSoulbound(inv: Inventory) {
        if (!on()) return
        val player = inv.player as? ServerPlayer ?: return
        val kept = mutableListOf<Pair<Int, ItemStack>>()
        for (i in 0 until inv.containerSize) {
            val s = inv.getItem(i)
            if (!s.isEmpty && Enchantments.level(s, "soulbound") > 0) {
                kept += i to s.copy()
                inv.setItem(i, ItemStack.EMPTY)
            }
        }
        if (kept.isNotEmpty()) keptOnDeath[player.uuid] = kept
    }

    /** Q on a soulbound item needs a second press within the window. Returns true to block the drop. */
    @JvmStatic
    fun blockSoulboundDrop(player: ServerPlayer): Boolean {
        if (!on()) return false
        val held = player.mainHandItem
        if (held.isEmpty || Enchantments.level(held, "soulbound") == 0) return false
        val now = System.currentTimeMillis()
        val last = dropConfirm[player.uuid] ?: 0
        if (now - last <= cfg.soulboundDropWindowMs) {
            dropConfirm.remove(player.uuid)
            player.sendSystemMessage(txt("§7You dropped your soulbound ").append(held.hoverName).append(txt("§7.")))
            return false
        }
        dropConfirm[player.uuid] = now
        player.sendOverlayMessage(txt("§7").append(held.hoverName).append(txt("§7 is soulbound. Drop again to let go of it.")))
        // Make the client put the item back in its slot.
        player.inventoryMenu.sendAllDataToRemote()
        return true
    }
}
