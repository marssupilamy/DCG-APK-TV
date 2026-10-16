package com.dcg.tv.data

import java.util.Calendar

enum class Sport(val label: String) {
    FOOTBALL("Futebol"),
    BASKETBALL("Basquete"),
    TENNIS("Tênis"),
    VOLLEYBALL("Vôlei"),
    MOTOR("Automobilismo"),
    FIGHT("Lutas"),
    AMERICAN_FOOTBALL("Futebol americano"),
    OTHER("Outros esportes"),
}

/** Um jogo pode passar em vários canais ao mesmo tempo. */
data class Game(val program: Program, val sport: Sport, val channels: List<Entry>)

/**
 * Monta a agenda de jogos a partir da programação dos canais de esporte.
 * A separação por esporte usa palavras do título e da descrição do programa.
 */
object Sports {

    private val CHANNEL_WORDS = listOf(
        "esporte", "sport", "espn", "premiere", "combate", "dazn", "ufc", "nba", "futebol", "fight",
        "golf", "racing", "eleven", "goat", "caze", "xsports", "onefootball", "f1",
    )

    private val REPLAY_WORDS = listOf("reprise", "melhores momentos", "compacto", "(vt)", "vt:", "gols da rodada")

    private val SPORT_WORDS: List<Pair<Sport, List<String>>> = listOf(
        Sport.AMERICAN_FOOTBALL to listOf("nfl", "futebol americano", "super bowl"),
        Sport.BASKETBALL to listOf("nba", "nbb", "wnba", "basquete", "basketball", "euroliga"),
        Sport.TENNIS to listOf("tenis", "atp", "wta", "wimbledon", "roland garros", "us open", "australian open"),
        Sport.VOLLEYBALL to listOf("volei", "volleyball", "superliga", "vnl"),
        Sport.MOTOR to listOf(
            "formula 1", "formula1", "formula e", "f1 ", "grande premio", "gp d", "motogp", "nascar",
            "stock car", "indy", "rally", "rali",
        ),
        Sport.FIGHT to listOf("ufc", "boxe", "boxing", "mma", "bellator", "pfl", "jiu-jitsu", "jiu jitsu", "wwe", "luta"),
        Sport.OTHER to listOf(
            "beisebol", "mlb", "hoquei", "nhl", "handebol", "golfe", "pga", "rugby", "ciclismo",
            "atletismo", "natacao", "sinuca", "dardos", "futsal", "e-sports", "esports",
        ),
        Sport.FOOTBALL to listOf(
            "futebol", "brasileirao", "libertadores", "sul-americana", "sulamericana", "copa do brasil",
            "champions", "premier league", "la liga", "laliga", "serie a", "serie b", "bundesliga", "ligue 1",
            "paulistao", "carioca", "gauchao", "mineiro", "campeonato", "eliminatorias", "copa do mundo",
            "mundial de clubes", "liga europa", "europa league", "conference league", "copa america",
            "eurocopa", "mls", "liga dos campeoes", "fa cup", "copa do rei", "nordeste",
        ),
    )

    private val MATCH = Regex("""\S\s+(x|vs\.?|versus)\s+\S""")

    /** Esportes em que o programa vale mesmo sem "Time A x Time B" no título. */
    private val EVENT_SPORTS = setOf(Sport.MOTOR, Sport.FIGHT, Sport.TENNIS)

    fun isSportsGroup(group: String): Boolean {
        val g = M3uParser.normalize(group)
        return "esporte" in g || "sport" in g || "futebol" in g
    }

    fun isSportsChannel(e: Entry): Boolean {
        val text = M3uParser.normalize(e.group + " " + e.name)
        return CHANNEL_WORDS.any { it in text }
    }

    private fun sportIn(text: String): Sport? =
        SPORT_WORDS.firstOrNull { (_, words) -> words.any { it in text } }?.first

    /** Esporte do programa, ou nulo se não parece um jogo ou evento. */
    fun classify(p: Program): Sport? {
        val title = M3uParser.normalize(p.title)
        val all = title + " " + M3uParser.normalize(p.description)
        if (REPLAY_WORDS.any { it in title }) return null
        val isMatch = MATCH.containsMatchIn(title)
        val sport = sportIn(title) ?: sportIn(all)
        return when {
            sport != null && (isMatch || sport in EVENT_SPORTS || "ao vivo" in all) -> sport
            sport == null && isMatch -> Sport.FOOTBALL
            else -> null
        }
    }

    private val QUALITY = Regex("""(?i)\b(fhd|uhd|hd|sd|4k|h\.?265|hevc|alt\d*|\[.*?]|\(.*?\))""")

    /** "ESPN FHD" e "ESPN HD" são o mesmo canal: fica só o primeiro. */
    fun channelKey(e: Entry) = M3uParser.normalize(QUALITY.replace(e.name, "")).replace(Regex("""[^a-z0-9]+"""), "")

    /** Junta a programação dos canais em jogos, de agora até [hours] horas à frente. */
    fun buildAgenda(listings: Map<Entry, List<Program>>, now: Long, hours: Int = 36): Map<Sport, List<Game>> {
        val limit = now + hours * 3_600_000L
        val byGame = LinkedHashMap<Pair<String, Long>, Pair<Program, MutableList<Entry>>>()
        val sports = HashMap<Pair<String, Long>, Sport>()
        for ((channel, programs) in listings) {
            for (p in programs) {
                if (p.end <= now || p.start >= limit) continue
                val sport = classify(p) ?: continue
                val key = M3uParser.normalize(p.title).replace(Regex("""[^a-z0-9]+"""), "") to p.start
                val slot = byGame.getOrPut(key) { p to ArrayList() }
                if (slot.second.none { it.key == channel.key }) slot.second.add(channel)
                sports.putIfAbsent(key, sport)
            }
        }
        return byGame.entries
            .map { (key, slot) -> Game(slot.first, sports.getValue(key), slot.second) }
            .sortedWith(compareBy({ !it.program.isLive(now) }, { it.program.start }))
            .groupBy { it.sport }
            .toSortedMap(compareBy { it.ordinal })
    }

    /** "AO VIVO", "Hoje 20:00", "Amanhã 16:00" ou "sáb 18:30". */
    fun whenLabel(p: Program, now: Long): String {
        if (p.isLive(now)) return "AO VIVO"
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val day = Calendar.getInstance().apply { timeInMillis = p.start }
        val diff = day.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR) +
            (day.get(Calendar.YEAR) - today.get(Calendar.YEAR)) * 365
        val prefix = when (diff) {
            0 -> "Hoje"
            1 -> "Amanhã"
            else -> java.text.SimpleDateFormat("EEE", java.util.Locale("pt", "BR")).format(p.start)
        }
        return "$prefix ${Epg.time(p.start)}"
    }
}
