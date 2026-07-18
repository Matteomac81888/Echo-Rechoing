package dev.matteomac81888.echo.ui.feed

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.matteomac81888.echo.databinding.FragmentGenericCollapsableBinding
import dev.matteomac81888.echo.databinding.FragmentRecyclerWithRefreshBinding
import dev.matteomac81888.echo.extensions.ExtensionUtils.getExtensionOrThrow
import dev.matteomac81888.echo.extensions.cache.Cached
import dev.matteomac81888.echo.extensions.cache.Cached.savingFeed
import dev.matteomac81888.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.configure
import dev.matteomac81888.echo.ui.extensions.login.LoginFragment.Companion.bind
import dev.matteomac81888.echo.ui.feed.FeedAdapter.Companion.getFeedAdapter
import dev.matteomac81888.echo.ui.feed.FeedAdapter.Companion.getTouchHelper
import dev.matteomac81888.echo.ui.feed.FeedClickListener.Companion.getFeedListener
import dev.matteomac81888.echo.ui.main.MainFragment.Companion.applyPlayerBg
import dev.matteomac81888.echo.utils.ContextUtils.observe
import dev.matteomac81888.echo.utils.ui.FastScrollerHelper
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.viewModel

class FeedFragment : Fragment(R.layout.fragment_generic_collapsable) {
    companion object {
        fun getBundle(title: String, subtitle: String?) = Bundle().apply {
            putString("title", title)
            putString("subtitle", subtitle)
        }
    }

    class VM : ViewModel() {
        var initialized = false
        var extensionId: String? = null
        var feedId: String? = null
        var feed: Feed<Shelf>? = null
    }

    private val activityVm by activityViewModels<VM>()
    private val vm by viewModels<VM>()

    private val feedData by lazy {
        val feedViewModel by viewModel<FeedViewModel>()
        if (!vm.initialized) {
            vm.initialized = true
            vm.extensionId = activityVm.extensionId
            vm.feedId = activityVm.feedId
            vm.feed = activityVm.feed
        }
        feedViewModel.getFeedData(
            vm.feedId ?: "",
            cached = {
                val extId = vm.extensionId!!
                val feed = Cached.getFeedShelf(app, extId, vm.feedId!!)
                FeedData.State(extId, null, feed.getOrThrow())
            }
        ) {
            val extension = music.getExtensionOrThrow(vm.extensionId)
            val feed = savingFeed(app, extension, vm.feedId!!, vm.feed!!)
            FeedData.State(extension.id, null, feed)
        }
    }

    private val title by lazy { arguments?.getString("title")!! }
    private val subtitle by lazy { arguments?.getString("subtitle") }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentGenericCollapsableBinding.bind(view)
        binding.bind(this, false)
        binding.extensionIcon.isVisible = false
        binding.toolBar.title = title
        binding.toolBar.subtitle = subtitle
        applyPlayerBg(view) {
            mainBgDrawable.combine(feedData.backgroundImageFlow) { a, b -> b ?: a }
        }
        if (savedInstanceState == null) childFragmentManager.commit {
            replace<Actual>(R.id.genericFragmentContainer, null, arguments)
        }
    }

    class Actual() : Fragment(R.layout.fragment_recycler_with_refresh) {
        private val feedData by lazy {
            val vm by requireParentFragment().viewModel<FeedViewModel>()
            vm.feedDataMap.values.first()
        }

        private val listener by lazy { requireParentFragment().getFeedListener() }
        private val feedAdapter by lazy {
            getFeedAdapter(feedData, listener)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            val binding = FragmentRecyclerWithRefreshBinding.bind(view)
            applyInsets {
                binding.recyclerView.applyContentInsets(it, 20, 8, 16)
            }
            FastScrollerHelper.applyTo(binding.recyclerView)
            configureGridLayout(
                binding.recyclerView,
                feedAdapter.withLoading(this)
            )
            getTouchHelper(listener).attachToRecyclerView(binding.recyclerView)
            binding.swipeRefresh.run {
                configure()
                setOnRefreshListener { feedData.refresh() }
                observe(feedData.isRefreshingFlow) {
                    isRefreshing = it
                }
            }
        }
    }
}