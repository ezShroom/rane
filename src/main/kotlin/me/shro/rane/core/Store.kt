package me.shro.rane.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Per-world persistent state, stored as JSON in `<world>/rane/<name>.json`. Loaded when the server
 * starts, saved whenever the world saves (and on shutdown) if it was marked dirty.
 */
class Store<T : Any>(val name: String, private val serializer: KSerializer<T>, private val fresh: () -> T) {
    private val log = LoggerFactory.getLogger("rane/store")
    private var value: T? = null
    private var path: Path? = null
    private var dirty = false

    val data: T get() = value ?: error("store '$name' used before the server started")

    fun markDirty() {
        dirty = true
    }

    /** Mutates the data and marks it dirty. */
    inline fun <R> edit(block: T.() -> R): R {
        val r = data.block()
        markDirty()
        return r
    }

    internal fun load(server: MinecraftServer) {
        val p = server.getWorldPath(LevelResource.ROOT).resolve("rane").resolve("$name.json").normalize()
        path = p
        value = try {
            if (Files.exists(p)) Config.json.decodeFromString(serializer, Files.readString(p)) else fresh()
        } catch (e: Exception) {
            log.error("Could not read {}; starting empty and keeping a backup", p, e)
            runCatching { Files.copy(p, p.resolveSibling("$name.json.broken"), StandardCopyOption.REPLACE_EXISTING) }
            fresh()
        }
        dirty = false
    }

    internal fun save(force: Boolean = false) {
        val p = path ?: return
        val v = value ?: return
        if (!dirty && !force) return
        try {
            Files.createDirectories(p.parent)
            val tmp = p.resolveSibling("$name.json.tmp")
            Files.writeString(tmp, Config.json.encodeToString(serializer, v))
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            dirty = false
        } catch (e: Exception) {
            log.error("Could not save {}", p, e)
        }
    }

    companion object {
        internal val all = mutableListOf<Store<*>>()

        inline fun <reified T : Any> of(name: String, noinline fresh: () -> T): Store<T> =
            Store(name, serializer<T>(), fresh).also { register(it) }

        fun register(store: Store<*>) {
            all += store
        }
    }
}
