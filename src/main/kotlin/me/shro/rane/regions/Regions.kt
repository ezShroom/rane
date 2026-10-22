package me.shro.rane.regions

import kotlinx.serialization.Serializable
import me.shro.rane.Rane
import me.shro.rane.core.Config
import me.shro.rane.core.Perms
import me.shro.rane.core.RaneModule
import me.shro.rane.core.Store
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.PermissionLevel
import java.util.UUID
import kotlin.math.ceil

@Serializable
data class RegionsConfig(
    /** Item paid for a region; refunded in full when the region is deleted. */
    val currency: String = "minecraft:diamond",
    /** Price = ceil(base + perBlockXZ * (x * z)) — height doesn't cost anything. */
    val costBase: Double = 1.0,
    val costPerBlockXZ: Double = 1.0 / 64.0,
    val minSizeXZ: Int = 4,
    val maxSizeXZ: Int = 512,
    val minHeight: Int = 4,
    val maxRegionsPerPlayer: Int = 32,
)

enum class RoleSetting(val label: String) { ADMIN("Administrate"), BUILD("Build"), USE("Use (doors, buttons…)"), CONTAINER("Use containers"), PORTAL("Use portals") }
enum class EnvSetting(val label: String, val default: Boolean) {
    MONSTERS("Monsters spawn", true), PVP("PvP", true), EXPLOSIONS("Explosions damage blocks", true),
    FIRE("Fire spreads", true), TRAMPLE("Farmland can be trampled", false),
}

@Serializable
data class Role(val id: String, var name: String, val settings: MutableMap<String, Boolean> = mutableMapOf()) {
    fun has(s: RoleSetting) = settings[s.name] ?: false
}

@Serializable
data class RegionGroup(
    val id: String,
    var name: String,
    val owner: String,
    val roles: MutableList<Role> = mutableListOf(),
    /** player uuid -> role id; anyone else is in the "others" role. */
    val players: MutableMap<String, String> = mutableMapOf(),
    val environment: MutableMap<String, Boolean> = mutableMapOf(),
) {
    fun roleOf(uuid: UUID): Role =
        players[uuid.toString()]?.let { id -> roles.firstOrNull { it.id == id } } ?: roles.first { it.id == OTHERS }

    fun env(s: EnvSetting) = environment[s.name] ?: s.default

    companion object {
        const val ADMINS = "admins"
        const val FRIENDS = "friends"
        const val OTHERS = "others"

        fun fresh(name: String, owner: UUID) = RegionGroup(UUID.randomUUID().toString(), name, owner.toString()).apply {
            roles += Role(ADMINS, "[Admins]", RoleSetting.entries.associate { it.name to true }.toMutableMap())
            roles += Role(FRIENDS, "Friends", mutableMapOf("BUILD" to true, "USE" to true, "CONTAINER" to true, "PORTAL" to true))
            roles += Role(OTHERS, "[Others]", mutableMapOf("USE" to true))
            players[owner.toString()] = ADMINS
        }
    }
}

@Serializable
data class Region(
    val id: String,
    var name: String,
    val owner: String,
    var group: String,
    val dimension: String,
    val minX: Int, val minY: Int, val minZ: Int,
    val maxX: Int, val maxY: Int, val maxZ: Int,
    /** What was paid, refunded on deletion. */
    val paid: Int = 0,
) {
    fun contains(dim: String, p: BlockPos) = dim == dimension &&
        p.x in minX..maxX && p.y in minY..maxY && p.z in minZ..maxZ

    fun intersects(o: Region) = dimension == o.dimension && minX <= o.maxX && maxX >= o.minX &&
        minY <= o.maxY && maxY >= o.minY && minZ <= o.maxZ && maxZ >= o.minZ
}

@Serializable
data class RegionsData(
    val regions: MutableMap<String, Region> = mutableMapOf(),
    val groups: MutableMap<String, RegionGroup> = mutableMapOf(),
    /** player uuid -> their default region group id */
    val defaultGroups: MutableMap<String, String> = mutableMapOf(),
)

/** Buy land with diamonds, group regions under shared rules, and give roles to other players. */
object Regions : RaneModule("regions", "Region-based grief protection with roles, managed through menus.") {
    lateinit var config: RegionsConfig
        private set
    val store = Store.of("regions") { RegionsData() }

    /** chunk key (dim + chunk) -> regions overlapping that chunk */
    private var index: Map<String, List<Region>> = emptyMap()
    private var indexValid = false

    override fun init() {
        config = Config.load("regions", RegionsConfig())
        Selection.init()
        Enforcement.init()
        RegionMenus.init()
    }

    fun invalidateIndex() {
        indexValid = false
    }

    private fun chunkKey(dim: String, cx: Int, cz: Int) = "$dim|$cx|$cz"

    private fun rebuildIndex() {
        val m = mutableMapOf<String, MutableList<Region>>()
        for (r in store.data.regions.values) {
            for (cx in (r.minX shr 4)..(r.maxX shr 4)) for (cz in (r.minZ shr 4)..(r.maxZ shr 4)) {
                m.getOrPut(chunkKey(r.dimension, cx, cz)) { mutableListOf() } += r
            }
        }
        index = m
        indexValid = true
    }

    fun regionAt(level: ServerLevel, pos: BlockPos): Region? {
        if (!Rane.isEnabled(id)) return null
        if (!indexValid) rebuildIndex()
        val dim = level.dimension().identifier().toString()
        return index[chunkKey(dim, pos.x shr 4, pos.z shr 4)]?.firstOrNull { it.contains(dim, pos) }
    }

    fun groupOf(region: Region): RegionGroup? = store.data.groups[region.group]

    fun bypass(player: ServerPlayer) = Perms.check(player, "rane.regions.bypass", PermissionLevel.GAMEMASTERS)

    fun allowed(player: ServerPlayer, level: ServerLevel, pos: BlockPos, setting: RoleSetting): Boolean {
        val region = regionAt(level, pos) ?: return true
        if (bypass(player)) return true
        val group = groupOf(region) ?: return true
        return group.roleOf(player.uuid).has(setting)
    }

    fun mayBuild(player: ServerPlayer, level: ServerLevel, pos: BlockPos) = allowed(player, level, pos, RoleSetting.BUILD)
    fun mayUseContainers(player: ServerPlayer, level: ServerLevel, pos: BlockPos) = allowed(player, level, pos, RoleSetting.CONTAINER)

    fun envAllows(level: ServerLevel, pos: BlockPos, setting: EnvSetting): Boolean {
        val region = regionAt(level, pos) ?: return true
        return groupOf(region)?.env(setting) ?: true
    }

    fun cost(sizeX: Int, sizeZ: Int): Int = ceil(config.costBase + config.costPerBlockXZ * sizeX * sizeZ).toInt()

    fun defaultGroup(player: ServerPlayer): RegionGroup {
        val key = player.uuid.toString()
        store.data.defaultGroups[key]?.let { store.data.groups[it] }?.let { return it }
        val g = RegionGroup.fresh("${player.gameProfile.name()}'s regions", player.uuid)
        store.edit {
            groups[g.id] = g
            defaultGroups[key] = g.id
        }
        return g
    }

    fun mayAdministrate(player: ServerPlayer, group: RegionGroup) =
        bypass(player) || group.owner == player.uuid.toString() || group.roleOf(player.uuid).has(RoleSetting.ADMIN)
}
