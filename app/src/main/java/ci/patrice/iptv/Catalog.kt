package ci.patrice.iptv

import java.text.Collator
import java.util.Locale

/** Une entrée du menu Explorer : libellé affiché + lien de playlist iptv-org. */
data class Source(val label: String, val url: String)

object Catalog {
    private const val BASE = "https://iptv-org.github.io/iptv"
    const val WORLD = "$BASE/index.m3u"
    const val INTERNATIONAL = "$BASE/countries/int.m3u"

    private val fr get() = Locale.getDefault().language == "fr"

    fun flag(code: String): String =
        code.uppercase().filter { it in 'A'..'Z' }.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    /** Tous les pays du monde, noms dans la langue du téléphone, pays de l'utilisateur en premier. */
    fun countries(internationalLabel: String): List<Source> {
        val loc = Locale.getDefault()
        val collator = Collator.getInstance(loc)
        val home = loc.country.uppercase()
        val all = Locale.getISOCountries().map { code ->
            val name = Locale("", code).getDisplayCountry(loc)
            val slug = if (code == "GB") "uk" else code.lowercase()
            code to Source("${flag(code)}  $name", "$BASE/countries/$slug.m3u")
        }.sortedWith(compareBy(collator) { it.second.label.substringAfter("  ") })
        val homeEntry = all.firstOrNull { it.first == home }?.second
        return listOfNotNull(homeEntry, Source(internationalLabel, INTERNATIONAL)) +
            all.map { it.second }.filter { it != homeEntry }
    }

    // ISO 639-3 (utilisé par iptv-org) -> ISO 639-1 (pour afficher le nom traduit)
    private val languageCodes = listOf(
        "fra" to "fr", "eng" to "en", "ara" to "ar", "spa" to "es", "por" to "pt", "deu" to "de",
        "ita" to "it", "rus" to "ru", "zho" to "zh", "hin" to "hi", "tur" to "tr", "nld" to "nl",
        "pol" to "pl", "ell" to "el", "ron" to "ro", "ukr" to "uk", "fas" to "fa", "urd" to "ur",
        "ben" to "bn", "tam" to "ta", "tel" to "te", "jpn" to "ja", "kor" to "ko", "vie" to "vi",
        "tha" to "th", "ind" to "id", "msa" to "ms", "heb" to "he", "swa" to "sw", "hau" to "ha",
        "yor" to "yo", "ibo" to "ig", "amh" to "am", "som" to "so", "wol" to "wo", "lin" to "ln",
        "bam" to "bm", "ful" to "ff", "kin" to "rw", "zul" to "zu", "mlg" to "mg", "srp" to "sr",
        "hrv" to "hr", "hun" to "hu", "ces" to "cs", "swe" to "sv", "nor" to "no", "dan" to "da",
        "fin" to "fi", "cat" to "ca", "aze" to "az", "kaz" to "kk", "uzb" to "uz", "pus" to "ps",
    )

    fun languages(): List<Source> {
        val loc = Locale.getDefault()
        val collator = Collator.getInstance(loc)
        return languageCodes.map { (iso3, iso2) ->
            val name = Locale(iso2).getDisplayLanguage(loc).replaceFirstChar { it.titlecase(loc) }
            Source(name, "$BASE/languages/$iso3.m3u")
        }.sortedWith(compareBy(collator) { it.label })
    }

    // id iptv-org, emoji, français, anglais
    private val categoryData = listOf(
        listOf("news", "📰", "Infos", "News"),
        listOf("sports", "⚽", "Sport", "Sports"),
        listOf("movies", "🎬", "Films", "Movies"),
        listOf("series", "📺", "Séries", "Series"),
        listOf("entertainment", "🎉", "Divertissement", "Entertainment"),
        listOf("music", "🎵", "Musique", "Music"),
        listOf("kids", "🧸", "Enfants", "Kids"),
        listOf("animation", "🐭", "Dessins animés", "Animation"),
        listOf("documentary", "🦁", "Documentaires", "Documentary"),
        listOf("education", "🎓", "Éducation", "Education"),
        listOf("science", "🔬", "Science", "Science"),
        listOf("religious", "🙏", "Religion", "Religious"),
        listOf("culture", "🎭", "Culture", "Culture"),
        listOf("lifestyle", "🌿", "Style de vie", "Lifestyle"),
        listOf("cooking", "🍲", "Cuisine", "Cooking"),
        listOf("travel", "✈️", "Voyage", "Travel"),
        listOf("outdoor", "🏕️", "Nature & plein air", "Outdoor"),
        listOf("business", "💼", "Économie", "Business"),
        listOf("comedy", "😂", "Humour", "Comedy"),
        listOf("family", "👨‍👩‍👧", "Famille", "Family"),
        listOf("classic", "🎞️", "Classiques", "Classic"),
        listOf("auto", "🚗", "Auto & moto", "Auto"),
        listOf("weather", "⛅", "Météo", "Weather"),
        listOf("legislative", "🏛️", "Politique & parlement", "Legislative"),
        listOf("relax", "🧘", "Détente", "Relax"),
        listOf("shop", "🛍️", "Téléachat", "Shopping"),
        listOf("general", "📡", "Généralistes", "General"),
    )

    fun categories(): List<Source> = categoryData.map { (id, emoji, frName, enName) ->
        Source("$emoji  ${if (fr) frName else enName}", "$BASE/categories/$id.m3u")
    }
}
