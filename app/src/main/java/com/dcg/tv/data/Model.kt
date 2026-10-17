package com.dcg.tv.data

enum class Kind { LIVE, MOVIE, SERIES }

data class Entry(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val kind: Kind,
    val seriesName: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    /** Identificador estável, usado em favoritos, histórico e "continuar assistindo". */
    val key: String = url,
    /** Identificador do canal no guia de programação (epg_channel_id ou tvg-id). */
    val epgId: String? = null,
)

data class Series(
    val id: Int,
    val name: String,
    val logo: String?,
    val group: String,
    val episodes: List<Entry>,
    /** Nas contas Xtream os episódios vêm do servidor só quando a série é aberta. */
    val xtreamId: String? = null,
) {
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()

    val key: String
        get() = if (xtreamId != null) "series:$xtreamId" else "series:$group/${name.lowercase()}"
}

data class Category<T>(val name: String, val items: List<T>)

class Library(
    val live: List<Category<Entry>>,
    val movies: List<Category<Entry>>,
    val series: List<Category<Series>>,
) {
    val seriesById: Map<Int, Series> = series.flatMap { it.items }.associateBy { it.id }

    val entriesByKey: Map<String, Entry> by lazy {
        HashMap<String, Entry>().also { map ->
            live.forEach { c -> c.items.forEach { map.putIfAbsent(it.key, it) } }
            movies.forEach { c -> c.items.forEach { map.putIfAbsent(it.key, it) } }
        }
    }

    val seriesByKey: Map<String, Series> by lazy {
        HashMap<String, Series>().also { map -> seriesById.values.forEach { map.putIfAbsent(it.key, it) } }
    }

    val liveCount get() = live.sumOf { it.items.size }
    val movieCount get() = movies.sumOf { it.items.size }
    val seriesCount get() = series.sumOf { it.items.size }

    val isEmpty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}
