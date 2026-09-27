package com.rrgmc.classapptriage.triage

import com.rrgmc.classapptriage.api.Message
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

// Rule-based triage as specified in docs/TRIAGE.md. The JSON shape of Config
// is the portable rules format described there; keep this file and the
// document in sync.

enum class Category { IMPORTANT, ROUTINE }

/**
 * Matches messages by label, sender and summary text. Every non-empty field
 * must match (AND); within a field any value may match (OR). A rule with no
 * fields set never matches. Text comparisons are case- and accent-insensitive.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Rule(
    @EncodeDefault val name: String = "",
    /** Matches the message label title exactly. */
    val labels: List<String> = emptyList(),
    /** Matches the message label ID. */
    val labelIds: List<Long> = emptyList(),
    /** Matches if the sender (entity) name contains any value. */
    val senders: List<String> = emptyList(),
    /** Matches the sender entity ID. */
    val senderIds: List<Long> = emptyList(),
    /** Matches if the summary starts with any value. */
    val summaryPrefixes: List<String> = emptyList(),
    /** Matches if the summary contains any value. */
    val summaryContains: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = labels.isEmpty() && labelIds.isEmpty() && senders.isEmpty() &&
            senderIds.isEmpty() && summaryPrefixes.isEmpty() && summaryContains.isEmpty()

    fun matches(msg: Message): Boolean {
        if (isEmpty) return false
        val summary = normalize(msg.summary)
        val labelTitle = normalize(msg.label?.title.orEmpty())
        val sender = normalize(msg.entity?.fullname.orEmpty())
        if (labels.isNotEmpty() && labels.none { normalize(it) == labelTitle }) return false
        if (labelIds.isNotEmpty() && (msg.label?.id ?: 0L) !in labelIds) return false
        if (senders.isNotEmpty() && senders.none { sender.contains(normalize(it)) }) return false
        if (senderIds.isNotEmpty() && (msg.entity?.id ?: 0L) !in senderIds) return false
        if (summaryPrefixes.isNotEmpty() && summaryPrefixes.none { summary.startsWith(normalize(it)) }) return false
        if (summaryContains.isNotEmpty() && summaryContains.none { summary.contains(normalize(it)) }) return false
        return true
    }
}

/** The classification of a single message; [rule] is null when defaulted. */
data class Result(val category: Category, val rule: String? = null)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Config(
    /** Important rules win over Routine rules. */
    @EncodeDefault val important: List<Rule> = emptyList(),
    /** Routine rules mark a message as Routine. */
    @EncodeDefault val routine: List<Rule> = emptyList(),
) {
    /** Unmatched messages are Important, so nothing unknown is hidden. */
    fun classify(msg: Message): Result {
        important.firstOrNull { it.matches(msg) }?.let { return Result(Category.IMPORTANT, it.name) }
        routine.firstOrNull { it.matches(msg) }?.let { return Result(Category.ROUTINE, it.name) }
        return Result(Category.IMPORTANT)
    }

    fun toJson(): String = encoder.encodeToString(serializer(), this)

    companion object {
        /** The default rules listed in docs/TRIAGE.md. */
        fun default() = Config(
            important = listOf(
                Rule(name = "marked urgent", summaryContains = listOf("urgente", "importante", "atenção")),
            ),
            routine = listOf(
                Rule(name = "meals", labels = listOf("Alimentação")),
                Rule(name = "menu", summaryContains = listOf("cardápio")),
                Rule(name = "daily routine", summaryPrefixes = listOf("Rotina", "Relatório")),
            ),
        )

        /** Parses a JSON config. Unknown fields are rejected. */
        fun fromJson(text: String): Config = decoder.decodeFromString(serializer(), text)

        private val encoder = Json { prettyPrint = true; encodeDefaults = false }
        private val decoder = Json { ignoreUnknownKeys = false }
    }
}

private val accents = mapOf(
    'á' to 'a', 'à' to 'a', 'â' to 'a', 'ã' to 'a', 'ä' to 'a',
    'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
    'í' to 'i', 'ì' to 'i', 'î' to 'i', 'ï' to 'i',
    'ó' to 'o', 'ò' to 'o', 'ô' to 'o', 'õ' to 'o', 'ö' to 'o',
    'ú' to 'u', 'ù' to 'u', 'û' to 'u', 'ü' to 'u',
    'ç' to 'c', 'ñ' to 'n',
)

/** Lowercases [s], strips Portuguese accents and trims spaces. */
fun normalize(s: String): String {
    val lower = s.trim().lowercase(Locale.ROOT)
    return buildString(lower.length) { lower.forEach { append(accents[it] ?: it) } }
}
