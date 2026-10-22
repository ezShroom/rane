package me.shro.rane.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * JSON configuration in `config/rane/`. Files are rewritten on load so newly added options show
 * up with their defaults, and unknown keys are ignored.
 */
object Config {
    private val log = LoggerFactory.getLogger("rane/config")

    val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
        allowComments = true
        allowTrailingComma = true
    }

    val dir: Path get() = FabricLoader.getInstance().configDir.resolve("rane")

    fun <T> load(name: String, serializer: KSerializer<T>, default: T): T {
        val file = dir.resolve("$name.json")
        val value = try {
            if (Files.exists(file)) json.decodeFromString(serializer, Files.readString(file)) else default
        } catch (e: Exception) {
            log.error("Could not read {}, using defaults. Fix the file and restart; it was left untouched.", file, e)
            return default
        }
        try {
            Files.createDirectories(dir)
            Files.writeString(file, json.encodeToString(serializer, value) + "\n")
        } catch (e: Exception) {
            log.warn("Could not write {}", file, e)
        }
        return value
    }

    inline fun <reified T> load(name: String, default: T): T = load(name, serializer<T>(), default)

    fun <T> save(name: String, serializer: KSerializer<T>, value: T) {
        val file = dir.resolve("$name.json")
        Files.createDirectories(dir)
        Files.writeString(file, json.encodeToString(serializer, value) + "\n")
    }

    inline fun <reified T> save(name: String, value: T) = save(name, serializer<T>(), value)
}
