package dev.matteomac81888.echo.ui.player.more.lyrics

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dev.matteomac81888.echo.databinding.ItemColorGradientBinding

class ColorGradientAdapter(
    // Passiamo solo i colori (start, end)
    private val colorPairs: List<Pair<Int, Int>>,
    private val onColorSelected: (Pair<Int, Int>) -> Unit
) : RecyclerView.Adapter<ColorGradientAdapter.ViewHolder>() {

    private var selectedPosition = 0

    inner class ViewHolder(val binding: ItemColorGradientBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                if (bindingAdapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                val oldPosition = selectedPosition
                selectedPosition = bindingAdapterPosition
                notifyItemChanged(oldPosition)
                notifyItemChanged(selectedPosition)
                onColorSelected(colorPairs[selectedPosition])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemColorGradientBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val colors = colorPairs[position]

        // Crea il gradiente per il bottoncino
        val gradient = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colors.first, colors.second)
        )

        holder.binding.colorView.background = gradient
        holder.binding.selectedIndicator.isVisible = position == selectedPosition

        // Evidenzia la card se selezionata (bordo)
        if (position == selectedPosition) {
            holder.binding.cardView.strokeWidth = 6
            holder.binding.cardView.strokeColor = Color.WHITE
        } else {
            holder.binding.cardView.strokeWidth = 0
        }
    }

    override fun getItemCount(): Int = colorPairs.size
}