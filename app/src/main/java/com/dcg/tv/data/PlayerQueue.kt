package com.dcg.tv.data

/** Lista que o player recebe. Fica em memória porque listas grandes não cabem num Intent. */
object PlayerQueue {
    var items: List<Entry> = emptyList()
        private set
    var start: Int = 0
        private set
    var live: Boolean = false
        private set

    fun set(items: List<Entry>, start: Int, live: Boolean) {
        this.items = items
        this.start = start
        this.live = live
    }
}
