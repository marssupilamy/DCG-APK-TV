package com.dcg.tv.data

import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Lê o guia XMLTV (xmltv.php), que traz a programação de todos os canais num arquivo só.
 * Para economizar memória, guarda apenas os programas que [keep] aceita, dentro do período pedido.
 */
object Xmltv {

    /** "20261017200000 -0300" ou "20261017200000" (horário do aparelho). */
    internal fun parseTime(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.length < 14) return null
        val base = t.substring(0, 14)
        val zone = t.substring(14).trim()
        return try {
            if (zone.matches(Regex("""[+-]\d{4}"""))) {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse("$base $zone")?.time
            } else {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getDefault() }.parse(base)?.time
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Retorna os programas por id de canal do guia. */
    fun read(
        parser: XmlPullParser,
        from: Long,
        to: Long,
        keep: (channelId: String, program: Program) -> Boolean,
    ): Map<String, List<Program>> {
        val result = HashMap<String, MutableList<Program>>()
        var channel: String? = null
        var start: Long? = null
        var stop: Long? = null
        var title = ""
        var desc = ""
        var field: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")?.trim()
                        start = parseTime(parser.getAttributeValue(null, "start"))
                        stop = parseTime(parser.getAttributeValue(null, "stop"))
                        title = ""
                        desc = ""
                    }
                    "title" -> if (title.isEmpty()) field = "title"
                    "desc" -> if (desc.isEmpty()) field = "desc"
                }
                XmlPullParser.TEXT -> when (field) {
                    "title" -> title += parser.text
                    "desc" -> desc += parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "title", "desc" -> field = null
                    "programme" -> {
                        val c = channel
                        val s = start
                        val e = stop
                        if (c != null && s != null && e != null && e > from && s < to && e > s) {
                            val program = Program(title.trim(), desc.trim(), s, e)
                            if (keep(c, program)) result.getOrPut(c) { ArrayList() }.add(program)
                        }
                        channel = null
                    }
                }
            }
            event = parser.next()
        }
        result.values.forEach { list -> list.sortBy { it.start } }
        return result
    }
}
