package com.rrgmc.classapptriage.notify

import com.rrgmc.classapptriage.api.Entity
import com.rrgmc.classapptriage.api.Label
import com.rrgmc.classapptriage.api.Message
import com.rrgmc.classapptriage.triage.Config
import org.junit.Assert.assertEquals
import org.junit.Test

class NewImportantTest {
    private val inbox = 10L

    private fun msg(id: Long, label: String, summary: String, senderId: Long = 2) = Message(
        id = id,
        summary = summary,
        label = Label(id = 1, title = label),
        entity = Entity(id = senderId, fullname = "Escola"),
    )

    @Test
    fun keepsOnlyNewImportantFromOthers() {
        val unread = listOf(
            msg(1, "", "Reunião de pais"), // important (unmatched)
            msg(2, "Alimentação", "Lanche da semana"), // routine
            msg(3, "Rotina", "Rotina - URGENTE: febre"), // important override
            msg(4, "", "Aviso enviado", senderId = inbox), // sent from this inbox
            msg(5, "", "Passeio"), // already seen
        )
        val got = newImportant(unread, Config.default(), inbox, seen = setOf(5))
        assertEquals(listOf(1L, 3L), got.map { it.id })
    }

    @Test
    fun emptyWhenAllSeen() {
        val unread = listOf(msg(1, "", "Reunião de pais"))
        assertEquals(emptyList<Message>(), newImportant(unread, Config.default(), inbox, seen = setOf(1)))
    }
}
