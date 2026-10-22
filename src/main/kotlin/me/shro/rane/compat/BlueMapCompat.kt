package me.shro.rane.compat

import de.bluecolored.bluemap.api.BlueMapAPI
import de.bluecolored.bluemap.api.markers.ExtrudeMarker
import de.bluecolored.bluemap.api.markers.MarkerSet
import de.bluecolored.bluemap.api.markers.POIMarker
import de.bluecolored.bluemap.api.math.Color
import de.bluecolored.bluemap.api.math.Shape
import me.shro.rane.Rane
import me.shro.rane.core.Scheduler
import me.shro.rane.portals.Portals
import me.shro.rane.portals.Visibility
import me.shro.rane.regions.Regions
import net.fabricmc.loader.api.FabricLoader

/** Public portals and regions on the BlueMap web map (like vane's map layers). */
object BlueMapCompat {
    fun init() {
        if (!FabricLoader.getInstance().isModLoaded("bluemap")) return
        BlueMapAPI.onEnable { refresh(it) }
        Scheduler.repeat(1200, 200) {
            BlueMapAPI.getInstance().ifPresent { runCatching { refresh(it) }.onFailure { e -> Rane.log.warn("BlueMap markers failed", e) } }
            true
        }
    }

    private fun refresh(api: BlueMapAPI) {
        val server = runCatching { Rane.server }.getOrNull() ?: return
        for (level in server.allLevels) {
            val world = api.getWorld(level).orElse(null) ?: continue
            val dim = level.dimension().identifier().toString()
            val portals = MarkerSet.builder().label("Portals").toggleable(true).build()
            if (Rane.isEnabled("portals")) for (p in Portals.store.data.portals.values) {
                if (p.dimension != dim || p.visibility != Visibility.PUBLIC) continue
                portals.markers["portal-${p.id}"] = POIMarker.builder().label("🌀 ${p.name}")
                    .position(p.origin.x + 0.5, p.origin.y + 1.0, p.origin.z + 0.5).build()
            }
            val regions = MarkerSet.builder().label("Regions").toggleable(true).defaultHidden(false).build()
            if (Rane.isEnabled("regions")) for (r in Regions.store.data.regions.values) {
                if (r.dimension != dim) continue
                regions.markers["region-${r.id}"] = ExtrudeMarker.builder().label(r.name)
                    .shape(Shape.createRect(r.minX.toDouble(), r.minZ.toDouble(), r.maxX + 1.0, r.maxZ + 1.0), r.minY.toFloat(), r.maxY + 1f)
                    .lineColor(Color(80, 200, 255, 1f)).fillColor(Color(80, 200, 255, 0.15f))
                    .position(r.minX.toDouble(), r.minY.toDouble(), r.minZ.toDouble()).build()
            }
            for (map in world.maps) {
                map.markerSets["rane-portals"] = portals
                map.markerSets["rane-regions"] = regions
            }
        }
    }
}
