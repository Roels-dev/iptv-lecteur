package ci.patrice.iptv

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onClick: (Channel) -> Unit,
    private val onFavToggle: (Channel) -> Unit,
    private val isFav: (Channel) -> Boolean,
    private val isOffline: (Channel) -> Boolean,
    private val linkCount: (Channel) -> Int,
    private val epgLine: (Channel) -> String?,
    private val onLongClick: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    var items: List<Channel> = emptyList()
    var current: Channel? = null

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.logo)
        val name: TextView = v.findViewById(R.id.name)
        val group: TextView = v.findViewById(R.id.group)
        val fav: TextView = v.findViewById(R.id.fav)
        val epg: TextView = v.findViewById(R.id.epg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        val playing = c == current
        h.name.text = c.name
        val offline = isOffline(c) && !playing
        val ctx = h.itemView.context
        val n = linkCount(c)
        val groupText = if (n > 1) ctx.getString(R.string.links_count, c.group, n) else c.group
        h.group.text = if (offline) ctx.getString(R.string.offline_tag, groupText) else groupText
        h.name.setTextColor(if (playing) ACCENT else if (offline) MUTED else TEXT)
        h.itemView.alpha = if (offline) 0.55f else 1f
        h.itemView.isActivated = playing
        h.fav.setTextColor(if (isFav(c)) ACCENT else MUTED)
        h.logo.load(c.logo.ifBlank { null }) { crossfade(true) }
        val line = epgLine(c)
        h.epg.text = line.orEmpty()
        h.epg.visibility = if (line.isNullOrEmpty()) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { onClick(c) }
        h.itemView.setOnLongClickListener { onLongClick(c); true }
        h.fav.setOnClickListener { onFavToggle(c) }
    }

    companion object {
        private val ACCENT = Color.parseColor("#FF8A3D")
        private val TEXT = Color.parseColor("#E8EAED")
        private val MUTED = Color.parseColor("#4A4F59")
    }
}
