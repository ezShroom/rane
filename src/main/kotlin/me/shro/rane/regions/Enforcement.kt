package me.shro.rane.regions

import me.shro.rane.Rane
import me.shro.rane.core.txt
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.Container
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.BlockAttachedEntity
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.vehicle.VehicleEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.SpawnEggItem

/** Applies region roles to players and region-group environment settings to the world. */
object Enforcement {
    private fun on() = Rane.isEnabled("regions")

    private fun deny(p: ServerPlayer, what: String): InteractionResult {
        p.sendOverlayMessage(txt("§cYou can't $what in this region."))
        return InteractionResult.FAIL
    }

    /** Items whose right-click changes blocks (placing, pouring, stripping, igniting…). */
    private fun modifiesWorld(s: ItemStack) = !s.isEmpty && (s.item is BlockItem || s.item is BucketItem || s.item is SpawnEggItem ||
        s.`is`(ItemTags.AXES) || s.`is`(ItemTags.HOES) || s.`is`(ItemTags.SHOVELS) || s.`is`(Items.FLINT_AND_STEEL) ||
        s.`is`(Items.FIRE_CHARGE) || s.`is`(Items.BONE_MEAL) || s.`is`(Items.ARMOR_STAND) || s.`is`(Items.ITEM_FRAME) ||
        s.`is`(Items.GLOW_ITEM_FRAME) || s.`is`(Items.PAINTING) || s.`is`(Items.END_CRYSTAL) || s.`is`(Items.SHEARS) ||
        s.`is`(ItemTags.BOATS) || s.`is`(Items.MINECART) || s.`is`(Items.HONEYCOMB) || s.`is`(ItemTags.DYES))

    fun init() {
        PlayerBlockBreakEvents.BEFORE.register { level, p, pos, _, _ ->
            if (!on() || p !is ServerPlayer) return@register true
            val ok = Regions.mayBuild(p, level as ServerLevel, pos)
            if (!ok) deny(p, "build")
            ok
        }
        UseBlockCallback.EVENT.register { p, level, hand, hit ->
            if (!on() || p !is ServerPlayer) return@register InteractionResult.PASS
            val sl = level as ServerLevel
            val pos = hit.blockPos
            if (Regions.mayBuild(p, sl, pos) && Regions.mayBuild(p, sl, pos.relative(hit.direction))) return@register InteractionResult.PASS
            val held = p.getItemInHand(hand)
            val be = sl.getBlockEntity(pos)
            val sneakPlace = p.isSecondaryUseActive && modifiesWorld(held)
            if (!sneakPlace && (be is Container || be is MenuProvider && be !is net.minecraft.world.level.block.entity.EnchantingTableBlockEntity)) {
                return@register if (Regions.mayUseContainers(p, sl, pos)) InteractionResult.PASS else deny(p, "use containers")
            }
            if (modifiesWorld(held)) return@register deny(p, "build")
            if (Regions.allowed(p, sl, pos, RoleSetting.USE)) InteractionResult.PASS else deny(p, "use things")
        }
        UseEntityCallback.EVENT.register { p, level, hand, e, _ ->
            if (!on() || p !is ServerPlayer) return@register InteractionResult.PASS
            val sl = level as ServerLevel
            val setting = when (e) {
                is ArmorStand, is BlockAttachedEntity -> RoleSetting.BUILD
                is Container -> RoleSetting.CONTAINER
                is AbstractVillager, is VehicleEntity -> RoleSetting.USE
                else -> if (modifiesWorld(p.getItemInHand(hand))) RoleSetting.BUILD else return@register InteractionResult.PASS
            }
            if (Regions.allowed(p, sl, e.blockPosition(), setting)) InteractionResult.PASS else deny(p, "do that")
        }
        AttackEntityCallback.EVENT.register { p, level, _, e, _ ->
            if (!on() || p !is ServerPlayer) return@register InteractionResult.PASS
            val sl = level as ServerLevel
            when (e) {
                is Player -> if (!Regions.envAllows(sl, e.blockPosition(), EnvSetting.PVP) || !Regions.envAllows(sl, p.blockPosition(), EnvSetting.PVP)) return@register deny(p, "fight players")
                is ArmorStand, is BlockAttachedEntity, is VehicleEntity -> if (!Regions.mayBuild(p, sl, e.blockPosition())) return@register deny(p, "break that")
                is LivingEntity -> if (e !is net.minecraft.world.entity.monster.Enemy && !Regions.mayBuild(p, sl, e.blockPosition())) return@register deny(p, "hurt animals")
            }
            InteractionResult.PASS
        }
        // Projectiles and other indirect PvP.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { e, source, _ ->
            if (!on() || e !is ServerPlayer) return@register true
            val attacker = source.entity as? ServerPlayer ?: return@register true
            if (attacker == e) return@register true
            val sl = e.level() as ServerLevel
            Regions.envAllows(sl, e.blockPosition(), EnvSetting.PVP) && Regions.envAllows(sl, attacker.blockPosition(), EnvSetting.PVP)
        }
    }

    // ------------------------------------------------------------------ from mixins

    @JvmStatic
    fun filterExplosion(level: ServerLevel, blocks: MutableList<BlockPos>) {
        if (!on()) return
        blocks.removeIf { !Regions.envAllows(level, it, EnvSetting.EXPLOSIONS) }
    }

    @JvmStatic
    fun fireAllowed(level: ServerLevel, pos: BlockPos) = !on() || Regions.envAllows(level, pos, EnvSetting.FIRE)

    @JvmStatic
    fun monstersAllowed(level: ServerLevel, pos: BlockPos) = !on() || Regions.envAllows(level, pos, EnvSetting.MONSTERS)

    @JvmStatic
    fun trampleAllowed(level: ServerLevel, pos: BlockPos, entity: Entity?): Boolean {
        if (!on()) return true
        if (entity is ServerPlayer && !Regions.mayBuild(entity, level, pos)) return false
        return Regions.envAllows(level, pos, EnvSetting.TRAMPLE)
    }
}
