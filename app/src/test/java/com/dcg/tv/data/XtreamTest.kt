package com.dcg.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class XtreamTest {

    private val account = XtreamAccount(listOf("http://srv.com"), "user", "pass")

    @Test
    fun normalizaServidores() {
        assertEquals(
            listOf("http://xc.dxvd.online", "http://dns.dxvd.online:8080", "https://on.xdps.space"),
            Xtream.parseServers("xc.dxvd.online  http://dns.dxvd.online:8080/,https://on.xdps.space/get.php?username=a"),
        )
    }

    @Test
    fun loginAceito() {
        val body = """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8","ts"]},"server_info":{}}"""
        val login = Xtream.checkLogin("http://srv.com", body)
        assertEquals("ts", login.liveExt)
    }

    @Test(expected = Xtream.LoginRefused::class)
    fun loginRecusado() {
        Xtream.checkLogin("http://srv.com", """{"user_info":{"auth":0}}""")
    }

    @Test(expected = Xtream.LoginRefused::class)
    fun contaExpirada() {
        Xtream.checkLogin("http://srv.com", """{"user_info":{"auth":1,"status":"Expired"}}""")
    }

    @Test
    fun agrupaCanaisPorCategoria() {
        val cats = """[{"category_id":"1","category_name":"Abertos","parent_id":0},{"category_id":2,"category_name":"Esportes"},{"category_id":"3","category_name":"Vazia"}]"""
        val streams = """[
            {"num":1,"name":"Globo","stream_type":"live","stream_id":10,"stream_icon":"http://img/g.png","category_id":"1","tv_archive":0,"extra":{"a":[1,2]}},
            {"num":2,"name":"ESPN","stream_id":"11","stream_icon":"","category_id":"2"},
            {"num":3,"name":"Perdido","stream_id":12,"category_id":"99"}
        ]"""
        val result = Xtream.readStreams(cats.reader(), streams.reader(), Kind.LIVE, { id, ext -> account.liveUrl("http://srv.com", id, ext) }, "ts")
        assertEquals(listOf("Abertos", "Esportes", M3uParser.NO_GROUP), result.map { it.name })
        val globo = result[0].items.single()
        assertEquals("http://srv.com/live/user/pass/10.ts", globo.url)
        assertEquals("http://img/g.png", globo.logo)
        assertNull(result[1].items.single().logo)
    }

    @Test
    fun filmesUsamExtensaoDoServidor() {
        val cats = """[{"category_id":"5","category_name":"Ação"}]"""
        val vod = """[{"name":"Matrix","stream_id":500,"category_id":"5","container_extension":"mkv"},{"name":"Duna","stream_id":501,"category_id":"5"}]"""
        val result = Xtream.readStreams(cats.reader(), vod.reader(), Kind.MOVIE, { id, ext -> account.movieUrl("http://srv.com", id, ext) }, "mp4")
        assertEquals(listOf("http://srv.com/movie/user/pass/500.mkv", "http://srv.com/movie/user/pass/501.mp4"), result[0].items.map { it.url })
    }

    @Test
    fun leSeriesEEpisodios() {
        val cats = """[{"category_id":"7","category_name":"Drama"}]"""
        val list = """[{"series_id":900,"name":"Breaking Bad","cover":"http://img/bb.jpg","category_id":"7"}]"""
        val series = Xtream.readSeries(cats.reader(), list.reader(), firstId = 0)[0].items.single()
        assertEquals("900", series.xtreamId)

        val info = """{"seasons":[],"info":{"name":"Breaking Bad"},"episodes":{
            "2":[{"id":"2001","episode_num":1,"title":"BB S02E01","container_extension":"mkv","season":2,"info":{"duration":"47:00"}}],
            "1":[{"id":"1002","episode_num":"2","title":"BB S01E02","container_extension":"mp4","season":1},
                 {"id":"1001","episode_num":1,"title":"BB S01E01","container_extension":"mp4"}]
        }}"""
        val episodes = Xtream.readEpisodes(info.reader(), series) { id, ext -> account.episodeUrl("http://srv.com", id, ext) }
        assertEquals(listOf("BB S01E01", "BB S01E02", "BB S02E01"), episodes.map { it.name })
        assertEquals("http://srv.com/series/user/pass/2001.mkv", episodes[2].url)
        assertEquals(1, episodes[0].season)
    }

    @Test
    fun episodiosEmLista() {
        val series = Series(0, "X", null, "G", emptyList(), "1")
        val info = """{"episodes":[[{"id":"1","episode_num":1,"title":"A","season":1}]]}"""
        assertEquals(1, Xtream.readEpisodes(info.reader(), series) { id, ext -> "$id.$ext" }.size)
    }
}
