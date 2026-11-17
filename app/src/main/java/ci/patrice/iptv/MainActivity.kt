package ci.patrice.iptv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var controls: View
    private lateinit var nowPlaying: TextView
    private lateinit var status: TextView
    private lateinit var urlInput: EditText
    private lateinit var search: EditText
    private lateinit var groupSpinner: Spinner
    private lateinit var adapter: ChannelAdapter

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()
    private var groupOptions: List<String> = listOf(ALL)
    private var fullscreen = false

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("iptv", Context.MODE_PRIVATE) }
    private val favs = mutableSetOf<String>()
    private val offline = mutableSetOf<String>()
    private lateinit var http: DefaultHttpDataSource.Factory
    private var retried = false

    private val presets = listOf(
        "🇨🇮 Côte d'Ivoire" to "https://iptv-org.github.io/iptv/countries/ci.m3u",
        "🇸🇳 Sénégal" to "https://iptv-org.github.io/iptv/countries/sn.m3u",
        "🇫🇷 Chaînes en français" to "https://iptv-org.github.io/iptv/languages/fra.m3u",
        "📰 Infos (monde)" to "https://iptv-org.github.io/iptv/categories/news.m3u",
        "⚽ Sport (gratuit)" to "https://iptv-org.github.io/iptv/categories/sports.m3u",
        "🎵 Musique" to "https://iptv-org.github.io/iptv/categories/music.m3u",
    )

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) loadFromFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.player)
        controls = findViewById(R.id.controls)
        nowPlaying = findViewById(R.id.nowPlaying)
        status = findViewById(R.id.status)
        urlInput = findViewById(R.id.urlInput)
        search = findViewById(R.id.search)
        groupSpinner = findViewById(R.id.groupSpinner)

        favs += prefs.getStringSet("favs", emptySet()).orEmpty()
        offline += prefs.getStringSet("offline", emptySet()).orEmpty()

        adapter = ChannelAdapter(
            onClick = { play(it) },
            onFavToggle = { toggleFav(it) },
            isFav = { favs.contains(it.url) },
            isOffline = { offline.contains(it.url) },
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.loadBtn).setOnClickListener { loadFromUrl(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadFromUrl(urlInput.text.toString()); true } else false
        }
        findViewById<Button>(R.id.presetsBtn).setOnClickListener { showPresets() }
        search.doAfterTextChanged { refreshList() }
        groupSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshList()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        setGroups(emptyList())

        playerView.setFullscreenButtonClickListener { setFullscreen(!fullscreen, forceOrientation = true) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false, forceOrientation = true)
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        initPlayer()

        val last = prefs.getString("lastUrl", null)
        if (!last.isNullOrBlank()) loadFromUrl(last) else showPresets()
    }

    // ---------- Lecteur ----------

    private fun initPlayer() {
        http = DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> setStatus("Chargement du flux…")
                    Player.STATE_READY -> {
                        setStatus("En direct")
                        retried = false
                        adapter.current?.let { markOffline(it, false) }
                    }
                    else -> {}
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val c = adapter.current
                // Flux en direct « en retard » ou coupure passagère : on relance une fois automatiquement.
                if (!retried && (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)) {
                    retried = true
                    setStatus("Reconnexion…")
                    player?.let { it.seekToDefaultPosition(); it.prepare(); it.play() }
                    return
                }
                val msg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "Chaîne hors ligne ou réservée à son pays d'origine."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Serveur de la chaîne injoignable (ou ta connexion)."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                        "Format de flux non pris en charge."
                    else -> "Chaîne indisponible pour le moment."
                }
                setStatus("$msg Essaie une autre chaîne.", true)
                c?.let { markOffline(it, true) }
            }
        })
        playerView.player = p
        player = p
    }

    private fun play(c: Channel) {
        val p = player ?: return
        retried = false
        http.setUserAgent(c.userAgent.ifBlank { DEFAULT_UA })
        http.setDefaultRequestProperties(
            if (c.referer.isNotBlank()) mapOf("Referer" to c.referer, "Origin" to originOf(c.referer))
            else emptyMap()
        )
        adapter.current = c
        adapter.notifyDataSetChanged()
        nowPlaying.text = c.name
        val lower = c.url.lowercase()
        val builder = MediaItem.Builder().setUri(c.url)
        val direct = listOf(".mp4", ".mkv", ".ts", ".webm", ".mp3", ".aac").any { lower.substringBefore('?').endsWith(it) }
        if (!direct) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(builder.build())
        p.prepare()
        p.playWhenReady = true
    }

    // ---------- Playlists ----------

    private fun showPresets() {
        val names = presets.map { it.first } + "📂 Importer un fichier M3U…"
        MaterialAlertDialogBuilder(this)
            .setTitle("Playlists gratuites et légales")
            .setItems(names.toTypedArray()) { _, i ->
                if (i < presets.size) loadFromUrl(presets[i].second)
                else pickFile.launch(arrayOf("*/*"))
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun loadFromUrl(input: String) {
        val url = input.trim()
        if (url.isEmpty()) return
        urlInput.setText(url)
        setStatus("Chargement de la playlist…")
        io.execute {
            try {
                val text = download(url)
                val list = M3uParser.parse(text)
                main.post {
                    prefs.edit().putString("lastUrl", url).apply()
                    useChannels(list, Uri.parse(url).host ?: "")
                }
            } catch (e: Exception) {
                main.post { setStatus("Impossible de charger la playlist : ${e.message}", true) }
            }
        }
    }

    private fun loadFromFile(uri: Uri) {
        setStatus("Lecture du fichier…")
        io.execute {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val list = M3uParser.parse(text)
                main.post { useChannels(list, "fichier importé") }
            } catch (e: Exception) {
                main.post { setStatus("Fichier illisible : ${e.message}", true) }
            }
        }
    }

    private fun download(start: String): String {
        var url = start
        repeat(5) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) IPTVLecteur/1.0")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw Exception("redirection invalide")
                url = URL(URL(url), loc).toString()
                conn.disconnect()
                return@repeat
            }
            if (code !in 200..299) throw Exception("erreur HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
        throw Exception("trop de redirections")
    }

    private fun useChannels(list: List<Channel>, label: String) {
        channels = list
        setGroups(list.flatMap { it.groups }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER))
        refreshList()
        if (list.isEmpty()) setStatus("Aucune chaîne trouvée dans cette playlist.", true)
        else setStatus("${list.size} chaînes chargées – $label")
    }

    private fun setGroups(groups: List<String>) {
        groupOptions = listOf(ALL, FAVS) + groups
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, groupOptions)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        groupSpinner.adapter = a
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshList() {
        val q = search.text.toString().trim().lowercase()
        val g = groupOptions.getOrNull(groupSpinner.selectedItemPosition) ?: ALL
        adapter.items = channels.filter { c ->
            (q.isEmpty() || c.name.lowercase().contains(q)) &&
                when (g) {
                    ALL -> true
                    FAVS -> favs.contains(c.url)
                    else -> c.groups.contains(g)
                }
        }.sortedBy { offline.contains(it.url) }
        adapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun toggleFav(c: Channel) {
        if (!favs.remove(c.url)) favs.add(c.url)
        prefs.edit().putStringSet("favs", HashSet(favs)).apply()
        if (groupOptions.getOrNull(groupSpinner.selectedItemPosition) == FAVS) refreshList()
        else adapter.notifyDataSetChanged()
    }

    private fun originOf(url: String): String {
        val u = Uri.parse(url)
        return if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else url
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun markOffline(c: Channel, isOff: Boolean) {
        val changed = if (isOff) offline.add(c.url) else offline.remove(c.url)
        if (changed) {
            prefs.edit().putStringSet("offline", HashSet(offline)).apply()
            adapter.notifyDataSetChanged()
        }
    }

    private fun setStatus(msg: String, error: Boolean = false) {
        status.text = msg
        status.setTextColor(if (error) 0xFFFF6B6B.toInt() else 0xFF8B919C.toInt())
    }

    // ---------- Plein écran ----------

    private fun setFullscreen(on: Boolean, forceOrientation: Boolean) {
        fullscreen = on
        controls.visibility = if (on) View.GONE else View.VISIBLE
        val lp = playerView.layoutParams
        lp.height = if (on) ViewGroup.LayoutParams.MATCH_PARENT else (220 * resources.displayMetrics.density).toInt()
        playerView.layoutParams = lp

        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        if (forceOrientation) {
            requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE && !fullscreen) {
            setFullscreen(true, forceOrientation = false)
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && fullscreen) {
            setFullscreen(false, forceOrientation = false)
        }
    }

    // ---------- Cycle de vie ----------

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onStart() {
        super.onStart()
        if (adapter.current != null) player?.play()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        io.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val ALL = "Toutes les catégories"
        private const val FAVS = "★ Favoris"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
