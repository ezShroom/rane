package me.shro.rane.core

import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ConfigurationTask
import java.util.Optional
import java.util.UUID
import java.util.function.Consumer

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
 * Pushes any number of server resource packs during the configuration phase. Vanilla's own task
 * finishes on the first final response for *any* pack, and clients report earlier packs again when
 * they reload with later ones, so all packs go out in one [Task] that waits for each id
 * (see `ServerConfigurationPacketListenerImplMixin`).
 */
object ResourcePacks {
    fun init(config: ResourcePackConfig) {
        if (config.packs.isEmpty()) return
        val infos = config.packs.map {
            val id = if (it.uuid.isNotBlank()) UUID.fromString(it.uuid) else UUID.nameUUIDFromBytes(it.url.toByteArray())
            MinecraftServer.ServerResourcePackInfo(id, it.url, it.sha1, config.required, txt(config.prompt))
        }
        ServerConfigurationConnectionEvents.CONFIGURE.register { handler, _ ->
            handler.addTask(Task(infos))
        }
    }

    class Task(private val packs: List<MinecraftServer.ServerResourcePackInfo>) : ConfigurationTask {
        private val waiting = packs.map { it.id }.toMutableSet()

        override fun start(sender: Consumer<Packet<*>>) {
            for (p in packs) sender.accept(ClientboundResourcePackPushPacket(p.id, p.url, p.hash, p.isRequired, Optional.ofNullable(p.prompt)))
        }

        /** Records a final response; true once every pack has one. */
        fun finished(id: UUID): Boolean {
            waiting -= id
            return waiting.isEmpty()
        }

        override fun type() = TYPE

        companion object {
            val TYPE = ConfigurationTask.Type("rane:resource_packs")
        }
    }
}
