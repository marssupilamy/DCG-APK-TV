package com.dcg.tv.data

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.Reader
import java.net.URLEncoder

/** Conta no padrão Xtream Codes: um ou mais servidores, usuário e senha. */
data class XtreamAccount(val servers: List<String>, val user: String, val pass: String) {

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    fun apiUrl(server: String, action: String? = null, extra: String = ""): String {
        val base = "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        return if (action == null) base else "$base&action=$action$extra"
    }

    fun liveUrl(server: String, id: String, ext: String) = "$server/live/$user/$pass/$id.$ext"
    fun movieUrl(server: String, id: String, ext: String) = "$server/movie/$user/$pass/$id.$ext"
    fun episodeUrl(server: String, id: String, ext: String) = "$server/series/$user/$pass/$id.$ext"
}

object Xtream {

    /** Aceita "dominio.com", "http://dominio.com:8080/" ou um link get.php completo. */
    fun normalizeServer(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val scheme = s.substringBefore("://")
        val authority = s.substringAfter("://").substringBefore('/').substringBefore('?')
        return "${scheme.lowercase()}://$authority"
    }

    fun parseServers(text: String): List<String> =
        text.split(Regex("""[\s,;]+""")).filter { it.isNotBlank() }.map(::normalizeServer).distinct()

    class Login(val server: String, val liveExt: String)

    /** Tenta cada servidor até um aceitar o login. O último que funcionou é testado primeiro. */
    fun login(account: XtreamAccount, preferred: String?): Login {
        val order = listOfNotNull(preferred?.takeIf { it in account.servers }) + account.servers.filter { it != preferred }
        val errors = ArrayList<String>()
        for (server in order) {
            try {
                return checkLogin(server, Net.fetchText(account.apiUrl(server)))
            } catch (e: LoginRefused) {
                throw IOException("${host(server)}: ${e.message}")
            } catch (e: Exception) {
                errors.add("${host(server)}: ${Net.describe(e)}")
            }
        }
        throw IOException("Nenhum servidor respondeu.\n" + errors.joinToString("\n"))
    }

    class LoginRefused(message: String) : IOException(message)

    internal fun checkLogin(server: String, body: String): Login {
        val root = try {
            JsonParser.parseString(body).asJsonObject
        } catch (e: Exception) {
            throw IOException("resposta inválida do servidor")
        }
        val info = root.getAsJsonObject("user_info") ?: throw IOException("resposta inválida do servidor")
        val auth = info.get("auth")?.takeIf { !it.isJsonNull }?.asString
        if (auth != "1" && auth != "true") throw LoginRefused("usuário ou senha incorretos")
        val status = info.get("status")?.takeIf { !it.isJsonNull }?.asString
        if (status != null && !status.equals("Active", true)) throw LoginRefused("conta com situação \"$status\"")
        val formats = info.getAsJsonArray("allowed_output_formats")?.mapNotNull { it.takeIf { e -> !e.isJsonNull }?.asString }.orEmpty()
        val ext = if (formats.isEmpty() || "ts" in formats) "ts" else formats.first()
        return Login(server, ext)
    }

    private fun host(server: String) = server.substringAfter("://")

    // ---- leitura dos arquivos JSON, um item por vez para economizar memória ----

    private fun reader(r: Reader) = JsonReader(r).apply { isLenient = true }

    /** Lê uma lista de objetos simples. Aceita também objeto com os itens como valores. */
    internal fun readItems(source: Reader, keys: Set<String>, onItem: (Map<String, String>) -> Unit) {
        val json = reader(source)
        when (json.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                json.beginArray()
                while (json.hasNext()) {
                    if (json.peek() == JsonToken.BEGIN_OBJECT) onItem(readFlat(json, keys)) else json.skipValue()
                }
                json.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                json.beginObject()
                while (json.hasNext()) {
                    json.nextName()
                    if (json.peek() == JsonToken.BEGIN_OBJECT) onItem(readFlat(json, keys)) else json.skipValue()
                }
                json.endObject()
            }
            else -> json.skipValue()
        }
    }

    private fun readFlat(json: JsonReader, keys: Set<String>): Map<String, String> {
        val map = HashMap<String, String>()
        json.beginObject()
        while (json.hasNext()) {
            val name = json.nextName()
            if (name !in keys) {
                json.skipValue()
                continue
            }
            when (json.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> map[name] = json.nextString()
                JsonToken.BOOLEAN -> map[name] = json.nextBoolean().toString()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return map
    }

    internal fun readCategories(source: Reader): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        readItems(source, setOf("category_id", "category_name")) { item ->
            val id = item["category_id"] ?: return@readItems
            result[id] = item["category_name"]?.takeIf { it.isNotBlank() } ?: M3uParser.NO_GROUP
        }
        return result
    }

    private val STREAM_KEYS = setOf("name", "stream_id", "stream_icon", "category_id", "container_extension")
    private val SERIES_KEYS = setOf("name", "series_id", "cover", "category_id")

    /** Agrupa os itens pela ordem das categorias do servidor. Categorias vazias somem. */
    private fun <T> grouped(categories: Map<String, String>, items: List<Pair<String?, T>>): List<Category<T>> {
        val byCategory = LinkedHashMap<String, MutableList<T>>()
        categories.keys.forEach { byCategory[it] = ArrayList() }
        val orphans = ArrayList<T>()
        for ((cat, item) in items) {
            val list = cat?.let { byCategory[it] }
            if (list != null) list.add(item) else orphans.add(item)
        }
        val result = byCategory.filterValues { it.isNotEmpty() }.map { (id, list) -> Category(categories.getValue(id), list.toList()) }
        return if (orphans.isEmpty()) result else result + Category(M3uParser.NO_GROUP, orphans)
    }

    internal fun readStreams(
        categoriesSource: Reader,
        streamsSource: Reader,
        kind: Kind,
        url: (id: String, ext: String) -> String,
        defaultExt: String,
    ): List<Category<Entry>> {
        val categories = readCategories(categoriesSource)
        val items = ArrayList<Pair<String?, Entry>>()
        readItems(streamsSource, STREAM_KEYS) { s ->
            val id = s["stream_id"] ?: return@readItems
            val ext = if (kind == Kind.LIVE) defaultExt else s["container_extension"]?.takeIf { it.isNotBlank() } ?: defaultExt
            val catId = s["category_id"]
            val group = catId?.let { categories[it] } ?: M3uParser.NO_GROUP
            val entry = Entry(
                name = s["name"]?.trim().orEmpty().ifEmpty { id },
                url = url(id, ext),
                logo = s["stream_icon"]?.takeIf { it.isNotBlank() },
                group = group,
                kind = kind,
                key = (if (kind == Kind.LIVE) "live:" else "movie:") + id,
            )
            items.add(catId to entry)
        }
        return grouped(categories, items)
    }

    internal fun readSeries(categoriesSource: Reader, seriesSource: Reader, firstId: Int): List<Category<Series>> {
        val categories = readCategories(categoriesSource)
        val items = ArrayList<Pair<String?, Series>>()
        var nextId = firstId
        readItems(seriesSource, SERIES_KEYS) { s ->
            val id = s["series_id"] ?: return@readItems
            val catId = s["category_id"]
            val series = Series(
                id = nextId++,
                name = s["name"]?.trim().orEmpty().ifEmpty { id },
                logo = s["cover"]?.takeIf { it.isNotBlank() },
                group = catId?.let { categories[it] } ?: M3uParser.NO_GROUP,
                episodes = emptyList(),
                xtreamId = id,
            )
            items.add(catId to series)
        }
        return grouped(categories, items)
    }

    private val EPISODE_KEYS = setOf("id", "episode_num", "title", "container_extension", "season")

    /** Lê o resultado de get_series_info. Os episódios vêm agrupados por temporada. */
    internal fun readEpisodes(source: Reader, series: Series, url: (id: String, ext: String) -> String): List<Entry> {
        val result = ArrayList<Entry>()
        fun add(seasonKey: String?, e: Map<String, String>) {
            val id = e["id"] ?: return
            val season = e["season"]?.toIntOrNull() ?: seasonKey?.toIntOrNull() ?: 1
            val number = e["episode_num"]?.toIntOrNull() ?: 0
            result.add(
                Entry(
                    name = e["title"]?.trim().orEmpty().ifEmpty { "Episódio $number" },
                    url = url(id, e["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4"),
                    logo = series.logo,
                    group = series.group,
                    kind = Kind.SERIES,
                    seriesName = series.name,
                    season = season,
                    episode = number,
                    key = "ep:$id",
                )
            )
        }
        fun readSeasonArray(json: JsonReader, seasonKey: String?) {
            json.beginArray()
            while (json.hasNext()) {
                if (json.peek() == JsonToken.BEGIN_OBJECT) add(seasonKey, readFlat(json, EPISODE_KEYS)) else json.skipValue()
            }
            json.endArray()
        }

        val json = reader(source)
        if (json.peek() != JsonToken.BEGIN_OBJECT) return result
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() != "episodes") {
                json.skipValue()
                continue
            }
            when (json.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        val key = json.nextName()
                        if (json.peek() == JsonToken.BEGIN_ARRAY) readSeasonArray(json, key) else json.skipValue()
                    }
                    json.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    json.beginArray()
                    while (json.hasNext()) {
                        if (json.peek() == JsonToken.BEGIN_ARRAY) readSeasonArray(json, null) else json.skipValue()
                    }
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return result.sortedWith(compareBy({ it.season }, { it.episode }))
    }
}
