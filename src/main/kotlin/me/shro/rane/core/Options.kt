package me.shro.rane.core

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceCondition
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditionType
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditions
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps

/**
 * Switches that rane's built-in data files can depend on, through the `rane:option` load
 * condition: `"fabric:load_conditions": [{"condition": "rane:option", "key": "admin.chat_format"}]`.
 */
object Options {
    private val values = mutableMapOf<String, Boolean>()

    fun set(key: String, on: Boolean) {
        values[key] = on
    }

    fun isOn(key: String) = values[key] ?: false

    private data class OptionCondition(val key: String) : ResourceCondition {
        override fun getType(): ResourceConditionType<*> = TYPE

        override fun test(registryInfo: RegistryOps.RegistryInfoLookup?): Boolean = isOn(key)
    }

    private val CODEC: MapCodec<OptionCondition> = RecordCodecBuilder.mapCodec { i ->
        i.group(Codec.STRING.fieldOf("key").forGetter(OptionCondition::key)).apply(i, ::OptionCondition)
    }

    private val TYPE: ResourceConditionType<OptionCondition> =
        ResourceConditionType.create(Identifier.fromNamespaceAndPath("rane", "option"), CODEC)

    init {
        ResourceConditions.register(TYPE)
    }

    fun init() {}
}
