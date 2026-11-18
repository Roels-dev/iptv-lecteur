package ci.patrice.iptv

data class Channel(
    val name: String,
    val logo: String,
    val group: String,
    val url: String,
    val referer: String = "",
    val userAgent: String = "",
    val tvgId: String = "",
) {
    /** Clé commune aux différents liens d'une même chaîne (ex. RTI 1 en 1080p et en 720p). */
    val key: String by lazy {
        val id = tvgId.substringBefore('@').trim().lowercase()
        id.ifEmpty {
            name.replace(Regex("\\([^)]*\\)|\\[[^\\]]*\\]"), "").trim().lowercase()
        }
    }

    val groups: List<String> get() = group.split(';').map { it.trim() }.filter { it.isNotEmpty() }
}

object M3uParser {
    private fun attr(line: String, key: String): String =
        Regex("$key=\"([^\"]*)\"").find(line)?.groupValues?.get(1).orEmpty()

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name = ""
        var logo = ""
        var group = ""
        var referer = ""
        var userAgent = ""
        var tvgId = ""
        var hasMeta = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
                    val title = line.substringAfterLast(',', "").trim()
                    name = title.ifEmpty { attr(line, "tvg-name") }.ifEmpty { "Sans nom" }
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title").ifEmpty { "Autres" }
                    referer = attr(line, "http-referrer")
                    userAgent = attr(line, "http-user-agent")
                    tvgId = attr(line, "tvg-id")
                    hasMeta = true
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-referrer", "http-referer" -> referer = value
                        "http-user-agent" -> userAgent = value
                    }
                }
                line.startsWith("#") -> {}
                line.contains("youtube.com") || line.contains("youtu.be") -> {
                    // Liens YouTube : non lisibles par le lecteur, on les ignore.
                    hasMeta = false; referer = ""; userAgent = ""; tvgId = ""
                }
                else -> {
                    out += if (hasMeta) Channel(name, logo, group, line, referer, userAgent, tvgId)
                    else Channel(line, "", "Autres", line, referer, userAgent)
                    hasMeta = false
                    referer = ""
                    userAgent = ""
                    tvgId = ""
                }
            }
        }
        return out
    }
}
