package com.dcg.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-id="globo" tvg-name="Globo SP" tvg-logo="http://img/globo.png" group-title="Abertos, HD",Globo SP
        http://srv:8080/user/pass/101
        #EXTINF:-1 tvg-name="SBT" group-title="Abertos",SBT
        http://srv:8080/live/user/pass/102.m3u8
        #EXTINF:-1 tvg-logo="http://img/matrix.jpg" group-title="Filmes | Ação",Matrix (1999)
        http://srv:8080/movie/user/pass/5001.mp4
        #EXTINF:-1 group-title="Lançamentos",Duna Parte 2
        http://cdn/filmes/duna2.mkv
        #EXTINF:-1 tvg-logo="http://img/bb.jpg" group-title="Séries | Drama",Breaking Bad S01E02
        http://srv:8080/series/user/pass/9002.mkv
        #EXTINF:-1 group-title="Séries | Drama",Breaking Bad S01E01
        http://srv:8080/series/user/pass/9001.mkv
        #EXTINF:-1 group-title="Séries | Drama",Breaking Bad S02E01
        http://srv:8080/series/user/pass/9101.mkv
        #EXTINF:-1 group-title="Novelas",Pantanal 1x05
        http://cdn/novelas/pantanal-105.mp4
        #EXTINF:-1,Canal sem grupo
        #EXTVLCOPT:http-user-agent=Test
        http://srv/x.ts
    """.trimIndent()

    private val lib = M3uParser.parse(sample.reader().buffered())

    @Test
    fun separaAoVivo() {
        assertEquals(listOf("Abertos, HD", "Abertos", M3uParser.NO_GROUP), lib.live.map { it.name })
        val globo = lib.live[0].items[0]
        assertEquals("Globo SP", globo.name)
        assertEquals("http://img/globo.png", globo.logo)
        assertNull(lib.live[1].items[0].logo)
    }

    @Test
    fun separaFilmes() {
        assertEquals(listOf("Filmes | Ação", "Lançamentos"), lib.movies.map { it.name })
        assertEquals("Matrix (1999)", lib.movies[0].items[0].name)
    }

    @Test
    fun agrupaSeries() {
        assertEquals(listOf("Séries | Drama", "Novelas"), lib.series.map { it.name })
        val bb = lib.series[0].items.single()
        assertEquals("Breaking Bad", bb.name)
        assertEquals("http://img/bb.jpg", bb.logo)
        assertEquals(listOf(1, 2), bb.seasons)
        assertEquals(listOf("S01E01", "S01E02", "S02E01"), bb.episodes.map { it.name.takeLast(6) })

        val pantanal = lib.series[1].items.single()
        assertEquals("Pantanal", pantanal.name)
        assertEquals(5, pantanal.episodes[0].episode)
    }

    @Test
    fun respeitaTvgType() {
        assertEquals(Kind.MOVIE, M3uParser.classify("http://x/1", "G", "Filme", "movie"))
        assertEquals(Kind.LIVE, M3uParser.classify("http://x/1.mp4", "G", "Canal", "live"))
    }

    @Test
    fun episodioSemPadrao() {
        assertEquals(Triple("Especial", 1, 0), M3uParser.parseEpisode("Especial"))
    }
}
