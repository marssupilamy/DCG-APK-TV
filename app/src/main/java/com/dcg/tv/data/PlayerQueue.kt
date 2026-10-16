package com.dcg.tv.data

/** Lista que o player recebe. Fica em memória porque listas grandes não cabem num Intent. */
object PlayerQueue {
    var items: List<Entry> = emptyList()
        private set
    var start: Int = 0
        private set
    var live: Boolean = false
        private set

    /** Quando a lista é de episódios, o histórico guarda a série e não o episódio. */
    var seriesKey: String? = null
        private set

    fun set(items: List<Entry>, start: Int, live: Boolean, seriesKey: String? = null) {
        this.items = items
        this.start = start
        this.live = live
        this.seriesKey = seriesKey
    }
}
