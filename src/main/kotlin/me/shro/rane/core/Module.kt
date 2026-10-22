package me.shro.rane.core

import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * A rane module: a group of related features that can be switched off as a whole in
 * `config/rane/modules.json`. Modules register their events, commands, items and recipes in [init],
 * which runs once at mod initialisation if the module is enabled.
 */
abstract class RaneModule(val id: String, val description: String) {
    val log: Logger = LoggerFactory.getLogger("rane/$id")

    /** Whether the module is on when no setting exists yet. */
    open val enabledByDefault: Boolean = true

    open fun init() {}

    open fun serverStarted(server: MinecraftServer) {}

    open fun serverStopping(server: MinecraftServer) {}
}
