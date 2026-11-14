package app.pureiptv.core.parsing.xtream

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Xtream panels are inconsistent: the same field can be `123`, `"123"`, `""`, `null`, `[]` or `false`
 * depending on panel version and provider. These serializers accept all of that and never throw;
 * a garbage value becomes `null`.
 */
val XtreamJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

private fun JsonElement.primitiveContent(): String? =
    (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private abstract class LenientSerializer<T : Any>(name: String, kind: PrimitiveKind) : KSerializer<T?> {
    override val descriptor = PrimitiveSerialDescriptor("Lenient$name", kind)
    abstract fun convert(element: JsonElement): T?
    override fun deserialize(decoder: Decoder): T? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return runCatching { convert(element) }.getOrNull()
    }
    override fun serialize(encoder: Encoder, value: T?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value.toString())
    }
}

object LenientLong : KSerializer<Long?> by object : LenientSerializer<Long>("Long", PrimitiveKind.LONG) {
    override fun convert(element: JsonElement): Long? {
        val s = element.primitiveContent() ?: return null
        return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong()
    }
}

object LenientInt : KSerializer<Int?> by object : LenientSerializer<Int>("Int", PrimitiveKind.INT) {
    override fun convert(element: JsonElement): Int? {
        val s = element.primitiveContent() ?: return null
        return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt()
    }
}

object LenientDouble : KSerializer<Double?> by object : LenientSerializer<Double>("Double", PrimitiveKind.DOUBLE) {
    override fun convert(element: JsonElement): Double? =
        element.primitiveContent()?.replace(',', '.')?.toDoubleOrNull()
}

object LenientString : KSerializer<String?> by object : LenientSerializer<String>("String", PrimitiveKind.STRING) {
    override fun convert(element: JsonElement): String? = element.primitiveContent()
}

/** `1`, `"1"`, `true`, `"true"` are true; everything else (including missing) is false. */
object LenientBool : KSerializer<Boolean?> by object : LenientSerializer<Boolean>("Bool", PrimitiveKind.BOOLEAN) {
    override fun convert(element: JsonElement): Boolean {
        val s = element.primitiveContent()?.lowercase() ?: return false
        return s == "1" || s == "true" || s == "yes"
    }
}

/** A string list that may arrive as `["a","b"]`, `"a"`, `""` or `null`. */
object LenientStringList : KSerializer<List<String>> {
    override val descriptor = PrimitiveSerialDescriptor("LenientStringList", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): List<String> =
        when (val e = (decoder as JsonDecoder).decodeJsonElement()) {
            is JsonArray -> e.mapNotNull { it.primitiveContent() }
            is JsonPrimitive -> listOfNotNull(e.primitiveContent())
            else -> emptyList()
        }
    override fun serialize(encoder: Encoder, value: List<String>) = encoder.encodeString(value.joinToString(","))
}

/** Panels send `[]` instead of `{}` for empty objects. Treat any non-object as an empty object. */
internal fun JsonElement?.asObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
