package me.shro.rane.portals

import kotlinx.serialization.Serializable
import me.shro.rane.Rane
import me.shro.rane.core.Config
import me.shro.rane.core.RaneModule
import me.shro.rane.core.Store
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

@Serializable
data class PortalsConfig(
    val boundaryBlocks: List<String> = listOf("minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:gold_block",
        "minecraft:gilded_blackstone", "minecraft:emerald_block"),
    val originBlock: String = "minecraft:netherite_block",
    /** What the origin block turns into once the portal is built (it's consumed). */
    val originDisguise: String = "minecraft:obsidian",
    val consoleBlock: String = "minecraft:enchanting_table",
    val activeAreaBlock: String = "minecraft:end_gateway",
    val maxAreaBlocks: Int = 1024,
    val maxConsoleDistance: Int = 16,
    val deactivateAfterTicks: Long = 200,
    /** After going through, an entity can't use any portal for this long. */
    val teleportCooldownTicks: Long = 40,
)

enum class Visibility(val label: String) {
    PRIVATE("Private (only you)"), PUBLIC("Public"), GROUP("Region group"), GROUP_INTERNAL("Region group, internal only")
}

@Serializable
data class P(val x: Int, val y: Int, val z: Int) {
    fun pos() = BlockPos(x, y, z)
    companion object { fun of(b: BlockPos) = P(b.x, b.y, b.z) }
}

@Serializable
data class Portal(
    val id: String,
    var name: String,
    val owner: String,
    val dimension: String,
    val origin: P,
    /** "x" (frame spans the X/Y plane), "z" (Z/Y plane) or "y" (horizontal). */
    val plane: String,
    val area: List<P>,
    val boundary: List<P>,
    val consoles: MutableList<P> = mutableListOf(),
    var visibility: Visibility = Visibility.PRIVATE,
    var target: String? = null,
    var targetLocked: Boolean = false,
    var orientationLocked: Boolean = false,
    /** Icon item as JSON (ItemStack codec). */
    var icon: String? = null,
    /** Floating icon display entities, one per console. */
    val displays: MutableMap<String, String> = mutableMapOf(),
) {
    fun levelKey(): ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension))
}

@Serializable
data class PortalsData(val portals: MutableMap<String, Portal> = mutableMapOf())

/** Vane's portals: build a frame, link a console, pick a destination, flip the lever. */
object Portals : RaneModule("portals", "Buildable, styleable long-distance portals with consoles and destination menus.") {
    lateinit var config: PortalsConfig
        private set
    val store = Store.of("portals") { PortalsData() }

    /** "dim|x|y|z" -> portal id, for every block belonging to a portal. */
    private var blockIndex: Map<String, String> = emptyMap()
    private var indexValid = false

    override fun init() {
        config = Config.load("portals", PortalsConfig())
        PortalLogic.init()
        PortalMenus.init()
    }

    fun invalidate() { indexValid = false }

    private fun key(dim: String, p: BlockPos) = "$dim|${p.x}|${p.y}|${p.z}"

    fun portalAt(level: Level, pos: BlockPos): Portal? {
        if (!Rane.isEnabled(id)) return null
        if (!indexValid) {
            val m = mutableMapOf<String, String>()
            for (pt in store.data.portals.values) {
                (pt.area + pt.boundary + pt.consoles + listOf(pt.origin)).forEach { m[key(pt.dimension, it.pos())] = pt.id }
            }
            blockIndex = m
            indexValid = true
        }
        val id = blockIndex[key(level.dimension().identifier().toString(), pos)] ?: return null
        return store.data.portals[id]
    }

    fun level(p: Portal): ServerLevel? = Rane.server.getLevel(p.levelKey())
}
