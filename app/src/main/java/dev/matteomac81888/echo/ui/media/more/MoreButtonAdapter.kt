package dev.matteomac81888.echo.ui.media.more

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import dev.matteomac81888.echo.databinding.ItemMoreButtonBinding
import dev.matteomac81888.echo.ui.common.GridAdapter
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder

class MoreButtonAdapter
    : ListAdapter<MoreButton, MoreButtonAdapter.ViewHolder>(MoreButton.DiffCallback), GridAdapter {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)
    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = 1

    class ViewHolder(
        parent: ViewGroup,
        val binding: ItemMoreButtonBinding = ItemMoreButtonBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {
        fun bind(item: MoreButton) = with(binding.root) {
            text = item.title
            setOnClickListener { item.onClick() }
            setIconResource(item.icon)
        }
    }
}