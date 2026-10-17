package com.dcg.tv.data

import java.io.BufferedReader
import java.text.Normalizer

/**
 * Lê listas M3U/M3U8 e separa os itens em TV ao vivo, filmes e séries.
 *
 * A separação usa, nesta ordem: o atributo tvg-type, o caminho do link
 * (/movie/ e /series/ das listas Xtream) e a extensão do arquivo
 * (.mp4, .mkv e similares são conteúdo sob demanda).
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val sxeRegex = Regex("""(?i)^(.*?)[\s._|:-]*\bS(\d{1,2})[\s._-]*E(\d{1,4})""")
    private val nxnRegex = Regex("""(?i)^(.*?)[\s._|:-]*\b(\d{1,2})x(\d{1,3})\b""")
    private val accents = Regex("""\p{Mn}+""")

    private val vodExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "m4v", "webm", "mpg", "mpeg",
    )
    private val seriesWords = listOf("serie", "novela", "temporada", "season")

    const val NO_GROUP = "Sem categoria"

    fun parse(reader: BufferedReader): Library {
        val entries = ArrayList<Entry>()
        var info: String? = null
        var extGroup: String? = null
        for (raw in reader.lineSequence()) {
            val line = raw.removePrefix("﻿").trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    extGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substring(8).trim()
                line.startsWith("#") -> Unit
                else -> {
                    entries.add(buildEntry(info, extGroup, line))
                    info = null
                    extGroup = null
                }
            }
        }
        return organize(entries)
    }

    internal fun buildEntry(info: String?, extGroup: String?, url: String): Entry {
        val attrs = HashMap<String, String>()
        var title = ""
        if (info != null) {
            attrRegex.findAll(info).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2].trim() }
            title = titleOf(info)
        }
        if (title.isBlank()) title = attrs["tvg-name"].orEmpty()
        if (title.isBlank()) title = url.substringBefore('|').substringBefore('?').substringAfterLast('/')

        val group = attrs["group-title"]?.takeIf { it.isNotBlank() }
            ?: extGroup?.takeIf { it.isNotBlank() }
            ?: NO_GROUP
        val logo = (attrs["tvg-logo"] ?: attrs["logo"])?.takeIf { it.isNotBlank() }
        val kind = classify(url, group, title, attrs["tvg-type"] ?: attrs["type"])

        if (kind == Kind.SERIES) {
            val (series, season, episode) = parseEpisode(title)
            return Entry(title, url, logo, group, kind, series, season, episode)
        }
        return Entry(title, url, logo, group, kind, epgId = attrs["tvg-id"]?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** O título fica depois da primeira vírgula que não está entre aspas. */
    private fun titleOf(info: String): String {
        var inQuotes = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return info.substring(i + 1).trim()
        }
        return ""
    }

    internal fun classify(url: String, group: String, title: String, type: String?): Kind {
        when (type?.lowercase()) {
            "live", "stream", "channel" -> return Kind.LIVE
            "movie", "movies", "vod" -> return Kind.MOVIE
            "series", "serie" -> return Kind.SERIES
        }
        val path = url.substringBefore('|').substringBefore('?').lowercase()
        if ("/series/" in path) return Kind.SERIES
        if ("/movie/" in path || "/movies/" in path) return Kind.MOVIE

        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext in vodExtensions) {
            val g = normalize(group)
            val looksLikeSeries = hasEpisode(title) || seriesWords.any { it in g }
            return if (looksLikeSeries) Kind.SERIES else Kind.MOVIE
        }
        return Kind.LIVE
    }

    private fun hasEpisode(title: String) = sxeRegex.containsMatchIn(title) || nxnRegex.containsMatchIn(title)

    /** Retorna nome da série, temporada e episódio. Sem padrão reconhecido, vira temporada 1. */
    internal fun parseEpisode(title: String): Triple<String, Int, Int> {
        val match = sxeRegex.find(title) ?: nxnRegex.find(title)
        if (match != null) {
            val name = match.groupValues[1].trim(' ', '-', '_', '.', '|', ':')
            if (name.isNotEmpty()) {
                return Triple(name, match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }
        }
        return Triple(title.trim(), 1, 0)
    }

    fun normalize(text: String): String =
        accents.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()

    private fun organize(entries: List<Entry>): Library {
        val live = LinkedHashMap<String, MutableList<Entry>>()
        val movies = LinkedHashMap<String, MutableList<Entry>>()
        val series = LinkedHashMap<String, LinkedHashMap<String, MutableList<Entry>>>()

        for (e in entries) {
            when (e.kind) {
                Kind.LIVE -> live.getOrPut(e.group) { ArrayList() }.add(e)
                Kind.MOVIE -> movies.getOrPut(e.group) { ArrayList() }.add(e)
                Kind.SERIES -> series.getOrPut(e.group) { LinkedHashMap() }
                    .getOrPut(normalize(e.seriesName ?: e.name)) { ArrayList() }
                    .add(e)
            }
        }

        var nextId = 0
        val seriesCategories = series.map { (group, byName) ->
            Category(group, byName.values.map { episodes ->
                Series(
                    id = nextId++,
                    name = episodes.first().seriesName ?: episodes.first().name,
                    logo = episodes.firstNotNullOfOrNull { it.logo },
                    group = group,
                    episodes = episodes.sortedWith(compareBy({ it.season }, { it.episode })),
                )
            })
        }

        return Library(
            live = live.map { Category(it.key, it.value) },
            movies = movies.map { Category(it.key, it.value) },
            series = seriesCategories,
        )
    }
}
