package com.dcg.tv.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/** Sinopse e ficha de um filme ou série. Os campos vêm do servidor e podem faltar. */
data class Details(
    val plot: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val duration: String? = null,
    val rating: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
) {
    /** "2021 · 1h 52min · Nota 7,5 · Ação, Aventura" */
    val summary: String
        get() = listOfNotNull(year, duration, rating?.let { "Nota $it" }, genre).joinToString("  ·  ")

    companion object {
        /** Lê o objeto "info" das respostas get_vod_info e get_series_info. */
        fun read(source: Reader): Details {
            val json = JsonReader(source).apply { isLenient = true }
            if (json.peek() != JsonToken.BEGIN_OBJECT) return Details()
            var info: Map<String, String> = emptyMap()
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "info" && json.peek() == JsonToken.BEGIN_OBJECT) info = readInfo(json) else json.skipValue()
            }
            json.endObject()
            return from(info)
        }

        private fun readInfo(json: JsonReader): Map<String, String> {
            val map = HashMap<String, String>()
            json.beginObject()
            while (json.hasNext()) {
                val name = json.nextName()
                when (json.peek()) {
                    JsonToken.STRING, JsonToken.NUMBER -> map[name] = json.nextString()
                    JsonToken.BEGIN_ARRAY -> {
                        // backdrop_path vem como lista: fica a primeira imagem.
                        json.beginArray()
                        while (json.hasNext()) {
                            if (json.peek() == JsonToken.STRING && name !in map) map[name] = json.nextString() else json.skipValue()
                        }
                        json.endArray()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
            return map
        }

        internal fun from(info: Map<String, String>): Details {
            fun get(vararg keys: String) = keys.firstNotNullOfOrNull { k -> info[k]?.trim()?.takeIf { it.isNotEmpty() && it != "0" && it != "null" } }
            return Details(
                plot = get("plot", "description"),
                genre = get("genre"),
                year = get("releasedate", "releaseDate", "release_date", "year")?.let { Regex("""\d{4}""").find(it)?.value },
                duration = formatDuration(get("duration_secs", "episode_run_time"), get("duration")),
                rating = get("rating", "rating_5based")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale("pt", "BR"), it) },
                cast = get("cast", "actors"),
                director = get("director"),
                poster = get("movie_image", "cover_big", "cover"),
                backdrop = get("backdrop_path"),
            )
        }

        /** Aceita segundos, minutos (episode_run_time) ou "01:52:00". */
        private fun formatDuration(seconds: String?, text: String?): String? {
            val minutes = when {
                seconds?.toLongOrNull() != null -> seconds.toLong().let { if (it > 600) it / 60 else it }
                text != null && text.count { it == ':' } >= 1 -> {
                    val parts = text.split(':').mapNotNull { it.trim().toLongOrNull() }
                    if (parts.size == 3) parts[0] * 60 + parts[1] else if (parts.size == 2) parts[0] * 60 + parts[1] else null
                }
                else -> null
            } ?: return null
            if (minutes <= 0) return null
            return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "${minutes}min"
        }
    }
}
