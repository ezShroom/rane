package me.shro.rane.core

import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.config.ServerResourcePackConfigurationTask
import java.util.UUID

@Serializable
data class PackEntry(
    val url: String,
    val sha1: String = "",
    /** Stable id so clients cache the pack; derived from the url when left empty. */
    val uuid: String = "",
)

@Serializable
data class ResourcePackConfig(
    /** Packs pushed to every player while they join, in order (later packs override earlier ones). */
    val packs: List<PackEntry> = emptyList(),
    val required: Boolean = true,
    val prompt: String = "&6This server uses resource packs for custom items and mobs.",
)

/**
 * Pushes any number of server resource packs during the configuration phase, using the same
 * vanilla configuration task as `server.properties` does for its single pack.
 */
object ResourcePacks {
    fun init(config: ResourcePackConfig) {
        if (config.packs.isEmpty()) return
        val infos = config.packs.map {
            val id = if (it.uuid.isNotBlank()) UUID.fromString(it.uuid) else UUID.nameUUIDFromBytes(it.url.toByteArray())
            MinecraftServer.ServerResourcePackInfo(id, it.url, it.sha1, config.required, txt(config.prompt))
        }
        ServerConfigurationConnectionEvents.CONFIGURE.register { handler, _ ->
            infos.forEach { handler.addTask(ServerResourcePackConfigurationTask(it)) }
        }
    }
}
