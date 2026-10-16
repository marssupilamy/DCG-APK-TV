package com.dcg.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.dcg.tv.R

private fun RecyclerView.ViewHolder.withPosition(block: (Int) -> Unit) {
    val pos = bindingAdapterPosition
    if (pos != RecyclerView.NO_POSITION) block(pos)
}

/** Lista simples de textos: categorias, temporadas e episódios. */
class TextListAdapter(
    @LayoutRes private val layout: Int,
    private val onClick: (Int) -> Unit,
    private val onFocus: ((Int) -> Unit)? = null,
) : RecyclerView.Adapter<TextListAdapter.Holder>() {

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    private var items: List<String> = emptyList()
    private var selectedIndex = -1

    fun submit(list: List<String>, selected: Int = -1) {
        items = list
        selectedIndex = selected
        notifyDataSetChanged()
    }

    /** Troca os textos sem recriar as linhas, para o foco do controle não se perder. */
    fun refresh(list: List<String>) {
        if (list.size != items.size) return submit(list, selectedIndex)
        items = list
        notifyItemRangeChanged(0, list.size)
    }

    fun select(index: Int) {
        val old = selectedIndex
        selectedIndex = index
        if (old in items.indices) notifyItemChanged(old)
        if (index in items.indices) notifyItemChanged(index)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false) as TextView
        val holder = Holder(view)
        view.setOnClickListener { holder.withPosition(onClick) }
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocus?.let { holder.withPosition(it) }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.text.text = items[position]
        holder.text.isSelected = position == selectedIndex
    }
}

data class GridItem(val title: String, val image: String?)

/** Grade de canais (logo ao lado do nome) ou de capas (filmes e séries). */
class GridAdapter(
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onFocus: (Int) -> Unit = {},
) : RecyclerView.Adapter<GridAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.image)
        val title: TextView = view.findViewById(R.id.title)
    }

    private var items: List<GridItem> = emptyList()
    private var poster = false

    fun submit(list: List<GridItem>, poster: Boolean) {
        items = list
        this.poster = poster
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = if (poster) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == 1) R.layout.item_poster else R.layout.item_channel
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        val holder = Holder(view)
        view.setOnClickListener { holder.withPosition(onClick) }
        // Segurar o OK do controle gera um clique longo.
        view.setOnLongClickListener {
            holder.withPosition(onLongClick)
            true
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_MENU && event.action == android.view.KeyEvent.ACTION_UP) {
                holder.withPosition(onLongClick)
                true
            } else {
                false
            }
        }
        view.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.06f else 1f
            v.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            if (hasFocus) holder.withPosition(onFocus)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.image.load(item.image) {
            crossfade(false)
            placeholder(R.drawable.ic_placeholder)
            error(R.drawable.ic_placeholder)
            fallback(R.drawable.ic_placeholder)
        }
    }
}
