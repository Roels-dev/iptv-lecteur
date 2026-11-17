package ci.patrice.iptv

data class Channel(
    val name: String,
    val logo: String,
    val group: String,
    val url: String,
) {
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
        var hasMeta = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF")) {
                val title = line.substringAfterLast(',', "").trim()
                name = title.ifEmpty { attr(line, "tvg-name") }.ifEmpty { "Sans nom" }
                logo = attr(line, "tvg-logo")
                group = attr(line, "group-title").ifEmpty { "Autres" }
                hasMeta = true
            } else if (!line.startsWith("#")) {
                out += if (hasMeta) Channel(name, logo, group, line)
                else Channel(line, "", "Autres", line)
                hasMeta = false
            }
        }
        return out
    }
}
