package com.rrgmc.classapptriage.triage

import com.rrgmc.classapptriage.api.Entity
import com.rrgmc.classapptriage.api.Label
import com.rrgmc.classapptriage.api.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Cases follow the semantics in docs/TRIAGE.md.
class TriageTest {
    private fun msg(label: String, sender: String, summary: String) = Message(
        summary = summary,
        label = Label(id = 1, title = label),
        entity = Entity(id = 2, fullname = sender),
    )

    @Test
    fun defaultConfig() {
        val cfg = Config.default()
        val cases = listOf(
            Triple("meals label", msg("Alimentação", "Cantina", "Lanche da semana"), Result(Category.ROUTINE, "meals")),
            Triple("meals label without accent", msg("ALIMENTACAO", "Cantina", "Lanche"), Result(Category.ROUTINE, "meals")),
            Triple("menu by summary", msg("", "Secretaria", "Cardápio de outubro"), Result(Category.ROUTINE, "menu")),
            Triple("daily routine", msg("Rotina", "Professora Ana", "Rotina do dia 12/09"), Result(Category.ROUTINE, "daily routine")),
            Triple("daily report", msg("Rotina", "Professora Ana", "Relatório diário"), Result(Category.ROUTINE, "daily routine")),
            Triple("routine without label", msg("", "Maria", "Rotina Infantil IV"), Result(Category.ROUTINE, "daily routine")),
            Triple("report under other label", msg("Curso de Férias", "Isabelly", "Relatório"), Result(Category.ROUTINE, "daily routine")),
            Triple("routine label other summary", msg("Rotina", "Professora Ana", "Passeio ao zoológico"), Result(Category.IMPORTANT)),
            Triple("urgent overrides routine", msg("Rotina", "Professora Ana", "Rotina - URGENTE: febre"), Result(Category.IMPORTANT, "marked urgent")),
            Triple("unlabeled", msg("", "Direção", "Reunião de pais"), Result(Category.IMPORTANT)),
        )
        for ((name, m, want) in cases) {
            assertEquals(name, want, cfg.classify(m))
        }
    }

    @Test
    fun ruleAndSemantics() {
        var r = Rule(name = "x", labelIds = listOf(1), senderIds = listOf(3))
        assertFalse("matched with only one of two fields", r.matches(msg("L", "S", "s")))
        r = r.copy(senderIds = listOf(2))
        assertTrue("did not match with all fields", r.matches(msg("L", "S", "s")))
        assertFalse("empty rule matched", Rule(name = "empty").matches(msg("L", "S", "s")))
    }

    @Test
    fun missingLabelAndSender() {
        val m = Message(summary = "Cardápio")
        assertEquals(Result(Category.ROUTINE, "menu"), Config.default().classify(m))
        assertFalse(Rule(name = "l", labels = listOf("Rotina")).matches(m))
    }

    @Test
    fun loadConfig() {
        val cfg = Config.fromJson("""{ "routine": [{"name": "cantina", "senders": ["cantina"]}] }""")
        assertEquals(Category.ROUTINE, cfg.classify(msg("", "Cantina Escolar", "Hoje tem bolo")).category)
        assertThrows(Exception::class.java) { Config.fromJson("""{"routin": []}""") }
    }

    @Test
    fun jsonRoundTripMatchesSpecShape() {
        val json = Config.default().toJson()
        assertTrue(json.contains("\"summaryContains\""))
        assertFalse("empty lists must be omitted (docs/TRIAGE.md)", json.contains("\"labelIds\""))
        assertEquals(Config.default(), Config.fromJson(json))
        // The default rules as written in docs/TRIAGE.md must import unchanged.
        val specJson = """
            {
              "important": [
                {"name": "marked urgent", "summaryContains": ["urgente", "importante", "atenção"]}
              ],
              "routine": [
                {"name": "meals", "labels": ["Alimentação"]},
                {"name": "menu", "summaryContains": ["cardápio"]},
                {"name": "daily routine", "summaryPrefixes": ["Rotina", "Relatório"]}
              ]
            }
        """.trimIndent()
        assertEquals(Config.default(), Config.fromJson(specJson))
    }
}
