package com.streambox.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/** Small generic list adapter with D-pad friendly "selected" highlighting. */
class SimpleAdapter<T>(
    private val layout: Int,
    private val bind: (view: View, item: T, position: Int) -> Unit,
    private val onClick: (item: T, position: Int) -> Unit = { _, _ -> },
    private val onLongClick: ((item: T, position: Int) -> Boolean)? = null
) : RecyclerView.Adapter<SimpleAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v)

    var items: List<T> = emptyList()
        private set
    var selected: Int = -1
        private set

    fun submit(list: List<T>, selectedIndex: Int = -1) {
        items = list
        selected = selectedIndex
        notifyDataSetChanged()
    }

    fun select(position: Int) {
        val old = selected
        selected = position
        if (old in items.indices) notifyItemChanged(old, PAYLOAD)
        if (position in items.indices) notifyItemChanged(position, PAYLOAD)
    }

    /** Re-binds one row (e.g. after a favourite toggle) without stealing focus. */
    fun refresh(position: Int) {
        if (position in items.indices) notifyItemChanged(position, REBIND)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(layout, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.itemView.isActivated = position == selected
        bind(holder.itemView, item, position)
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p in items.indices) onClick(items[p], p)
        }
        if (onLongClick != null) {
            holder.itemView.setOnLongClickListener {
                val p = holder.bindingAdapterPosition
                if (p in items.indices) onLongClick.invoke(items[p], p) else false
            }
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        holder.itemView.isActivated = position == selected
        if (payloads.contains(REBIND)) bind(holder.itemView, items[position], position)
    }

    companion object {
        private const val PAYLOAD = "sel"
        private const val REBIND = "rebind"
    }
}
