package dev.matteomac81888.echo.ui.player.more.lyrics

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.matteomac81888.echo.databinding.ItemLyricSelectableBinding

class LyricSelectionAdapter(
    private val lines: List<String>,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<LyricSelectionAdapter.ViewHolder>() {

    var startIdx = -1
    var endIdx = -1

    fun getSelectedLines(): List<String> {
        if (startIdx == -1 || endIdx == -1) return emptyList()
        return lines.subList(startIdx, endIdx + 1)
    }

    inner class ViewHolder(val binding: ItemLyricSelectableBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener

                if (startIdx == -1) {
                    // Nessuna selezione, inizia da qui
                    startIdx = pos
                    endIdx = pos
                } else if (pos in startIdx..endIdx) {
                    // Cliccato su un elemento già selezionato
                    if (pos == startIdx) startIdx++
                    else if (pos == endIdx) endIdx--
                    else {
                        // Cliccato in mezzo, resetta la selezione su questo
                        startIdx = pos
                        endIdx = pos
                    }
                    if (startIdx > endIdx) {
                        startIdx = -1
                        endIdx = -1
                    }
                } else {
                    // Cliccato fuori. Controlla se è adiacente e se non supera i 5
                    if (pos == startIdx - 1 && endIdx - pos < 5) {
                        startIdx = pos
                    } else if (pos == endIdx + 1 && pos - startIdx < 5) {
                        endIdx = pos
                    } else {
                        // Non consecutivo o supera i 5, resetta la selezione
                        startIdx = pos
                        endIdx = pos
                    }
                }

                onSelectionChanged(if (startIdx == -1) 0 else (endIdx - startIdx) + 1)
                notifyDataSetChanged()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLyricSelectableBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.lyricText.text = lines[position]

        val isSelected = position in startIdx..endIdx
        // Colori per simulare la UI richiesta
        if (isSelected) {
            holder.binding.lyricText.setTextColor(Color.WHITE)
            holder.binding.lyricText.alpha = 1.0f
            holder.binding.lyricText.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
            holder.binding.lyricText.background.alpha = 50 // Sfondo semi-trasparente
        } else {
            holder.binding.lyricText.setTextColor(Color.WHITE)
            holder.binding.lyricText.alpha = 0.4f
            holder.binding.lyricText.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        }
    }

    override fun getItemCount() = lines.size
}