package com.dcg.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class XmltvTest {

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="x">
          <channel id="espn.br"><display-name>ESPN</display-name></channel>
          <programme start="20261017200000 +0000" stop="20261017220000 +0000" channel="espn.br">
            <title lang="pt">Flamengo x Palmeiras</title>
            <desc lang="pt">Brasileirão Série A</desc>
          </programme>
          <programme start="20261017220000 +0000" stop="20261017230000 +0000" channel="espn.br">
            <title>SportsCenter</title>
          </programme>
          <programme start="20261010200000 +0000" stop="20261010220000 +0000" channel="espn.br">
            <title>Santos x Sport</title>
          </programme>
          <programme start="20261017200000 +0000" stop="20261017220000 +0000" channel="outro.br">
            <title>Real x Barça</title>
          </programme>
        </tv>"""

    private fun read(keep: (String, Program) -> Boolean): Map<String, List<Program>> {
        val parser = KXmlParser()
        parser.setInput(StringReader(xml))
        val from = Xmltv.parseTime("20261017000000 +0000")!!
        val to = Xmltv.parseTime("20261019000000 +0000")!!
        return Xmltv.read(parser, from, to, keep)
    }

    @Test
    fun leHorarioComFuso() {
        assertEquals(Xmltv.parseTime("20261017200000 +0000")!! + 3 * 3_600_000L, Xmltv.parseTime("20261017200000 -0300"))
        assertNull(Xmltv.parseTime("2026"))
    }

    @Test
    fun guardaSoJogosDosCanaisPedidos() {
        val result = read { channel, program -> channel == "espn.br" && Sports.classify(program) != null }
        assertEquals(setOf("espn.br"), result.keys)
        val games = result.getValue("espn.br")
        assertEquals(listOf("Flamengo x Palmeiras"), games.map { it.title })
        assertEquals("Brasileirão Série A", games.single().description)
    }

    @Test
    fun ignoraForaDoPeriodo() {
        val result = read { _, _ -> true }
        assertEquals(2, result.getValue("espn.br").size)
        assertEquals(1, result.getValue("outro.br").size)
    }
}
