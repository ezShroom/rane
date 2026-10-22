package me.shro.rane

import kotlinx.serialization.Serializable
import me.shro.rane.core.Config
import me.shro.rane.core.RaneModule
import me.shro.rane.core.ResourcePackConfig
import me.shro.rane.core.ResourcePacks
import me.shro.rane.core.Store
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

@Serializable
data class RaneConfig(
    /** Turn whole modules on or off. Takes effect after a restart. */
    val modules: Map<String, Boolean> = emptyMap(),
    val resourcePacks: ResourcePackConfig = ResourcePackConfig(),
)

object Rane : ModInitializer {
    val log = LoggerFactory.getLogger("rane")

    /** The running server; only valid between SERVER_STARTING and SERVER_STOPPED. */
    lateinit var server: MinecraftServer
        private set

    val modules: List<RaneModule> by lazy {
        listOf(
            me.shro.rane.core.CoreModule,
            me.shro.rane.admin.Admin,
            me.shro.rane.bedtime.Bedtime,
            me.shro.rane.greylist.Greylist,
            me.shro.rane.regions.Regions,
            me.shro.rane.portals.Portals,
            me.shro.rane.enchantments.Enchantments,
            me.shro.rane.trifles.Trifles,
        )
    }

    lateinit var enabled: List<RaneModule>
        private set

    lateinit var config: RaneConfig
        private set

    override fun onInitialize() {
        val loaded = Config.load("rane", RaneConfig())
        // Write every module's switch into the file so it's discoverable.
        config = loaded.copy(modules = modules.associate { it.id to (loaded.modules[it.id] ?: it.enabledByDefault) })
        Config.save("rane", config)
        enabled = modules.filter { config.modules[it.id] == true }
        log.info("rane modules: {}", enabled.joinToString { it.id })

        me.shro.rane.core.Options.init()
        ResourcePacks.init(config.resourcePacks)
        enabled.forEach { it.init() }
        me.shro.rane.compat.BlueMapCompat.init()

        ServerLifecycleEvents.SERVER_STARTING.register { s ->
            server = s
            Store.all.forEach { it.load(s) }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { s -> enabled.forEach { it.serverStarted(s) } }
        ServerLifecycleEvents.BEFORE_SAVE.register { _, _, _ -> Store.all.forEach { it.save() } }
        ServerLifecycleEvents.SERVER_STOPPING.register { s ->
            enabled.forEach { it.serverStopping(s) }
            Store.all.forEach { it.save() }
        }
    }

    fun isEnabled(id: String) = enabled.any { it.id == id }
}
