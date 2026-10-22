package me.shro.rane.regions

import me.shro.rane.Rane
import me.shro.rane.core.Scheduler
import me.shro.rane.core.txt
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import java.util.UUID

/** Region selection: left/right click with an empty hand sets the two corners. */
object Selection {
    class Sel(var level: ServerLevel? = null, var a: BlockPos? = null, var b: BlockPos? = null)

    val active = mutableMapOf<UUID, Sel>()

    fun start(player: ServerPlayer) {
        active[player.uuid] = Sel()
        player.sendSystemMessage(txt("§7Selecting a region: §bleft-click§7 and §bright-click§7 blocks with an empty hand to set the two corners, then open §b/rg§7 again."))
    }

    fun cancel(player: ServerPlayer) = active.remove(player.uuid)

    fun init() {
        AttackBlockCallback.EVENT.register { p, level, hand, pos, _ ->
            val sel = (p as? ServerPlayer)?.let { active[it.uuid] } ?: return@register InteractionResult.PASS
            if (hand != InteractionHand.MAIN_HAND || !p.mainHandItem.isEmpty) return@register InteractionResult.PASS
            set(p, sel, level as ServerLevel, pos, true)
            InteractionResult.FAIL
        }
        UseBlockCallback.EVENT.register { p, level, hand, hit ->
            val sel = (p as? ServerPlayer)?.let { active[it.uuid] } ?: return@register InteractionResult.PASS
            if (hand != InteractionHand.MAIN_HAND || !p.mainHandItem.isEmpty) return@register InteractionResult.PASS
            set(p, sel, level as ServerLevel, hit.blockPos, false)
            InteractionResult.SUCCESS
        }
        ServerPlayConnectionEvents.DISCONNECT.register { h, _ -> active.remove(h.player.uuid) }
        Scheduler.repeat(10) {
            if (Rane.isEnabled("regions")) for ((uuid, sel) in active) {
                val p = Rane.server.playerList.getPlayer(uuid) ?: continue
                outline(p, sel)
            }
            true
        }
    }

    private fun set(p: ServerPlayer, sel: Sel, level: ServerLevel, pos: BlockPos, first: Boolean) {
        if (sel.level != level) { sel.a = null; sel.b = null; sel.level = level }
        if (first) sel.a = pos.immutable() else sel.b = pos.immutable()
        val r = toRegion(p, sel)
        val info = if (r != null) " §7(${r.maxX - r.minX + 1}×${r.maxY - r.minY + 1}×${r.maxZ - r.minZ + 1}, costs §b${Regions.cost(r.maxX - r.minX + 1, r.maxZ - r.minZ + 1)}§7)" else ""
        p.sendOverlayMessage(txt("§aCorner ${if (first) 1 else 2} set to ${pos.x}, ${pos.y}, ${pos.z}$info"))
    }

    /** The selected cuboid as a (not yet saved) region, or null if incomplete. */
    fun toRegion(p: ServerPlayer, sel: Sel): Region? {
        val a = sel.a ?: return null
        val b = sel.b ?: return null
        val level = sel.level ?: return null
        return Region("", "", p.uuid.toString(), "", level.dimension().identifier().toString(),
            minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z), maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))
    }

    /** Problems with the current selection; empty when it can be bought. */
    fun problems(p: ServerPlayer, sel: Sel): List<String> {
        val r = toRegion(p, sel) ?: return listOf("Select both corners first.")
        val c = Regions.config
        val sx = r.maxX - r.minX + 1
        val sy = r.maxY - r.minY + 1
        val sz = r.maxZ - r.minZ + 1
        val out = mutableListOf<String>()
        if (sx < c.minSizeXZ || sz < c.minSizeXZ) out += "Too small: at least ${c.minSizeXZ}×${c.minSizeXZ}."
        if (sx > c.maxSizeXZ || sz > c.maxSizeXZ) out += "Too large: at most ${c.maxSizeXZ}×${c.maxSizeXZ}."
        if (sy < c.minHeight) out += "Too flat: at least ${c.minHeight} blocks high."
        if (Regions.store.data.regions.values.any { it.intersects(r) }) out += "Overlaps an existing region."
        val owned = Regions.store.data.regions.values.count { it.owner == p.uuid.toString() }
        if (owned >= c.maxRegionsPerPlayer && !Regions.bypass(p)) out += "You already own ${c.maxRegionsPerPlayer} regions."
        return out
    }

    private fun outline(p: ServerPlayer, sel: Sel) {
        val r = toRegion(p, sel) ?: return
        val level = sel.level ?: return
        if (p.level() != level) return
        val ok = problems(p, sel).isEmpty()
        val dust = DustParticleOptions(if (ok) 0x33FF55 else 0xFF3333, 1.2f)
        val x0 = r.minX.toDouble(); val y0 = r.minY.toDouble(); val z0 = r.minZ.toDouble()
        val x1 = r.maxX + 1.0; val y1 = r.maxY + 1.0; val z1 = r.maxZ + 1.0
        val edges = listOf(
            listOf(x0, y0, z0, x1, y0, z0), listOf(x0, y0, z1, x1, y0, z1), listOf(x0, y1, z0, x1, y1, z0), listOf(x0, y1, z1, x1, y1, z1),
            listOf(x0, y0, z0, x0, y0, z1), listOf(x1, y0, z0, x1, y0, z1), listOf(x0, y1, z0, x0, y1, z1), listOf(x1, y1, z0, x1, y1, z1),
            listOf(x0, y0, z0, x0, y1, z0), listOf(x1, y0, z0, x1, y1, z0), listOf(x0, y0, z1, x0, y1, z1), listOf(x1, y0, z1, x1, y1, z1),
        )
        for (e in edges) {
            val len = Math.sqrt((e[3] - e[0]).let { it * it } + (e[4] - e[1]).let { it * it } + (e[5] - e[2]).let { it * it })
            val steps = minOf(200, maxOf(1, (len * 2).toInt()))
            for (i in 0..steps) {
                val t = i.toDouble() / steps
                val x = e[0] + (e[3] - e[0]) * t; val y = e[1] + (e[4] - e[1]) * t; val z = e[2] + (e[5] - e[2]) * t
                if (p.distanceToSqr(x, y, z) > 64 * 64) continue
                level.sendParticles(p, dust, true, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }
}
