package com.dcg.tv.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import okio.ByteString.Companion.decodeBase64
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Um programa da grade de um canal. Horários em milissegundos desde 1970. */
data class Program(val title: String, val description: String, val start: Long, val end: Long) {
    fun isLive(now: Long) = now in start until end
}

object Epg {

    private val base64 = Regex("""^[A-Za-z0-9+/=\s]+$""")

    /** O servidor Xtream manda título e descrição em base64. Alguns mandam texto puro. */
    internal fun decode(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || t.length % 4 != 0 || !base64.matches(t)) return t
        val decoded = t.decodeBase64()?.utf8() ?: return t
        return if (decoded.any { it == '�' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') }) t else decoded.trim()
    }

    private val KEYS = setOf("title", "description", "start_timestamp", "stop_timestamp", "start", "end", "stop")

    /** Lê a resposta de get_short_epg: {"epg_listings":[...]} */
    internal fun readListings(source: Reader): List<Program> {
        val result = ArrayList<Program>()
        val json = JsonReader(source).apply { isLenient = true }
        if (json.peek() != JsonToken.BEGIN_OBJECT) return result
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() != "epg_listings" || json.peek() != JsonToken.BEGIN_ARRAY) {
                json.skipValue()
                continue
            }
            json.beginArray()
            while (json.hasNext()) {
                if (json.peek() != JsonToken.BEGIN_OBJECT) {
                    json.skipValue()
                    continue
                }
                val map = HashMap<String, String>()
                json.beginObject()
                while (json.hasNext()) {
                    val name = json.nextName()
                    if (name in KEYS && (json.peek() == JsonToken.STRING || json.peek() == JsonToken.NUMBER)) {
                        map[name] = json.nextString()
                    } else {
                        json.skipValue()
                    }
                }
                json.endObject()
                toProgram(map)?.let(result::add)
            }
            json.endArray()
        }
        json.endObject()
        return result.sortedBy { it.start }
    }

    private fun toProgram(map: Map<String, String>): Program? {
        val start = map["start_timestamp"]?.toLongOrNull()?.times(1000) ?: parseDate(map["start"]) ?: return null
        val end = map["stop_timestamp"]?.toLongOrNull()?.times(1000) ?: parseDate(map["end"] ?: map["stop"]) ?: return null
        if (end <= start) return null
        return Program(decode(map["title"].orEmpty()), decode(map["description"].orEmpty()), start, end)
    }

    private fun parseDate(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }.parse(text)?.time
        } catch (e: Exception) {
            null
        }
    }

    /** O programa de agora e o próximo. */
    fun nowNext(list: List<Program>, now: Long): Pair<Program?, Program?> {
        val current = list.firstOrNull { it.isLive(now) }
        val next = list.firstOrNull { it.start >= (current?.end ?: now) }
        return current to next
    }

    fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(ms)

    /** "Agora: Jornal (20:00–21:00)  ·  Depois: Novela (21:00)" */
    fun describeNowNext(list: List<Program>, now: Long): String? {
        val (current, next) = nowNext(list, now)
        val parts = listOfNotNull(
            current?.let { "Agora: ${it.title} (${time(it.start)}–${time(it.end)})" },
            next?.let { "Depois: ${it.title} (${time(it.start)})" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

/** Identificador do servidor Xtream guardado na chave do item ("live:123" vira "123"). */
val Entry.xtreamStreamId: String?
    get() = if (key.startsWith("live:") || key.startsWith("movie:") || key.startsWith("ep:")) key.substringAfter(':') else null
