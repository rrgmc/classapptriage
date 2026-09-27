package com.rrgmc.classapptriage.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Base class of every error raised by [ClassAppClient]. */
open class ClassAppException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A single entry of the GraphQL `errors` array. */
@Serializable
data class GraphQLError(val message: String = "", val name: String? = null)

/**
 * The server answered with a non-empty GraphQL `errors` array. ClassApp sends
 * these with a non-200 HTTP status, so [httpStatus] is informational only.
 */
open class GraphQLException(val errors: List<GraphQLError>, val httpStatus: Int) : ClassAppException(
    when (errors.size) {
        0 -> "graphql error"
        1 -> errors[0].message
        else -> "${errors[0].message} (and ${errors.size - 1} more)"
    },
)

/** The stored token was rejected (HTTP 401 on an authenticated request). */
class UnauthorizedException(errors: List<GraphQLError>, httpStatus: Int) : GraphQLException(errors, httpStatus)

/** The response `data` did not have the expected shape. */
class DecodeException(message: String, cause: Throwable) : ClassAppException(message, cause)

/** A non-2xx response that did not carry a GraphQL error envelope. */
class HttpStatusException(val httpStatus: Int) : ClassAppException("unexpected HTTP status $httpStatus")

/**
 * Client for the ClassApp GraphQL endpoint used by the web app, following the
 * protocol in docs/API.md.
 *
 * [token] is the bearer access token; it is null only for the login flow.
 */
class ClassAppClient(
    private val token: String? = null,
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val clientId: String = DEFAULT_CLIENT_ID,
    private val locale: String = DEFAULT_LOCALE,
    private val tzOffset: Int = DEFAULT_TZ_OFFSET,
) {
    /** Returns a client with the same configuration authenticated with [token]. */
    fun withToken(token: String) = ClassAppClient(token, httpClient, endpoint, clientId, locale, tzOffset)

    private val requestUrl = endpoint.toHttpUrl().newBuilder()
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("tz_offset", tzOffset.toString())
        .addQueryParameter("locale", locale)
        .build()

    /**
     * Runs a GraphQL operation and decodes its `data` with [deserializer]. The
     * `errors` envelope is decoded before the HTTP status is
     * checked, because ClassApp reports GraphQL errors with non-200 statuses.
     */
    internal suspend fun <T> execute(
        operation: String,
        query: String,
        variables: JsonObject,
        deserializer: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("operationName", operation)
            put("query", query)
            put("variables", variables)
        }.toString()
        val builder = Request.Builder()
            .url(requestUrl)
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Accept", "*/*")
            .header("User-Agent", USER_AGENT)
        if (token != null) builder.header("Authorization", "Bearer $token")

        val (status, text) = try {
            httpClient.newCall(builder.build()).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            throw ClassAppException("request failed: ${e.message}", e)
        }

        val envelope = try {
            json.decodeFromString(Envelope.serializer(), text)
        } catch (e: Exception) {
            null
        }
        if (envelope != null && envelope.errors.isNotEmpty()) {
            if (status == 401 && token != null) throw UnauthorizedException(envelope.errors, status)
            throw GraphQLException(envelope.errors, status)
        }
        if (status !in 200..299) {
            if (status == 401 && token != null) throw UnauthorizedException(emptyList(), status)
            throw HttpStatusException(status)
        }
        val data = envelope?.data ?: throw ClassAppException("response has no data")
        try {
            json.decodeFromJsonElement(deserializer, data)
        } catch (e: Exception) {
            throw DecodeException("decode response: ${e.message}", e)
        }
    }

    // --- Viewer -------------------------------------------------------------

    @Serializable private data class Nodes<T>(val nodes: List<T> = emptyList())

    @Serializable
    private data class ViewerData(val viewer: ViewerNode) {
        @Serializable
        data class ViewerNode(
            val id: Long = 0,
            val fullname: String = "",
            val email: String? = null,
            val phone: String? = null,
            val entities: Nodes<ViewerEntity> = Nodes(),
        )
    }

    /** Returns the logged-in user and the entities (inboxes) linked to it. */
    suspend fun viewer(): Viewer {
        val v = execute("ViewerQuery", QUERY_VIEWER, JsonObject(emptyMap()), ViewerData.serializer()).viewer
        return Viewer(v.id, v.fullname, v.email, v.phone, v.entities.nodes)
    }

    // --- Messages -----------------------------------------------------------

    @Serializable
    private data class MessagesData(val node: Node? = null) {
        @Serializable data class Node(val messages: Conn = Conn())
        @Serializable data class Conn(val nodes: List<Message> = emptyList(), val pageInfo: PageInfo = PageInfo())
    }

    /** Fetches one page of the inbox of [entityId]. */
    suspend fun messagesPage(entityId: Long, limit: Int = 25, offset: Int = 0, folder: String? = null): MessagesPage {
        val vars = buildJsonObject {
            put("entityId", entityId)
            put("limit", limit)
            put("offset", offset)
            putJsonArray("labelIds") {}
            put("search", "")
            if (folder != null) put("folder", folder)
        }
        val data = if (richMessages) {
            try {
                execute("EntityMessagesQuery", QUERY_ENTITY_MESSAGES_RICH, vars, MessagesData.serializer())
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: ClassAppException) {
                // Rejected (GraphQL error) or unexpectedly shaped (decode error)
                // undocumented fields: use the documented query from now on.
                // Other failures (network, HTTP) are not the fields' fault.
                if (e !is GraphQLException && e !is DecodeException) throw e
                richMessages = false
                execute("EntityMessagesQuery", QUERY_ENTITY_MESSAGES, vars, MessagesData.serializer())
            }
        } else {
            execute("EntityMessagesQuery", QUERY_ENTITY_MESSAGES, vars, MessagesData.serializer())
        }
        val conn = data.node?.messages ?: throw ClassAppException("entity $entityId not found")
        return MessagesPage(conn.nodes, conn.pageInfo)
    }

    /**
     * Fetches up to [max] of the most recent messages, following
     * `pageInfo.hasNextPage` with offset pagination.
     */
    suspend fun recentMessages(
        entityId: Long,
        max: Int,
        pageSize: Int = 50,
        offset: Int = 0,
        folder: String? = null,
    ): MessagesPage {
        val out = mutableListOf<Message>()
        var off = offset
        var info = PageInfo()
        while (out.size < max) {
            val page = messagesPage(entityId, minOf(pageSize, max - out.size), off, folder)
            out += page.messages
            off += page.messages.size
            info = page.pageInfo
            if (!info.hasNextPage || page.messages.isEmpty()) break
        }
        return MessagesPage(out, info)
    }

    // --- Message detail -----------------------------------------------------

    @Serializable
    private data class MessageData(val node: Node? = null) {
        @Serializable
        data class Node(
            val id: Long = 0,
            val subject: String? = null,
            val content: String? = null,
            val summary: String = "",
            val statusText: String? = null,
            val recipientsCount: Int = 0,
            val created: String? = null,
            val sentAt: String? = null,
            val entity: Entity? = null,
            val user: User? = null,
            val toEntity: ToEntity? = null,
            val label: Label? = null,
            val tags: Nodes<Tag> = Nodes(),
            val medias: Nodes<Media> = Nodes(),
        )
    }

    /** Fetches the full body and attachments of message [id]. */
    suspend fun message(id: Long): MessageDetail {
        val n = execute("MessageQuery", QUERY_MESSAGE, buildJsonObject { put("id", id) }, MessageData.serializer()).node
            ?: throw ClassAppException("message $id not found")
        return MessageDetail(
            n.id, n.subject, n.content, n.summary, n.statusText, n.recipientsCount, n.created, n.sentAt,
            n.entity, n.user, n.toEntity, n.label, n.tags.nodes, n.medias.nodes,
        )
    }

    // --- Labels -------------------------------------------------------------

    @Serializable
    private data class LabelsData(val node: Node? = null) {
        @Serializable data class Node(val organization: Org? = null)
        @Serializable data class Org(val labels: Nodes<Label> = Nodes())
    }

    /** Returns the labels configured by the organization of [entityId]. */
    suspend fun labels(entityId: Long, limit: Int = 100): List<Label> {
        val vars = buildJsonObject {
            put("entityId", entityId)
            put("limit", limit)
        }
        return execute("EntityLabelsQuery", QUERY_ENTITY_LABELS, vars, LabelsData.serializer())
            .node?.organization?.labels?.nodes.orEmpty()
    }

    // --- Status -------------------------------------------------------------

    /**
     * Applies [status] to [ids] in the inbox of [entityId] via
     * `createMessageStatusInBatch`, in chunks of [chunkSize]. No-op when empty.
     */
    suspend fun setMessagesStatus(entityId: Long, ids: List<Long>, status: MessageStatus, chunkSize: Int = 50) {
        for (chunk in ids.chunked(chunkSize)) {
            val vars = buildJsonObject {
                put("input", buildJsonObject {
                    put("entityId", entityId)
                    put("messagesId", buildJsonArray { chunk.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    put("status", status.name)
                })
            }
            execute("createMessageStatusInBatch", MUTATION_CREATE_MESSAGE_STATUS_IN_BATCH, vars, JsonElement.serializer())
        }
    }

    // --- Authentication (port of auth.go) -----------------------------------

    @Serializable
    private data class AuthUserResponse(
        val id: Long = 0,
        val language: String? = null,
        val isMaster: Boolean = false,
        val hasPassword: Boolean = false,
        val oauthProvider: OAuth? = null,
    ) {
        @Serializable data class OAuth(val accessToken: String? = null, val refreshToken: String? = null)

        fun result(requiresOtp: Boolean) = AuthResult(
            token = if (requiresOtp) null else oauthProvider?.accessToken,
            refreshToken = oauthProvider?.refreshToken,
            requiresOtp = requiresOtp,
            userId = id,
        )
    }

    @Serializable
    private data class PasswordData(val passwordAuthenticate: Payload) {
        @Serializable data class Payload(val requiresOtp: Boolean = false, val user: AuthUserResponse = AuthUserResponse())
    }

    @Serializable
    private data class CodeData(val codeAuthenticate: Payload) {
        @Serializable data class Payload(val user: AuthUserResponse = AuthUserResponse())
    }

    /**
     * Authenticates [contact] with [password]. When the result has
     * [AuthResult.requiresOtp], call [sendCode] and then [loginWithCode].
     */
    suspend fun loginWithPassword(contact: Contact, password: String): AuthResult {
        require(password.isNotEmpty()) { "password is required" }
        val input = buildJsonObject {
            put("password", password)
            contact.applyTo(this)
        }
        val data = execute(
            "passwordAuthenticate", MUTATION_PASSWORD_AUTHENTICATE,
            buildJsonObject { put("input", input) }, PasswordData.serializer(),
        ).passwordAuthenticate
        return data.user.result(data.requiresOtp).also { it.check() }
    }

    /** Requests a one-time login code for [contact] (by email or SMS). */
    suspend fun sendCode(contact: Contact) {
        val vars = buildJsonObject { contact.applyTo(this) }
        execute("sendCode", MUTATION_SEND_CODE, vars, JsonElement.serializer())
    }

    /** Completes authentication with the code received after [sendCode]. */
    suspend fun loginWithCode(contact: Contact, code: String): AuthResult {
        require(code.isNotEmpty()) { "code is required" }
        val input = buildJsonObject {
            put("code", code)
            put("address", contact.address)
        }
        return execute(
            "codeAuthenticate", MUTATION_CODE_AUTHENTICATE,
            buildJsonObject { put("input", input) }, CodeData.serializer(),
        ).codeAuthenticate.user.result(false).also { it.check() }
    }

    private fun AuthResult.check() {
        if (!requiresOtp && token.isNullOrEmpty()) throw ClassAppException("login returned no access token")
    }

    @Serializable
    private data class Envelope(val data: JsonElement? = null, val errors: List<GraphQLError> = emptyList())

    /**
     * Ids of the messages in the `UNREAD_BY_NTF` folder, i.e. unread by the
     * logged-in user (up to [max]). This is the per-user read state; the
     * message fields (`statusText`, `unread`) are per entity and not reliable.
     */
    suspend fun unreadMessageIds(entityId: Long, max: Int = 500): Set<Long> =
        recentMessages(entityId, max, folder = FOLDER_UNREAD).messages.mapTo(HashSet()) { it.id }

    companion object {
        const val FOLDER_UNREAD = "UNREAD_BY_NTF"
        const val DEFAULT_ENDPOINT = "https://web.classapp.com.br/graphql"
        const val DEFAULT_CLIENT_ID = "ZmYyYWM3M2JmYjkxY2IwZWJhNzlhZjcw"
        const val DEFAULT_LOCALE = "pt"
        /** Minutes; -180 == UTC-3 (America/Sao_Paulo), as sent by the web client. */
        const val DEFAULT_TZ_OFFSET = -180
        private const val USER_AGENT = "classapptriage-android/1.0"
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Whether the server accepts [QUERY_ENTITY_MESSAGES_RICH]; shared by all clients. */
        @Volatile
        internal var richMessages = true

        internal val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        }
    }
}

/** Identifies a user for the login flow. Exactly one of email/phone is set. */
data class Contact(val email: String? = null, val phone: String? = null) {
    init {
        require((email == null) != (phone == null)) { "exactly one of email or phone is required" }
    }

    /** The single contact value, used as the address bound to a one-time code. */
    val address: String get() = email ?: phone!!

    internal fun applyTo(b: kotlinx.serialization.json.JsonObjectBuilder) {
        if (email != null) b.put("email", email) else b.put("phone", phone)
    }

    companion object {
        /** Treats input containing '@' as an email, anything else as a phone. */
        fun parse(input: String): Contact {
            val s = input.trim()
            require(s.isNotEmpty()) { "email or phone is required" }
            return if ('@' in s) Contact(email = s) else Contact(phone = s)
        }
    }
}

/** Outcome of an authentication step. [token] is null while [requiresOtp]. */
data class AuthResult(
    val token: String?,
    val refreshToken: String?,
    val requiresOtp: Boolean,
    val userId: Long,
)
