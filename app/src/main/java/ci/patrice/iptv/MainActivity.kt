package ci.patrice.iptv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
import java.text.Normalizer
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
    private var groupOptions: List<String> = emptyList()
    private var fullscreen = false

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("iptv", Context.MODE_PRIVATE) }
    private val favs = mutableSetOf<String>()
    private val offline = mutableSetOf<String>()
    private lateinit var http: DefaultHttpDataSource.Factory
    private var retried = false
    private val tried = mutableSetOf<String>()
    private lateinit var listView: RecyclerView
    private val isTv by lazy {
        (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
    private var linksByKey: Map<String, List<Channel>> = emptyMap()

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
            onClick = {
                play(it)
                if (isTv) setFullscreen(true, forceOrientation = false)
            },
            onFavToggle = { toggleFav(it) },
            isFav = { favs.contains(it.url) },
            isOffline = { offline.contains(it.url) },
            linkCount = { linksByKey[it.key]?.size ?: 1 },
        )
        listView = findViewById(R.id.list)
        listView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.loadBtn).setOnClickListener { loadFromUrl(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadFromUrl(urlInput.text.toString()); true } else false
        }
        findViewById<Button>(R.id.presetsBtn).setOnClickListener { showExplore() }
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
        setFullscreen(false, forceOrientation = false)
        if (isTv) findViewById<View>(R.id.presetsBtn).requestFocus()

        val last = prefs.getString("lastUrl", null)
        when {
            last.isNullOrBlank() -> showExplore()
            last.startsWith("builtin:") -> loadBuiltin(last)
            else -> loadFromUrl(last, prefs.getString("lastLabel", null))
        }
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
                    Player.STATE_BUFFERING -> setStatus(getString(R.string.st_buffering))
                    Player.STATE_READY -> {
                        setStatus(getString(R.string.st_live))
                        retried = false
                        adapter.current?.let { markOffline(it, false) }
                    }
                    else -> {}
                }
            }
            // Écran toujours allumé pendant la lecture, veille normale en pause / à l'arrêt.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }

            override fun onPlayerError(error: PlaybackException) {
                val c = adapter.current
                // Flux en direct « en retard » ou coupure passagère : on relance une fois automatiquement.
                if (!retried && (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)) {
                    retried = true
                    setStatus(getString(R.string.st_reconnecting))
                    player?.let { it.seekToDefaultPosition(); it.prepare(); it.play() }
                    return
                }
                val msg = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        getString(R.string.err_http)
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        getString(R.string.err_network)
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                        getString(R.string.err_format)
                    else -> getString(R.string.err_generic)
                }
                c?.let { markOffline(it, true) }
                // Lien suivant de la même chaîne, s'il y en a un qu'on n'a pas encore essayé.
                val links = c?.let { linksByKey[it.key] }.orEmpty()
                val next = links.filter { it.url !in tried }.sortedBy { offline.contains(it.url) }.firstOrNull()
                if (next != null) {
                    setStatus(getString(R.string.st_trying_alt, tried.size + 1, links.size))
                    play(next, fromUser = false)
                    return
                }
                setStatus(getString(R.string.try_another, msg), true)
            }
        })
        playerView.player = p
        player = p
    }

    private fun play(c: Channel, fromUser: Boolean = true) {
        val p = player ?: return
        retried = false
        if (fromUser) tried.clear()
        tried += c.url
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
        val path = lower.substringBefore('?')
        val direct = listOf(".mp4", ".mkv", ".ts", ".webm", ".mp3", ".aac").any { path.endsWith(it) }
        when {
            path.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            !direct -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        p.setMediaItem(builder.build())
        p.prepare()
        p.playWhenReady = true
    }

    // ---------- Playlists ----------

    private fun showExplore() {
        val names = listOf(
            getString(R.string.ex_world), getString(R.string.ex_sport), getString(R.string.ex_english),
            getString(R.string.ex_freetv),
            getString(R.string.ex_country), getString(R.string.ex_language),
            getString(R.string.ex_category), getString(R.string.ex_file),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.explore_title)
            .setItems(names.toTypedArray()) { _, i ->
                when (i) {
                    0 -> loadFromUrl(Catalog.WORLD, getString(R.string.whole_world))
                    1 -> loadBuiltin(BUILTIN_SPORT)
                    2 -> loadFromUrl(Catalog.ENGLISH, getString(R.string.label_english))
                    3 -> loadBuiltin(BUILTIN_FREETV)
                    4 -> pickSource(getString(R.string.pick_country), Catalog.countries(getString(R.string.international)))
                    5 -> pickSource(getString(R.string.pick_language), Catalog.languages())
                    6 -> pickSource(getString(R.string.pick_category), Catalog.categories())
                    else -> pickFile.launch(arrayOf("*/*"))
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Liste avec champ de recherche (pays, langues, thèmes). */
    private fun pickSource(title: String, sources: List<Source>) {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        val filter = EditText(this).apply {
            hint = getString(R.string.filter_hint)
            isSingleLine = true
        }
        val listView = ListView(this)
        box.addView(filter)
        box.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (380 * d).toInt()))
        var shown = sources
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.label }.toMutableList())
        listView.adapter = listAdapter
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton(R.string.close, null)
            .create()
        filter.doAfterTextChanged { t ->
            val q = normalize(t?.toString().orEmpty())
            shown = sources.filter { normalize(it.label).contains(q) }
            listAdapter.clear()
            listAdapter.addAll(shown.map { it.label })
        }
        listView.setOnItemClickListener { _, _, pos, _ ->
            val src = shown[pos]
            dialog.dismiss()
            loadFromUrl(src.url, src.label)
        }
        dialog.show()
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()

    /** Listes combinées : plusieurs sources gratuites fusionnées et filtrées. */
    private fun loadBuiltin(id: String) {
        val (parts, label) = when (id) {
            BUILTIN_SPORT -> listOf<Pair<String, (Channel) -> Boolean>>(
                Catalog.SPORTS to { _ -> true },
                Catalog.FREE_TV to { c -> Catalog.isOfficialHost(c) && Catalog.isSport(c) },
            ) to getString(R.string.label_sport)
            else -> listOf<Pair<String, (Channel) -> Boolean>>(
                Catalog.FREE_TV to { c -> Catalog.isOfficialHost(c) },
            ) to getString(R.string.label_freetv)
        }
        setStatus(getString(R.string.st_loading_playlist))
        io.execute {
            val all = mutableListOf<Channel>()
            var lastError: Exception? = null
            for ((url, keep) in parts) {
                try {
                    all += M3uParser.parse(download(url)).filter(keep)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            val unique = all.distinctBy { it.url }
            val error = lastError
            main.post {
                if (unique.isEmpty() && error != null) {
                    setStatus(getString(R.string.err_playlist, error.message ?: ""), true)
                } else {
                    prefs.edit().putString("lastUrl", id).putString("lastLabel", label).apply()
                    useChannels(unique, label)
                }
            }
        }
    }

    private fun loadFromUrl(input: String, label: String? = null) {
        val url = input.trim()
        if (url.isEmpty()) return
        urlInput.setText(url)
        setStatus(getString(R.string.st_loading_playlist))
        io.execute {
            try {
                val text = download(url)
                val list = M3uParser.parse(text)
                main.post {
                    prefs.edit().putString("lastUrl", url).putString("lastLabel", label).apply()
                    useChannels(list, label ?: Uri.parse(url).host ?: "")
                }
            } catch (e: Exception) {
                main.post { setStatus(getString(R.string.err_playlist, e.message ?: ""), true) }
            }
        }
    }

    private fun loadFromFile(uri: Uri) {
        setStatus(getString(R.string.st_reading_file))
        io.execute {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val list = M3uParser.parse(text)
                main.post { useChannels(list, getString(R.string.imported_file)) }
            } catch (e: Exception) {
                main.post { setStatus(getString(R.string.err_file, e.message ?: ""), true) }
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
            conn.setRequestProperty("User-Agent", DEFAULT_UA)
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw Exception("bad redirect")
                url = URL(URL(url), loc).toString()
                conn.disconnect()
                return@repeat
            }
            if (code !in 200..299) throw Exception("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
        throw Exception("too many redirects")
    }

    private fun useChannels(list: List<Channel>, label: String) {
        channels = list
        linksByKey = list.groupBy { it.key }
        setGroups(list.flatMap { it.groups }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER))
        refreshList()
        if (isTv && !fullscreen) focusCurrentRow()
        if (list.isEmpty()) setStatus(getString(R.string.st_empty), true)
        else setStatus(getString(R.string.st_loaded, list.size, label))
    }

    private fun setGroups(groups: List<String>) {
        groupOptions = listOf(getString(R.string.all_categories), getString(R.string.favorites)) + groups
        val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, groupOptions)
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        groupSpinner.adapter = a
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshList() {
        val q = search.text.toString().trim().lowercase()
        val pos = groupSpinner.selectedItemPosition
        val g = groupOptions.getOrNull(pos)
        adapter.items = channels.filter { c ->
            (q.isEmpty() || c.name.lowercase().contains(q)) &&
                when {
                    pos <= 0 || g == null -> true
                    pos == 1 -> favs.contains(c.url)
                    else -> c.groups.contains(g)
                }
        }.sortedBy { offline.contains(it.url) }
        adapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun toggleFav(c: Channel) {
        if (!favs.remove(c.url)) favs.add(c.url)
        prefs.edit().putStringSet("favs", HashSet(favs)).apply()
        if (groupSpinner.selectedItemPosition == 1) refreshList()
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
        if (fullscreen && error) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        status.setTextColor(if (error) 0xFFFF6B6B.toInt() else 0xFF8B919C.toInt())
    }

    // ---------- Plein écran ----------

    private fun setFullscreen(on: Boolean, forceOrientation: Boolean) {
        fullscreen = on
        controls.visibility = if (on) View.GONE else View.VISIBLE
        val lp = playerView.layoutParams
        lp.height = if (on) ViewGroup.LayoutParams.MATCH_PARENT else normalPlayerHeight()
        playerView.layoutParams = lp

        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        if (!on && isTv) focusCurrentRow()
        if (forceOrientation && !isTv) {
            requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun normalPlayerHeight(): Int {
        val dm = resources.displayMetrics
        return if (isTv) (dm.heightPixels * 0.45f).toInt() else (220 * dm.density).toInt()
    }

    /** Remet le focus télécommande sur la chaîne en cours (ou la première). */
    private fun focusCurrentRow() {
        val items = adapter.items
        if (items.isEmpty()) return
        val idx = items.indexOf(adapter.current).coerceAtLeast(0)
        listView.scrollToPosition(idx)
        listView.post { listView.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }
    }

    /** Zapping : chaîne précédente / suivante de la liste affichée. */
    private fun zap(step: Int) {
        val items = adapter.items
        if (items.isEmpty()) return
        val idx = items.indexOf(adapter.current)
        val next = items[if (idx < 0) 0 else (idx + step).mod(items.size)]
        play(next)
        Toast.makeText(this, next.name, Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullscreen && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(1); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isTv) return
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
        private const val BUILTIN_SPORT = "builtin:sport"
        private const val BUILTIN_FREETV = "builtin:freetv"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
