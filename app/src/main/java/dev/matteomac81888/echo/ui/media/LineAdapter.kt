package dev.matteomac81888.echo.ui.media

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.LoadState
import dev.matteomac81888.echo.databinding.ItemLineBinding
import dev.matteomac81888.echo.ui.common.GridAdapter
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimLoadStateAdapter
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder

class LineAdapter : ScrollAnimLoadStateAdapter<LineAdapter.ViewHolder>(), GridAdapter {
    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState) = ViewHolder(parent)
    class ViewHolder(
        parent: ViewGroup,
        binding: ItemLineBinding = ItemLineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root)
}