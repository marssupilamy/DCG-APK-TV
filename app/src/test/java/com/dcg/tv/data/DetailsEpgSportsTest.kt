package com.dcg.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DetailsEpgSportsTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun leFichaDoFilme() {
        val body = """{"info":{"name":"Matrix","plot":"Um hacker descobre a verdade.","genre":"Ação, Ficção","releasedate":"1999-03-31",
            "duration":"02:16:00","rating":"8.7","cast":"Keanu Reeves","director":"Wachowski","movie_image":"http://img/m.jpg",
            "backdrop_path":["http://img/b1.jpg","http://img/b2.jpg"]},"movie_data":{"stream_id":1}}"""
        val d = Details.read(body.reader())
        assertEquals("Um hacker descobre a verdade.", d.plot)
        assertEquals("1999", d.year)
        assertEquals("2h 16min", d.duration)
        assertEquals("8,7", d.rating)
        assertEquals("http://img/b1.jpg", d.backdrop)
        assertEquals("1999  ·  2h 16min  ·  Nota 8,7  ·  Ação, Ficção", d.summary)
    }

    @Test
    fun fichaVazia() {
        val d = Details.read("""{"info":[],"episodes":{}}""".reader())
        assertNull(d.plot)
        assertEquals("", d.summary)
    }

    @Test
    fun leProgramacaoEmBase64() {
        val body = """{"epg_listings":[
            {"id":"2","title":"${b64("Flamengo x Palmeiras")}","description":"${b64("Brasileirão Série A")}","start_timestamp":"2000","stop_timestamp":"9000"},
            {"id":"1","title":"Jornal","description":"","start_timestamp":"1000","stop_timestamp":"2000"}
        ]}"""
        val list = Epg.readListings(body.reader())
        assertEquals(listOf("Jornal", "Flamengo x Palmeiras"), list.map { it.title })
        assertEquals(2_000_000L, list[1].start)
        val (now, next) = Epg.nowNext(list, 1_500_000L)
        assertEquals("Jornal", now?.title)
        assertEquals("Flamengo x Palmeiras", next?.title)
    }

    @Test
    fun textoPuroNaoViraLixo() {
        assertEquals("Show", Epg.decode("Show"))
        assertEquals("Futebol ao vivo", Epg.decode("Futebol ao vivo"))
    }

    private fun program(title: String, description: String = "") = Program(title, description, 0, 1)

    @Test
    fun classificaEsportes() {
        assertEquals(Sport.FOOTBALL, Sports.classify(program("Flamengo x Palmeiras")))
        assertEquals(Sport.FOOTBALL, Sports.classify(program("Libertadores: River vs Boca")))
        assertEquals(Sport.BASKETBALL, Sports.classify(program("NBA: Lakers x Celtics")))
        assertEquals(Sport.TENNIS, Sports.classify(program("ATP 500 de Viena - Quartas de final")))
        assertEquals(Sport.VOLLEYBALL, Sports.classify(program("Superliga Feminina: Minas x Praia")))
        assertEquals(Sport.MOTOR, Sports.classify(program("Fórmula 1: GP de Mônaco - Corrida")))
        assertEquals(Sport.FIGHT, Sports.classify(program("UFC 310: Card principal")))
        assertEquals(Sport.AMERICAN_FOOTBALL, Sports.classify(program("NFL: Chiefs x Eagles")))
        assertEquals(Sport.OTHER, Sports.classify(program("Futsal: Corinthians x Magnus")))
    }

    @Test
    fun ignoraProgramasQueNaoSaoJogos() {
        assertNull(Sports.classify(program("SportsCenter")))
        assertNull(Sports.classify(program("Seleção SporTV", "Debate sobre o Brasileirão")))
        assertNull(Sports.classify(program("Reprise: Flamengo x Palmeiras")))
        assertNull(Sports.classify(program("Melhores Momentos: Santos x Sport")))
    }

    @Test
    fun montaAgendaJuntandoCanais() {
        val espn = Entry("ESPN HD", "u1", null, "Esportes", Kind.LIVE, key = "live:1")
        val espnFhd = Entry("ESPN FHD", "u2", null, "Esportes", Kind.LIVE, key = "live:2")
        assertEquals(Sports.channelKey(espn), Sports.channelKey(espnFhd))
        assertTrue(Sports.isSportsChannel(espn))

        val now = 10_000L
        val jogo = Program("Real Madrid x Barcelona", "La Liga", 9_000, 20_000)
        val depois = Program("NBA: Bulls x Knicks", "", 30_000, 40_000)
        val acabou = Program("Santos x Sport", "", 1_000, 5_000)
        val agenda = Sports.buildAgenda(
            mapOf(espn to listOf(acabou, jogo, depois), espnFhd to listOf(jogo)),
            now,
        )
        assertEquals(listOf(Sport.FOOTBALL, Sport.BASKETBALL), agenda.keys.toList())
        val game = agenda.getValue(Sport.FOOTBALL).single()
        assertEquals(listOf("live:1", "live:2"), game.channels.map { it.key })
        assertTrue(game.program.isLive(now))
    }
}
