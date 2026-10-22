package me.shro.rane.trifles

import kotlinx.serialization.Serializable
import me.shro.rane.core.Config
import me.shro.rane.core.RaneModule
import me.shro.rane.core.Store
import me.shro.rane.item.ItemHooks

@Serializable
data class TriflesConfig(
    val scrolls: Scrolls = Scrolls(),
    val sickles: Sickles = Sickles(),
    val xpBottles: XpBottles = XpBottles(),
    val reinforcedElytraArmor: Double = 6.0,
    val fastWalking: FastWalking = FastWalking(),
    val harvesting: Boolean = true,
    val doubleDoors: Boolean = true,
    val chestSorting: ChestSorting = ChestSorting(),
    val invisibleItemFrames: Boolean = true,
    /** Anvil costs are capped below "Too Expensive!" (0 disables). */
    val repairCostLimit: Int = 39,
    val unlockAllRecipes: Boolean = true,
    val itemFinder: ItemFinder = ItemFinder(),
    val heads: Heads = Heads(),
    val setspawnCommand: Boolean = true,
) {
    @Serializable
    data class Scrolls(
        /** After taking damage, scrolls can't be used for this long (stops combat logging). */
        val damageCooldownMs: Long = 15000,
        val homeCooldownMs: Long = 10000,
        val unstableCooldownMs: Long = 6000,
        val spawnCooldownMs: Long = 6000,
        val lodestoneCooldownMs: Long = 6000,
        val deathCooldownMs: Long = 6000,
        /** The death scroll only works for deaths within this many minutes. */
        val deathScrollWindowMinutes: Long = 20,
        val allowMending: Boolean = false,
    )

    @Serializable
    data class Sickles(
        val wooden: Sickle = Sickle(1.0, 1.0, 1),
        val stone: Sickle = Sickle(1.5, 1.5, 1),
        val iron: Sickle = Sickle(2.0, 2.0, 2),
        val golden: Sickle = Sickle(1.5, 3.5, 3),
        val diamond: Sickle = Sickle(2.5, 2.5, 2),
        val netherite: Sickle = Sickle(3.0, 2.5, 2),
    )

    @Serializable
    data class Sickle(val attackDamage: Double, val attackSpeed: Double, val harvestRadius: Int)

    @Serializable
    data class XpBottles(
        /** Share of experience lost when bottling. */
        val lossPercentage: Double = 0.3,
        val smallLevels: Int = 10,
        val mediumLevels: Int = 20,
        val largeLevels: Int = 30,
    )

    @Serializable
    data class FastWalking(
        val enabled: Boolean = true,
        val speedLevel: Int = 2,
        val durationMs: Long = 2000,
        val blocks: List<String> = listOf("minecraft:dirt_path"),
    )

    @Serializable
    data class ChestSorting(
        val enabled: Boolean = true,
        val cooldownMs: Long = 1000,
        val radius: Int = 1,
    )

    @Serializable
    data class ItemFinder(
        val enabled: Boolean = true,
        val chunkRadius: Int = 2,
        val searchEntities: Boolean = true,
    )

    @Serializable
    data class Heads(
        val enabled: Boolean = true,
        /** Item paid per head (0 price = free). */
        val currency: String = "minecraft:bone",
        val price: Int = 1,
    )
}

@Serializable
data class TriflesPlayer(
    var lastScrollFrom: StoredPos? = null,
    var lastDeath: StoredPos? = null,
    var lastDeathTime: Long = 0,
)

@Serializable
data class StoredPos(val dimension: String, val x: Double, val y: Double, val z: Double, val yaw: Float = 0f, val pitch: Float = 0f)

@Serializable
data class TriflesData(val players: MutableMap<String, TriflesPlayer> = mutableMapOf())

/** Lore-friendly custom items and quality-of-life tweaks from vane-trifles. */
object Trifles : RaneModule("trifles", "Custom items (scrolls, sickles, backpacks, …) and quality-of-life tweaks.") {
    lateinit var config: TriflesConfig
        private set
    val store = Store.of("trifles") { TriflesData() }

    fun playerData(uuid: java.util.UUID): TriflesPlayer = store.data.players.getOrPut(uuid.toString()) { TriflesPlayer() }

    override fun init() {
        config = Config.load("trifles", TriflesConfig())
        ItemHooks.init()
        TriflesItems.register()
        Scrolls.init()
        Sickles.init()
        FileTool.init()
        Trowel.init()
        XpBottles.init()
        Storage.init()
        Misc.init()
        ItemFinder.init()
        Heads.init()
    }
}
