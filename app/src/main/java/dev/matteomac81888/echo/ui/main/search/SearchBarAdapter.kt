package dev.matteomac81888.echo.ui.main.search

import android.view.LayoutInflater
import android.view.ViewGroup
import com.google.android.material.search.SearchView
import dev.matteomac81888.echo.databinding.ItemSearchBarBinding
import dev.matteomac81888.echo.ui.common.GridAdapter
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder

class SearchBarAdapter(
    val viewModel: SearchViewModel,
    val searchView: SearchView,
) : ScrollAnimRecyclerAdapter<SearchBarAdapter.ViewHolder>(), GridAdapter {
    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(parent, viewModel, searchView)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        holder.bind()
    }

    class ViewHolder(
        parent: ViewGroup,
        val viewModel: SearchViewModel,
        val searchView: SearchView,
        private val binding: ItemSearchBarBinding = ItemSearchBarBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
    ) : ScrollAnimViewHolder(binding.root) {
        fun bind() {
            searchView.setupWithSearchBar(binding.root)
            binding.root.setText(viewModel.queryFlow.value.takeIf { it.isNotBlank() })
        }
    }
}