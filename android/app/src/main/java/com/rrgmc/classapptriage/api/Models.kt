package com.rrgmc.classapptriage.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

// Typed models for the data models in docs/API.md §7. GraphQL connection wrappers
// ({ nodes { ... } }) are flattened by the client methods. Timestamps are kept
// as the ISO-8601 strings the server returns.

@Serializable
data class Picture(val id: Long = 0, val uri: String? = null, val key: String? = null)

@Serializable
data class Organization(val id: Long = 0, val fullname: String = "")

@Serializable
data class Entity(
    val id: Long = 0,
    val fullname: String = "",
    val disabled: Boolean = false,
    val picture: Picture? = null,
)

@Serializable
data class User(val id: Long = 0, val fullname: String = "")

@Serializable
data class ToEntity(val id: Long = 0, val fullname: String = "", val status: Int = 0)

@Serializable
data class Label(val id: Long = 0, val title: String = "", val color: String? = null)

@Serializable
data class ViewerEntity(
    val id: Long = 0,
    val fullname: String = "",
    val type: String? = null,
    val disabled: Boolean = false,
    val organization: Organization? = null,
)

data class Viewer(
    val id: Long,
    val fullname: String,
    val email: String?,
    val phone: String?,
    val entities: List<ViewerEntity>,
)

@Serializable
data class Message(
    val id: Long = 0,
    val summary: String = "",
    val statusText: String? = null,
    val status: Int = 0,
    val type: String? = null,
    val created: String? = null,
    val sentAt: String? = null,
    val pin: Boolean = false,
    val public: Boolean = false,
    val recipientsCount: Int = 0,
    val imagesCount: Int = 0,
    val videosCount: Int = 0,
    val audiosCount: Int = 0,
    val filesCount: Int = 0,
    // Undocumented fields (see QUERY_ENTITY_MESSAGES_RICH); absent when the
    // client fell back to the documented query.
    @Serializable(with = LenientBooleanSerializer::class)
    val unread: Boolean? = null,
    val surveysCount: Int = 0,
    val chargesCount: Int = 0,
    val reportsCount: Int = 0,
    val formsCount: Int = 0,
    val commitmentsCount: Int = 0,
    val entity: Entity? = null,
    val user: User? = null,
    val toEntity: ToEntity? = null,
    val label: Label? = null,
)

@Serializable
data class PageInfo(val hasPreviousPage: Boolean = false, val hasNextPage: Boolean = false)

/** A file, image, video or audio attached to a message. */
@Serializable
data class Media(
    val id: Long = 0,
    /** FILE, IMAGE, VIDEO or AUDIO. */
    val type: String? = null,
    /** The full-size file; what tapping an attachment opens. */
    val uri: String? = null,
    /** `uri(size: "w1280")`: the 1280px-wide rendition the web app displays (images only). */
    val original: String? = null,
    val filename: String? = null,
    val key: String? = null,
    val size: Long = 0,
    val thumbnail: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class Tag(val id: Long = 0, val name: String = "")

/** One answered field of a [Report]. [value] is a string, a JSON array (or its string encoding) or null. */
@Serializable
data class ReportResult(
    val reportFieldId: Long = 0,
    val entityId: Long? = null,
    val name: String = "",
    /** TEXT, SELECT, CHECK, … */
    val type: String? = null,
    val value: JsonElement? = null,
) {
    /** [value] as display text: arrays are joined with ", ". */
    val displayValue: String get() = display(value)

    private fun display(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonArray -> e.map { display(it) }.filter { it.isNotEmpty() }.joinToString(", ")
        is JsonPrimitive -> {
            val s = e.content
            if (e.isString && s.trimStart().startsWith("[")) {
                runCatching { Json.parseToJsonElement(s) }.getOrNull()?.let { display(it) } ?: s
            } else s
        }
        else -> e.toString()
    }
}

/** A report (field/value card) attached to a message; see docs/API.md §5.4. */
data class Report(val id: Long, val name: String, val results: List<ReportResult>)

/** The full contents of a single message (MessageQuery, docs/API.md 5.4). */
data class MessageDetail(
    val id: Long,
    val subject: String?,
    val content: String?,
    /** Server-rendered HTML body, what the official app shows; may be set when [content] is empty. */
    val rendered: String? = null,
    val summary: String,
    val statusText: String?,
    val recipientsCount: Int,
    val created: String?,
    val sentAt: String?,
    val entity: Entity?,
    val user: User?,
    /** One recipient only: for multi-recipient messages it is not necessarily the viewer's entity. */
    val toEntity: ToEntity?,
    val label: Label?,
    val tags: List<Tag>,
    val medias: List<Media>,
    val reports: List<Report> = emptyList(),
)

/**
 * Decodes a flag the server may send as a boolean, a number (0/1, or a count)
 * or a string: true/nonzero means true. Anything else decodes as null.
 */
object LenientBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor = PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        val e = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeBoolean()
        if (e !is JsonPrimitive || e is JsonNull) return null
        return e.booleanOrNull ?: e.doubleOrNull?.let { it != 0.0 } ?: e.content.lowercase().let {
            when (it) { "true", "yes" -> true; "false", "no", "" -> false; else -> null }
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) encoder.encodeNull() else encoder.encodeBoolean(value)
    }
}

/** A single page of a message listing. */
data class MessagesPage(val messages: List<Message>, val pageInfo: PageInfo)

/** Per-user status applied with [ClassAppClient.setMessagesStatus]. */
enum class MessageStatus { READ, UNREAD, DELETED }
