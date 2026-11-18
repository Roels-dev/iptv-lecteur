package ci.patrice.iptv

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Une émission du guide des programmes. */
data class Programme(val start: Long, val stop: Long, val title: String, val desc: String)

/**
 * Guide des programmes (XMLTV) généré chaque jour par GitHub Actions
 * (workflow epg.yml) et publié dans la release « epg ».
 */
object Epg {
    private const val URL_GUIDE = "https://github.com/Roels-dev/iptv-lecteur/releases/download/epg/guide.xml.gz"
    private const val CACHE_FILE = "guide.xml.gz"
    private const val MAX_AGE_MS = 6L * 60 * 60 * 1000          // re-télécharge au-delà de 6 h
    private const val KEEP_AHEAD_MS = 36L * 60 * 60 * 1000      // garde les 36 prochaines heures

    @Volatile private var byId: Map<String, List<Programme>> = emptyMap()
    @Volatile private var byBase: Map<String, List<Programme>> = emptyMap()
    @Volatile private var byName: Map<String, List<Programme>> = emptyMap()
    @Volatile var loadedAt: Long = 0L
        private set

    val isLoaded: Boolean get() = byId.isNotEmpty()

    private fun norm(s: String) = s.lowercase(Locale.ROOT).trim()
    private fun baseOf(id: String) = norm(id).substringBefore('@')
    private fun normName(s: String) = s.lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), "")
        .replace(Regex("\\b(hd|fhd|uhd|sd|4k|tv)\\b"), "")
        .filter { it.isLetterOrDigit() }

    /** Programmes connus pour une chaîne (par tvg-id, puis id sans variante, puis nom). */
    fun programmes(c: Channel): List<Programme> {
        if (c.tvgId.isNotBlank()) {
            byId[norm(c.tvgId)]?.let { return it }
            byBase[baseOf(c.tvgId)]?.let { return it }
        }
        return byName[normName(c.name)] ?: emptyList()
    }

    /** Émission en cours et suivante. */
    fun nowNext(c: Channel, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = programmes(c)
        val i = list.indexOfFirst { it.stop > now }
        if (i < 0) return null to null
        val cur = list[i].takeIf { it.start <= now }
        val next = if (cur != null) list.getOrNull(i + 1) else list[i]
        return cur to next
    }

    /**
     * Charge le guide (cache local si récent, sinon téléchargement). Appel réseau + analyse :
     * à lancer hors du thread principal. Renvoie true si des programmes sont disponibles.
     */
    fun load(ctx: Context, force: Boolean = false): Boolean {
        val file = File(ctx.cacheDir, CACHE_FILE)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
        if (!fresh || force) {
            try {
                download(file)
            } catch (_: Exception) {
                // pas de réseau : on garde l'ancien fichier s'il existe
            }
        }
        if (!file.exists()) return false
        return try {
            parse(file)
            loadedAt = System.currentTimeMillis()
            isLoaded
        } catch (_: Exception) {
            false
        }
    }

    private fun download(target: File) {
        val conn = URL(URL_GUIDE).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "IPTVLecteur")
        if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
        val tmp = File(target.parentFile, target.name + ".tmp")
        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        conn.disconnect()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private val fmtZone = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    }
    private val fmtUtc = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() =
            SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val t = s.trim()
        return try {
            if (t.length > 15) fmtZone.get()!!.parse(t)?.time else fmtUtc.get()!!.parse(t.take(14))?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun parse(file: File) {
        val now = System.currentTimeMillis()
        val until = now + KEEP_AHEAD_MS
        val programmes = HashMap<String, MutableList<Programme>>()
        val names = HashMap<String, String>() // nom normalisé -> id
        val nameCount = HashMap<String, Int>()

        GZIPInputStream(file.inputStream().buffered()).use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, "UTF-8")
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> {
                            val id = p.getAttributeValue(null, "id").orEmpty()
                            // lit les <display-name> de la chaîne
                            while (!(p.next() == XmlPullParser.END_TAG && p.name == "channel")) {
                                if (p.eventType == XmlPullParser.START_TAG && p.name == "display-name") {
                                    val n = normName(p.nextText())
                                    if (n.isNotEmpty() && id.isNotEmpty()) {
                                        names[n] = id
                                        nameCount[n] = (nameCount[n] ?: 0) + 1
                                    }
                                }
                                if (p.eventType == XmlPullParser.END_DOCUMENT) break
                            }
                        }
                        "programme" -> {
                            val start = parseTime(p.getAttributeValue(null, "start"))
                            val stop = parseTime(p.getAttributeValue(null, "stop"))
                            val ch = p.getAttributeValue(null, "channel").orEmpty()
                            var title = ""
                            var desc = ""
                            while (!(p.next() == XmlPullParser.END_TAG && p.name == "programme")) {
                                if (p.eventType == XmlPullParser.START_TAG) {
                                    when (p.name) {
                                        "title" -> if (title.isEmpty()) title = p.nextText().trim()
                                        "desc" -> if (desc.isEmpty()) desc = p.nextText().trim().take(400)
                                    }
                                }
                                if (p.eventType == XmlPullParser.END_DOCUMENT) break
                            }
                            if (start != null && stop != null && stop > now && start < until && ch.isNotEmpty()) {
                                programmes.getOrPut(norm(ch)) { mutableListOf() }
                                    .add(Programme(start, stop, title.ifEmpty { "?" }, desc))
                            }
                        }
                    }
                }
                event = p.next()
            }
        }

        val sorted = programmes.mapValues { (_, v) -> v.sortedBy { it.start }.distinctBy { it.start } }
        val base = HashMap<String, List<Programme>>()
        for ((id, list) in sorted) {
            val b = baseOf(id)
            if (!base.containsKey(b)) base[b] = list
        }
        val byNameMap = HashMap<String, List<Programme>>()
        for ((n, id) in names) {
            if ((nameCount[n] ?: 0) == 1) sorted[norm(id)]?.let { byNameMap[n] = it }
        }
        byId = sorted
        byBase = base
        byName = byNameMap
    }
}
