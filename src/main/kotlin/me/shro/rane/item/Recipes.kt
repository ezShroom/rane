package me.shro.rane.item

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mojang.serialization.JsonOps
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeHolder
import org.slf4j.LoggerFactory

/**
 * Recipes defined in code. They're turned into vanilla recipe JSON, parsed with the vanilla codec
 * and added to the recipe manager (see `RecipeManagerMixin`), so they behave exactly like data
 * pack recipes, recipe book included.
 *
 * Ingredients are strings: `minecraft:stick`, `#minecraft:planks`, or `rane:<custom item>`.
 */
object Recipes {
    private val log = LoggerFactory.getLogger("rane/recipes")
    private val defs = linkedMapOf<String, (RegistryOps<JsonElement>) -> JsonObject>()

    /** Result: a custom item id, or a vanilla item id like `minecraft:elytra`. */
    class Result(val id: String, val count: Int = 1, val extraComponents: Map<String, Any> = emptyMap())

    fun shaped(id: String, result: Result, vararg pattern: String, category: String = "misc", key: Map<Char, String>) {
        defs[id] = { ops ->
            JsonObject().apply {
                addProperty("type", "minecraft:crafting_shaped")
                addProperty("category", category)
                add("pattern", JsonArray().also { a -> pattern.forEach { a.add(it) } })
                add("key", JsonObject().also { k -> key.forEach { (c, ing) -> k.add(c.toString(), ingredient(ing)) } })
                add("result", result(ops, result))
            }
        }
    }

    fun shapeless(id: String, result: Result, vararg ingredients: String, category: String = "misc") {
        defs[id] = { ops ->
            JsonObject().apply {
                addProperty("type", "minecraft:crafting_shapeless")
                addProperty("category", category)
                add("ingredients", JsonArray().also { a -> ingredients.forEach { a.add(ingredient(it)) } })
                add("result", result(ops, result))
            }
        }
    }

    fun smithing(id: String, result: Result, template: String?, base: String, addition: String?) {
        defs[id] = { ops ->
            JsonObject().apply {
                addProperty("type", "minecraft:smithing_transform")
                if (template != null) add("template", ingredient(template))
                add("base", ingredient(base))
                if (addition != null) add("addition", ingredient(addition))
                add("result", result(ops, result))
            }
        }
    }

    fun stonecutting(id: String, result: Result, ingredient: String) {
        defs[id] = { ops ->
            JsonObject().apply {
                addProperty("type", "minecraft:stonecutting")
                add("ingredient", ingredient(ingredient))
                add("result", result(ops, result))
            }
        }
    }

    fun cooking(id: String, type: String, result: Result, ingredient: String, xp: Float = 0.1f, time: Int = 200) {
        defs[id] = { ops ->
            JsonObject().apply {
                addProperty("type", "minecraft:$type")
                add("ingredient", ingredient(ingredient))
                add("result", result(ops, result))
                addProperty("experience", xp)
                addProperty("cookingtime", time)
            }
        }
    }

    private fun ingredient(s: String): JsonElement {
        if (s.startsWith("rane:")) {
            val item = CustomItems[s.removePrefix("rane:")] ?: error("unknown custom item in recipe: $s")
            // Fabric's component ingredient: matches the base item carrying our marker.
            return JsonObject().apply {
                addProperty("fabric:type", "fabric:components")
                addProperty("base", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.base).toString())
                add("components", JsonObject().apply {
                    add("minecraft:custom_data", JsonObject().apply {
                        add(CustomItem.TAG, JsonObject().apply { addProperty("item", item.id) })
                    })
                })
            }
        }
        return JsonPrimitive(s)
    }

    private fun result(ops: RegistryOps<JsonElement>, r: Result): JsonElement {
        val stack = CustomItems[r.id]?.create(r.count)
            ?: return JsonObject().apply {
                addProperty("id", r.id)
                if (r.count != 1) addProperty("count", r.count)
            }
        val json = ItemStack.CODEC.encodeStart(ops, stack).getOrThrow { IllegalStateException("cannot encode ${r.id}: $it") }.asJsonObject
        if (r.extraComponents.isNotEmpty()) {
            val comps = json.getAsJsonObject("components") ?: JsonObject().also { json.add("components", it) }
            val gson = com.google.gson.Gson()
            r.extraComponents.forEach { (k, v) -> comps.add(k, gson.toJsonTree(v)) }
        }
        return json
    }

    /** Called by the recipe manager mixin whenever recipes are (re)loaded. */
    @JvmStatic
    fun build(registries: HolderLookup.Provider): List<RecipeHolder<*>> {
        val ops = RegistryOps.create(JsonOps.INSTANCE, registries)
        val out = mutableListOf<RecipeHolder<*>>()
        for ((id, def) in defs) {
            try {
                val json = def(ops)
                val recipe = Recipe.DIRECT_CODEC.parse(ops, json).getOrThrow { IllegalStateException(it) }
                val key = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("rane", id))
                out += RecipeHolder(key, recipe)
            } catch (e: Exception) {
                log.error("Recipe rane:{} is broken and was skipped", id, e)
            }
        }
        return out
    }
}
