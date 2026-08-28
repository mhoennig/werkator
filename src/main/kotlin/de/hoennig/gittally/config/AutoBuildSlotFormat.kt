package de.hoennig.gittally.config

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider

/**
 * Accepts both YAML forms of an [AutoBuildSlot] entry: a plain `HH:MM` string, or an
 * object with `time` and optional `buildCommand` and `name`.
 */
class AutoBuildSlotDeserializer : JsonDeserializer<AutoBuildSlot>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): AutoBuildSlot {
        val node = parser.readValueAsTree<JsonNode>()
        if (node.isTextual) {
            return AutoBuildSlot(time = node.asText())
        }
        if (node.isObject) {
            val time =
                node.get("time")?.takeIf { it.isTextual }?.asText()
                    ?: throw context.instantiationException(AutoBuildSlot::class.java, "auto-build slot object needs a 'time' (HH:MM)")
            return AutoBuildSlot(
                time = time,
                buildCommand = node.get("buildCommand")?.asText() ?: "",
                name = node.get("name")?.asText() ?: "",
            )
        }
        throw context.instantiationException(
            AutoBuildSlot::class.java,
            "auto-build slot must be an HH:MM string or an object with 'time' and optional 'buildCommand' and 'name'",
        )
    }
}

/** Writes the compact form back: a plain string unless the slot carries its own command or name. */
class AutoBuildSlotSerializer : JsonSerializer<AutoBuildSlot>() {
    override fun serialize(
        slot: AutoBuildSlot,
        generator: JsonGenerator,
        provider: SerializerProvider,
    ) {
        if (slot.buildCommand.isBlank() && slot.name.isBlank()) {
            generator.writeString(slot.time)
            return
        }
        generator.writeStartObject()
        generator.writeStringField("time", slot.time)
        if (slot.buildCommand.isNotBlank()) {
            generator.writeStringField("buildCommand", slot.buildCommand)
        }
        if (slot.name.isNotBlank()) {
            generator.writeStringField("name", slot.name)
        }
        generator.writeEndObject()
    }
}
