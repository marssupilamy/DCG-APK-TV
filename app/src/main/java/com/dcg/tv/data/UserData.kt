package com.dcg.tv.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** Favoritos, vistos recentemente e ponto onde cada filme ou episódio parou. */
object UserData {

    private const val PREFS = "dcg_user"
    private const val MAX_HISTORY = 60
    private const val MAX_PROGRESS = 500

    /** Abaixo disso não vale retomar; acima de 95% conta como visto. */
    private const val MIN_RESUME_MS = 30_000L
    private const val FINISHED_FRACTION = 0.95

    data class Progress(val position: Long, val duration: Long, val time: Long) {
        val finished get() = duration > 0 && position >= duration * FINISHED_FRACTION
    }

    private val gson = Gson()
    private var loaded = false
    private val favorites = LinkedHashSet<String>()
    private val history = ArrayList<String>()
    private val progress = LinkedHashMap<String, Progress>()
    private val lastEpisode = HashMap<String, String>()
    private lateinit var appContext: Context

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        appContext = ctx.applicationContext
        val p = prefs()
        read<List<String>>(p.getString("favorites", null))?.let { favorites.addAll(it) }
        read<List<String>>(p.getString("history", null))?.let { history.addAll(it) }
        read<Map<String, Progress>>(p.getString("progress", null))?.let { progress.putAll(it) }
        read<Map<String, String>>(p.getString("last_episode", null))?.let { lastEpisode.putAll(it) }
        loaded = true
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private inline fun <reified T> read(json: String?): T? =
        try {
            json?.let { gson.fromJson<T>(it, object : TypeToken<T>() {}.type) }
        } catch (e: Exception) {
            null
        }

    private fun save() {
        prefs().edit()
            .putString("favorites", gson.toJson(favorites.toList()))
            .putString("history", gson.toJson(history))
            .putString("progress", gson.toJson(progress))
            .putString("last_episode", gson.toJson(lastEpisode))
            .apply()
    }

    // ---- favoritos ----

    @Synchronized
    fun isFavorite(key: String) = key in favorites

    /** Retorna verdadeiro se o item passou a ser favorito. */
    @Synchronized
    fun toggleFavorite(key: String): Boolean {
        val added = if (favorites.remove(key)) false else favorites.add(key)
        save()
        return added
    }

    /** Mais recentes primeiro. */
    @Synchronized
    fun favoriteKeys(): List<String> = favorites.toList().asReversed()

    // ---- vistos recentemente ----

    @Synchronized
    fun recordWatch(key: String) {
        history.remove(key)
        history.add(0, key)
        while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        save()
    }

    @Synchronized
    fun historyKeys(): List<String> = history.toList()

    // ---- continuar assistindo ----

    @Synchronized
    fun saveProgress(key: String, position: Long, duration: Long) {
        if (duration <= 0 || position < 0) return
        progress.remove(key)
        progress[key] = Progress(position, duration, System.currentTimeMillis())
        while (progress.size > MAX_PROGRESS) progress.remove(progress.keys.first())
        save()
    }

    @Synchronized
    fun progress(key: String): Progress? = progress[key]

    /** Posição para retomar, ou nulo se é melhor começar do início. */
    @Synchronized
    fun resumePosition(key: String): Long? {
        val p = progress[key] ?: return null
        return if (p.position >= MIN_RESUME_MS && !p.finished) p.position else null
    }

    @Synchronized
    fun setLastEpisode(seriesKey: String, episodeKey: String) {
        lastEpisode[seriesKey] = episodeKey
        save()
    }

    @Synchronized
    fun lastEpisode(seriesKey: String): String? = lastEpisode[seriesKey]

    fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
