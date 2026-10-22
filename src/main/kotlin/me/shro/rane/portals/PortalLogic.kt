package me.shro.rane.portals

import me.shro.rane.Rane
import me.shro.rane.core.Scheduler
import me.shro.rane.core.txt
import me.shro.rane.regions.Regions
import me.shro.rane.regions.RoleSetting
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object PortalLogic {
    private val cfg get() = Portals.config
    private val data get() = Portals.store.data
    private fun block(id: String): Block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(Blocks.OBSIDIAN)

    private val pendingLink = mutableMapOf<UUID, Pair<BlockPos, Long>>()
    /** portal id -> connected portal id while active */
    val active = mutableMapOf<String, String>()
    private val deactivationTasks = mutableMapOf<String, Scheduler.Task>()
    private val cooldown = mutableMapOf<UUID, Long>()

    fun init() {
        UseBlockCallback.EVENT.register { p, level, hand, hit ->
            if (!Rane.isEnabled("portals") || p !is ServerPlayer || hand != InteractionHand.MAIN_HAND) return@register InteractionResult.PASS
            val sl = level as ServerLevel
            val pos = hit.blockPos
            val state = sl.getBlockState(pos)
            val existing = Portals.portalAt(sl, pos)
            // Nothing may be placed into a portal's area.
            Portals.portalAt(sl, pos.relative(hit.direction))?.let { pt ->
                if (pt.area.any { it.pos() == pos.relative(hit.direction) } && !p.mainHandItem.isEmpty) return@register InteractionResult.FAIL
            }
            if (existing != null && existing.consoles.any { it.pos() == pos }) {
                PortalMenus.console(p, existing)
                return@register InteractionResult.SUCCESS
            }
            if (!p.mainHandItem.isEmpty) return@register InteractionResult.PASS
            if (p.isShiftKeyDown && state.`is`(block(cfg.consoleBlock))) {
                pendingLink[p.uuid] = pos.immutable() to System.currentTimeMillis()
                p.sendSystemMessage(txt("§7Now right-click a block of the portal frame to link this console."))
                return@register InteractionResult.SUCCESS
            }
            val pending = pendingLink[p.uuid]?.takeIf { System.currentTimeMillis() - it.second < 60_000 } ?: return@register InteractionResult.PASS
            pendingLink.remove(p.uuid)
            link(p, sl, pending.first, pos)
            InteractionResult.SUCCESS
        }
        PlayerBlockBreakEvents.BEFORE.register { level, p, pos, _, _ ->
            if (Portals.portalAt(level, pos) == null) return@register true
            (p as? ServerPlayer)?.sendOverlayMessage(txt("§cThis block belongs to a portal. Delete the portal from its console first."))
            false
        }
    }

    // ------------------------------------------------------------------ construction

    private data class Shape(val plane: String, val area: Set<BlockPos>, val boundary: Set<BlockPos>)

    private fun planeDirs(plane: String) = when (plane) {
        "x" -> listOf(Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN)
        "z" -> listOf(Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN)
        else -> listOf(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH)
    }

    private fun detect(level: ServerLevel, clicked: BlockPos): Shape? {
        val frame = cfg.boundaryBlocks.map { block(it) }.toSet() + block(cfg.originBlock)
        for (plane in listOf("x", "z", "y")) {
            val dirs = planeDirs(plane)
            for (d in dirs) {
                val start = clicked.relative(d)
                if (!level.getBlockState(start).isAir) continue
                val area = mutableSetOf<BlockPos>()
                val boundary = mutableSetOf<BlockPos>()
                val queue = ArrayDeque(listOf(start))
                var ok = true
                while (queue.isNotEmpty() && ok) {
                    val p = queue.removeFirst()
                    if (p in area) continue
                    area += p
                    if (area.size > cfg.maxAreaBlocks) { ok = false; break }
                    for (dd in dirs) {
                        val n = p.relative(dd)
                        if (n in area) continue
                        val s = level.getBlockState(n)
                        when {
                            s.isAir -> queue += n
                            s.block in frame -> boundary += n.immutable()
                            else -> ok = false
                        }
                    }
                }
                if (ok && clicked in boundary) return Shape(plane, area, boundary)
            }
        }
        return null
    }

    private fun link(p: ServerPlayer, level: ServerLevel, console: BlockPos, frameBlock: BlockPos) {
        Portals.portalAt(level, frameBlock)?.let { existing ->
            if (existing.owner != p.uuid.toString() && !Regions.bypass(p)) {
                p.sendSystemMessage(txt("§cOnly the owner can add consoles to this portal."))
                return
            }
            Portals.store.edit { existing.consoles += P.of(console) }
            Portals.invalidate()
            refreshDisplays(existing)
            p.sendSystemMessage(txt("§aConsole linked to §b${existing.name}§a."))
            return
        }
        val shape = detect(level, frameBlock)
        if (shape == null) {
            p.sendSystemMessage(txt("§cThat isn't a closed portal frame. Use only ${cfg.boundaryBlocks.joinToString { it.removePrefix("minecraft:") }} and one ${cfg.originBlock.removePrefix("minecraft:")}, enclosing empty air."))
            return
        }
        val origins = shape.boundary.filter { level.getBlockState(it).`is`(block(cfg.originBlock)) }
        if (origins.size != 1) {
            p.sendSystemMessage(txt("§cThe frame needs exactly one ${cfg.originBlock.removePrefix("minecraft:")} (the origin block)."))
            return
        }
        val origin = origins.first()
        if (shape.plane != "y" && (1..3).any { origin.above(it) !in shape.area }) {
            p.sendSystemMessage(txt("§cThe origin block needs at least 3 blocks of open portal area above it."))
            return
        }
        if (!console.closerThan(origin, cfg.maxConsoleDistance.toDouble())) {
            p.sendSystemMessage(txt("§cThe console is too far from the portal."))
            return
        }
        if (Rane.isEnabled("regions") && !Regions.mayBuild(p, level, origin)) {
            p.sendSystemMessage(txt("§cYou can't build a portal here."))
            return
        }
        me.shro.rane.core.Menus.prompt(p, "Name your portal", "${p.gameProfile.name()}'s portal") { name ->
            if (detect(level, frameBlock) == null || !level.getBlockState(origin).`is`(block(cfg.originBlock))) return@prompt
            val portal = Portal(UUID.randomUUID().toString(), name, p.uuid.toString(), level.dimension().identifier().toString(),
                P.of(origin), shape.plane, shape.area.map { P.of(it) }, shape.boundary.map { P.of(it) }, mutableListOf(P.of(console)))
            level.setBlock(origin, block(cfg.originDisguise).defaultBlockState(), Block.UPDATE_ALL)
            Portals.store.edit { portals[portal.id] = portal }
            Portals.invalidate()
            refreshDisplays(portal)
            level.playSound(null, origin, SoundEvents.END_PORTAL_SPAWN, SoundSource.BLOCKS, 0.6f, 1.4f)
            p.sendSystemMessage(txt("§aPortal §b$name§a created. It's private; open the console to change that. Flip a lever on the frame or next to the console to travel."))
        }
    }

    fun delete(portal: Portal) {
        deactivate(portal.id)
        removeDisplays(portal)
        Portals.store.edit {
            portals.remove(portal.id)
            portals.values.filter { it.target == portal.id }.forEach { it.target = null }
        }
        Portals.invalidate()
    }

    // ------------------------------------------------------------------ visibility

    fun mayUse(p: ServerPlayer, portal: Portal): Boolean {
        val level = Portals.level(portal) ?: return false
        return !Rane.isEnabled("regions") || Regions.allowed(p, level, portal.origin.pos(), RoleSetting.PORTAL)
    }

    fun visible(p: ServerPlayer, from: Portal, to: Portal): Boolean {
        if (to.id == from.id) return false
        if (to.owner == p.uuid.toString()) return true
        val toLevel = Portals.level(to) ?: return false
        val toRegion = if (Rane.isEnabled("regions")) Regions.regionAt(toLevel, to.origin.pos()) else null
        return when (to.visibility) {
            Visibility.PUBLIC -> true
            Visibility.PRIVATE -> false
            Visibility.GROUP -> toRegion != null && Regions.allowed(p, toLevel, to.origin.pos(), RoleSetting.PORTAL)
            Visibility.GROUP_INTERNAL -> {
                val fromLevel = Portals.level(from) ?: return false
                val fromRegion = if (Rane.isEnabled("regions")) Regions.regionAt(fromLevel, from.origin.pos()) else null
                toRegion != null && fromRegion != null && fromRegion.group == toRegion.group
            }
        }
    }

    // ------------------------------------------------------------------ activation (LeverBlockMixin)

    @JvmStatic
    fun onLever(level: Level, pos: BlockPos, player: net.minecraft.world.entity.player.Player?) {
        if (level !is ServerLevel || !Rane.isEnabled("portals")) return
        val powered = level.getBlockState(pos).getValue(net.minecraft.world.level.block.LeverBlock.POWERED)
        val portal = Direction.entries.firstNotNullOfOrNull { d -> Portals.portalAt(level, pos.relative(d))?.takeIf { pt -> pt.boundary.any { it.pos() == pos.relative(d) } || pt.origin.pos() == pos.relative(d) } }
            ?: data.portals.values.firstOrNull { pt -> pt.dimension == level.dimension().identifier().toString() &&
                pt.consoles.any { c -> Math.abs(c.x - pos.x) <= 1 && Math.abs(c.y - pos.y) <= 1 && Math.abs(c.z - pos.z) <= 1 } }
            ?: return
        val sp = player as? ServerPlayer
        if (!powered) { deactivate(portal.id); return }
        val target = portal.target?.let { data.portals[it] }
        when {
            target == null -> sp?.sendOverlayMessage(txt("§7Select a destination at the console first."))
            sp != null && !mayUse(sp, portal) -> sp.sendOverlayMessage(txt("§cYou may not use this portal."))
            sp != null && !visible(sp, portal, target) -> sp.sendOverlayMessage(txt("§cYou can't travel to that destination."))
            portal.id in active || target.id in active -> sp?.sendOverlayMessage(txt("§7That destination is already connected somewhere else."))
            else -> activate(portal, target)
        }
    }

    private fun setArea(portal: Portal, on: Boolean) {
        val level = Portals.level(portal) ?: return
        val state = if (on) block(cfg.activeAreaBlock).defaultBlockState() else Blocks.AIR.defaultBlockState()
        for (a in portal.area) {
            val cur = level.getBlockState(a.pos())
            if (on && !cur.isAir) continue
            if (!on && !cur.`is`(block(cfg.activeAreaBlock))) continue
            level.setBlock(a.pos(), state, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
        }
    }

    private fun activate(a: Portal, b: Portal) {
        active[a.id] = b.id
        active[b.id] = a.id
        setArea(a, true)
        setArea(b, true)
        for (pt in listOf(a, b)) Portals.level(pt)?.playSound(null, pt.origin.pos(), SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 1f, 1.4f)
        val task = Scheduler.later(cfg.deactivateAfterTicks) { deactivate(a.id) }
        deactivationTasks[a.id] = task
        deactivationTasks[b.id] = task
    }

    fun deactivate(id: String) {
        val other = active.remove(id) ?: return
        active.remove(other)
        deactivationTasks.remove(id)?.cancel()
        deactivationTasks.remove(other)?.cancel()
        for (pid in listOf(id, other)) {
            val pt = data.portals[pid] ?: continue
            setArea(pt, false)
            Portals.level(pt)?.playSound(null, pt.origin.pos(), SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 1f, 1.4f)
        }
        // The source forgets a non-public destination unless it's target-locked.
        data.portals[id]?.let { src ->
            val t = src.target?.let { data.portals[it] }
            if (t != null && t.visibility != Visibility.PUBLIC && !src.targetLocked) {
                Portals.store.edit { src.target = null }
                refreshDisplays(src)
            }
        }
    }

    // ------------------------------------------------------------------ travel (EndGatewayBlockMixin)

    private fun normal(plane: String): Vec3 = when (plane) { "x" -> Vec3(0.0, 0.0, 1.0); "z" -> Vec3(1.0, 0.0, 0.0); else -> Vec3(0.0, 1.0, 0.0) }
    private fun yawOf(v: Vec3) = Math.toDegrees(atan2(-v.x, v.z)).toFloat()

    /** Returns true if [pos] is part of a rane portal (vanilla gateway behaviour is then skipped). */
    @JvmStatic
    fun onEntityInside(level: Level, pos: BlockPos, entity: Entity): Boolean {
        if (level !is ServerLevel) return false
        val source = Portals.portalAt(level, pos) ?: return false
        val root = entity.rootVehicle
        val target = active[source.id]?.let { data.portals[it] } ?: return true
        if ((cooldown[root.uuid] ?: 0) > Scheduler.tick) return true
        if (root is ServerPlayer && !mayUse(root, source)) return true
        val tLevel = Portals.level(target) ?: return true
        travel(root, source, target, tLevel)
        return true
    }

    private fun travel(e: Entity, source: Portal, target: Portal, tLevel: ServerLevel) {
        val sn = normal(source.plane)
        val tn = normal(target.plane)
        val motion = e.deltaMovement.let { if (Vec3(it.x, 0.0, it.z).lengthSqr() > 1e-4) it else e.lookAngle }
        val side = if (motion.dot(sn) >= 0) 1.0 else -1.0
        val bothVertical = source.plane != "y" && target.plane != "y"
        val exitDir = if (target.orientationLocked) tn else tn.scale(side)
        val dYaw = if (bothVertical) yawOf(exitDir) - yawOf(sn.scale(side)) else 0f
        val r = Math.toRadians(-dYaw.toDouble())
        val v = e.deltaMovement
        val nv = Vec3(v.x * cos(r) - v.z * sin(r), v.y, v.x * sin(r) + v.z * cos(r))
        val o = target.origin.pos()
        val dest = if (target.plane == "y") {
            // Horizontal: arrive in the middle, just under the portal plane.
            Vec3(target.area.map { it.x }.average() + 0.5, o.y - e.bbHeight - 0.05, target.area.map { it.z }.average() + 0.5)
        } else Vec3(o.x + 0.5, o.y + 1.0, o.z + 0.5).add(exitDir.scale(1.0))
        cooldown[e.uuid] = Scheduler.tick + cfg.teleportCooldownTicks
        e.indirectPassengers.forEach { cooldown[it.uuid] = Scheduler.tick + cfg.teleportCooldownTicks }
        val from = e.position()
        (e.level() as ServerLevel).sendParticles(ParticleTypes.PORTAL, from.x, from.y + 1, from.z, 60, 0.5, 1.0, 0.5, 0.5)
        e.teleport(TeleportTransition(tLevel, dest, nv, e.yRot + dYaw, e.xRot, TeleportTransition.DO_NOTHING))
        tLevel.playSound(null, dest.x, dest.y, dest.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.7f, 1.2f)
    }

    // ------------------------------------------------------------------ floating icons

    fun iconOf(portal: Portal?): ItemStack {
        val json = portal?.icon ?: return ItemStack(Items.ENDER_PEARL)
        return runCatching {
            ItemStack.CODEC.parse(RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, Rane.server.registryAccess()),
                com.google.gson.JsonParser.parseString(json)).result().orElse(null)
        }.getOrNull() ?: ItemStack(Items.ENDER_PEARL)
    }

    fun encodeIcon(stack: ItemStack): String = ItemStack.CODEC.encodeStart(
        RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, Rane.server.registryAccess()), stack.copyWithCount(1)).getOrThrow().toString()

    private fun removeDisplays(portal: Portal) {
        val level = Portals.level(portal) ?: return
        portal.displays.values.forEach { id -> runCatching { level.getEntity(UUID.fromString(id))?.discard() } }
        portal.displays.clear()
    }

    /** One floating, spinning-free icon of the selected destination above each console. */
    fun refreshDisplays(portal: Portal) {
        val level = Portals.level(portal) ?: return
        removeDisplays(portal)
        val target = portal.target?.let { data.portals[it] } ?: run { Portals.store.markDirty(); return }
        val ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess())
        for (c in portal.consoles) {
            val tag = CompoundTag()
            tag.put("item", ItemStack.CODEC.encodeStart(ops, iconOf(target)).getOrThrow())
            tag.putString("billboard", "vertical")
            tag.put("transformation", net.minecraft.nbt.TagParser.parseCompoundFully(
                "{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],scale:[0.5f,0.5f,0.5f],right_rotation:[0f,0f,0f,1f]}"))
            val e = net.minecraft.world.entity.EntityTypes.ITEM_DISPLAY.create(level, EntitySpawnReason.COMMAND) ?: continue
            net.minecraft.util.ProblemReporter.ScopedCollector(org.slf4j.LoggerFactory.getLogger("rane/portals")).use { r ->
                e.load(net.minecraft.world.level.storage.TagValueInput.create(r, level.registryAccess(), tag))
            }
            e.snapTo(c.x + 0.5, c.y + 1.35, c.z + 0.5, 0f, 0f)
            e.addTag("rane.portal_icon")
            level.addFreshEntity(e)
            portal.displays["${c.x},${c.y},${c.z}"] = e.uuid.toString()
        }
        Portals.store.markDirty()
    }
}
