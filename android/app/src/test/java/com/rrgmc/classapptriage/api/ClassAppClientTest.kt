package com.rrgmc.classapptriage.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ClassAppClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        ClassAppClient.richMessages = true
    }
    @After fun tearDown() = server.shutdown()

    private fun client(token: String? = "tok") = ClassAppClient(token = token, endpoint = server.url("/graphql").toString())

    private fun enqueue(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun takeBody() = server.takeRequest().let { req ->
        Triple(req, Json.parseToJsonElement(req.body.readUtf8()).jsonObject, req.requestUrl!!)
    }

    @Test
    fun sendsQueryParamsAndBearer() = runTest {
        enqueue("""{"data":{"viewer":{"id":1,"fullname":"Ana","entities":{"nodes":[{"id":10,"fullname":"Kid","organization":{"id":5,"fullname":"School"}}]}}}}""")
        val v = client().viewer()
        assertEquals("Ana", v.fullname)
        assertEquals(10L, v.entities.single().id)
        val (req, body, url) = takeBody()
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals(ClassAppClient.DEFAULT_CLIENT_ID, url.queryParameter("client_id"))
        assertEquals("-180", url.queryParameter("tz_offset"))
        assertEquals("pt", url.queryParameter("locale"))
        assertEquals("ViewerQuery", body["operationName"]!!.jsonPrimitive.content)
    }

    @Test
    fun graphQLErrorsDecodedBeforeStatus() = runTest {
        enqueue("""{"errors":[{"message":"Invalid credentials","name":"GraphQLError"}]}""", code = 400)
        try {
            client().viewer()
            fail("expected error")
        } catch (e: GraphQLException) {
            assertFalse(e is UnauthorizedException)
            assertEquals("Invalid credentials", e.message)
            assertEquals(400, e.httpStatus)
        }
    }

    @Test
    fun unauthorizedWithToken() = runTest {
        enqueue("""{"errors":[{"message":"Not authorized"}]}""", code = 401)
        try {
            client().viewer()
            fail("expected error")
        } catch (e: UnauthorizedException) {
            assertEquals(401, e.httpStatus)
        }
    }

    @Test
    fun loginFailureIsNotUnauthorized() = runTest {
        enqueue("""{"errors":[{"message":"Invalid credentials"}]}""", code = 401)
        try {
            client(token = null).loginWithPassword(Contact.parse("a@b.c"), "x")
            fail("expected error")
        } catch (e: GraphQLException) {
            assertFalse(e is UnauthorizedException)
        }
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun nonJsonErrorStatus() = runTest {
        enqueue("<html>bad gateway</html>", code = 502)
        try {
            client().viewer()
            fail("expected error")
        } catch (e: HttpStatusException) {
            assertEquals(502, e.httpStatus)
        }
    }

    @Test
    fun passwordLoginWithOtp() = runTest {
        enqueue("""{"data":{"passwordAuthenticate":{"requiresOtp":true,"user":{"id":7,"oauthProvider":{"accessToken":"not-yet","refreshToken":"r"}}}}}""")
        val res = client(token = null).loginWithPassword(Contact.parse("11999990000"), "pw")
        assertTrue(res.requiresOtp)
        assertNull(res.token)
        val input = takeBody().second["variables"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("11999990000", input["phone"]!!.jsonPrimitive.content)
        assertEquals("pw", input["password"]!!.jsonPrimitive.content)
        assertNull(input["email"])

        enqueue("""{"data":{"codeAuthenticate":{"user":{"id":7,"oauthProvider":{"accessToken":"AT","refreshToken":"RT"}}}}}""")
        val done = client(token = null).loginWithCode(Contact.parse("11999990000"), "123456")
        assertEquals("AT", done.token)
        assertEquals("RT", done.refreshToken)
        val codeInput = takeBody().second["variables"]!!.jsonObject["input"]!!.jsonObject
        assertEquals("11999990000", codeInput["address"]!!.jsonPrimitive.content)
    }

    @Test
    fun recentMessagesPaginates() = runTest {
        fun page(from: Int, n: Int, more: Boolean) = """{"data":{"node":{"id":1,"messages":{"nodes":[${
            (from until from + n).joinToString(",") { """{"id":$it,"summary":"m$it","label":null}""" }
        }],"pageInfo":{"hasPreviousPage":false,"hasNextPage":$more}}}}}"""
        enqueue(page(0, 2, true))
        enqueue(page(2, 2, true))
        enqueue(page(4, 1, false))
        val res = client().recentMessages(entityId = 99, max = 10, pageSize = 2)
        assertEquals(listOf(0L, 1, 2, 3, 4), res.messages.map { it.id })
        assertFalse(res.pageInfo.hasNextPage)
        val offsets = (0 until 3).map { takeBody().second["variables"]!!.jsonObject["offset"]!!.jsonPrimitive.long }
        assertEquals(listOf(0L, 2, 4), offsets)
    }

    @Test
    fun recentMessagesStopsAtMax() = runTest {
        enqueue("""{"data":{"node":{"messages":{"nodes":[{"id":1},{"id":2}],"pageInfo":{"hasNextPage":true}}}}}""")
        val res = client().recentMessages(entityId = 99, max = 2, pageSize = 50)
        assertEquals(2, res.messages.size)
        assertTrue(res.pageInfo.hasNextPage)
        assertEquals(2L, takeBody().second["variables"]!!.jsonObject["limit"]!!.jsonPrimitive.long)
    }

    @Test
    fun setStatusChunks() = runTest {
        repeat(2) { enqueue("""{"data":{"createMessageStatusInBatch":{"clientMutationId":null}}}""") }
        client().setMessagesStatus(5, (1L..3L).toList(), MessageStatus.DELETED, chunkSize = 2)
        val first = takeBody().second["variables"]!!.jsonObject["input"]!!.jsonObject
        assertEquals(5L, first["entityId"]!!.jsonPrimitive.long)
        assertEquals("DELETED", first["status"]!!.jsonPrimitive.content)
        assertEquals(listOf(1L, 2L), first["messagesId"]!!.jsonArray.map { it.jsonPrimitive.long })
        val second = takeBody().second["variables"]!!.jsonObject["input"]!!.jsonObject
        assertEquals(listOf(3L), second["messagesId"]!!.jsonArray.map { it.jsonPrimitive.long })
    }

    @Test
    fun readAndUnreadUseUpdateRecipient() = runTest {
        repeat(2) { enqueue("""{"data":{"updateRecipientInBatch":{"clientMutationId":null}}}""") }
        client().setMessagesStatus(5, listOf(1L), MessageStatus.READ)
        client().setMessagesStatus(5, listOf(2L), MessageStatus.UNREAD)
        for (expected in listOf("READ", "AS_UNREAD")) {
            val body = takeBody().second
            assertEquals("updateRecipientInBatch", body["operationName"]!!.jsonPrimitive.content)
            val input = body["variables"]!!.jsonObject["input"]!!.jsonObject
            assertEquals(expected, input["status"]!!.jsonPrimitive.content)
            assertEquals(5L, input["entityId"]!!.jsonPrimitive.long)
            assertEquals("true", input["deleteNotification"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun sendCodeSendsOnlyOneContact() = runTest {
        enqueue("""{"data":{"sendCode":{"__typename":"SendCodePayload"}}}""")
        client(token = null).sendCode(Contact.parse("a@b.c"))
        val (req, body, _) = takeBody()
        assertNull(req.getHeader("Authorization"))
        val vars = body["variables"]!!.jsonObject
        assertEquals("a@b.c", vars["email"]!!.jsonPrimitive.content)
        assertFalse("phone must be omitted", "phone" in vars)
    }

    @Test
    fun richFields() = runTest {
        // Shapes as sent by the live server: unread is 0/1/null.
        enqueue("""{"data":{"node":{"messages":{"nodes":[
            {"id":1,"statusText":"READ","unread":null,"surveysCount":1},
            {"id":2,"statusText":"RECEIVED","unread":0,"chargesCount":2,"label":{"id":25501,"title":"Boleto","color":"f03e3e"}},
            {"id":3,"unread":1}
        ],"pageInfo":{"hasNextPage":false}}}}}""")
        val msgs = client().messagesPage(5).messages
        assertEquals(listOf(null, false, true), msgs.map { it.unread })
        assertEquals(1, msgs[0].surveysCount)
        assertEquals(2, msgs[1].chargesCount)
        assertTrue(takeBody().second["query"]!!.jsonPrimitive.content.contains("surveysCount"))
    }

    @Test
    fun unreadIdsUseUnreadFolder() = runTest {
        enqueue("""{"data":{"node":{"messages":{"nodes":[{"id":7},{"id":9}],"pageInfo":{"hasNextPage":false}}}}}""")
        assertEquals(setOf(7L, 9L), client().unreadMessageIds(5))
        val vars = takeBody().second["variables"]!!.jsonObject
        assertEquals("UNREAD_BY_NTF", vars["folder"]!!.jsonPrimitive.content)
    }

    @Test
    fun fallsBackWhenRichFieldsRejected() = runTest {
        enqueue("""{"errors":[{"message":"Invalid request"}]}""", code = 400)
        enqueue("""{"data":{"node":{"messages":{"nodes":[{"id":1,"statusText":"READ"}],"pageInfo":{"hasNextPage":false}}}}}""")
        enqueue("""{"data":{"node":{"messages":{"nodes":[],"pageInfo":{"hasNextPage":false}}}}}""")
        val c = client()
        assertEquals("READ", c.messagesPage(5).messages.single().statusText)
        assertTrue(takeBody().second["query"]!!.jsonPrimitive.content.contains("surveysCount"))
        assertFalse(takeBody().second["query"]!!.jsonPrimitive.content.contains("surveysCount"))
        c.messagesPage(5) // remembered: goes straight to the documented query
        assertFalse(takeBody().second["query"]!!.jsonPrimitive.content.contains("surveysCount"))
    }

    @Test
    fun fallsBackWhenRichFieldsFailToDecode() = runTest {
        enqueue("""{"data":{"node":{"messages":{"nodes":[{"id":1,"surveysCount":{"weird":true}}],"pageInfo":{}}}}}""")
        enqueue("""{"data":{"node":{"messages":{"nodes":[{"id":1,"statusText":"READ"}],"pageInfo":{"hasNextPage":false}}}}}""")
        assertEquals("READ", client().messagesPage(5).messages.single().statusText)
        assertFalse(ClassAppClient.richMessages)
    }

    @Test
    fun networkErrorDoesNotDisableRichFields() = runTest {
        enqueue("<html>bad gateway</html>", code = 502)
        try {
            client().messagesPage(5)
            fail("expected error")
        } catch (e: HttpStatusException) {
            assertTrue(ClassAppClient.richMessages)
        }
    }

    @Test
    fun messageDetail() = runTest {
        enqueue("""{"data":{"node":{"id":42,"subject":"Passeio","content":"<p>Oi</p>","summary":"Passeio",
            "entity":{"id":7,"fullname":"Escola"},"tags":{"nodes":[{"id":1,"name":"4B"}]},
            "medias":{"nodes":[{"id":9,"type":"IMAGE","uri":"https://x/y.jpg","filename":"y.jpg","size":1234}]},"links":[]}}}""")
        val m = client().message(42)
        assertEquals("Passeio", m.subject)
        assertEquals("Escola", m.entity?.fullname)
        assertEquals("4B", m.tags.single().name)
        assertEquals("IMAGE", m.medias.single().type)
        val body = takeBody().second
        assertEquals("MessageQuery", body["operationName"]!!.jsonPrimitive.content)
        assertEquals(42L, body["variables"]!!.jsonObject["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun entityMessageWithRenderedAndReports() = runTest {
        enqueue("""{"data":{"node":{"id":5,"message":{"id":42,"subject":"Menu","content":"","rendered":"<p>Hi</p>",
            "reports":{"nodes":[{"id":3,"name":"Menu - Office","results":{"nodes":[
              {"reportFieldId":1,"entityId":5,"name":"Drink","type":"TEXT","value":"Juice"},
              {"reportFieldId":2,"entityId":6,"name":"Drink","type":"TEXT","value":"Other kid"},
              {"reportFieldId":3,"entityId":5,"name":"Sides","type":"CHECK","value":"[\"Rice\",\"Beans\"]"},
              {"reportFieldId":4,"entityId":5,"name":"Main","type":"SELECT","value":["Chicken"]}
            ]}}]}}}}}""")
        val m = client().message(42, entityId = 5)
        assertEquals("<p>Hi</p>", m.rendered)
        val r = m.reports.single()
        assertEquals("Menu - Office", r.name)
        assertEquals(listOf("Juice", "Rice, Beans", "Chicken"), r.results.map { it.displayValue })
        val body = takeBody().second
        assertEquals("EntityMessageQuery", body["operationName"]!!.jsonPrimitive.content)
        assertEquals(5L, body["variables"]!!.jsonObject["entityId"]!!.jsonPrimitive.long)
    }

    @Test
    fun entityMessageFallsBackToPlainQuery() = runTest {
        enqueue("""{"errors":[{"message":"Invalid request"}]}""", code = 400)
        enqueue("""{"data":{"node":{"id":42,"subject":"S","content":"c"}}}""")
        assertEquals("c", client().message(42, entityId = 5).content)
        assertEquals("EntityMessageQuery", takeBody().second["operationName"]!!.jsonPrimitive.content)
        assertEquals("MessageQuery", takeBody().second["operationName"]!!.jsonPrimitive.content)
    }

    @Test
    fun contactParse() {
        assertEquals(Contact(email = "a@b.c"), Contact.parse(" a@b.c "))
        assertEquals(Contact(phone = "+55 11 9999"), Contact.parse("+55 11 9999"))
    }
}
