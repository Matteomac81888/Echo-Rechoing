package dev.matteomac81888.echo.ui.media

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.paging.LoadState
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.matteomac81888.echo.databinding.FragmentMediaDetailsBinding
import dev.matteomac81888.echo.ui.common.GridAdapter
import dev.matteomac81888.echo.ui.common.GridAdapter.Companion.configureGridLayout
import dev.matteomac81888.echo.ui.common.UiViewModel
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.configure
import dev.matteomac81888.echo.ui.feed.FeedAdapter.Companion.getFeedAdapter
import dev.matteomac81888.echo.ui.feed.FeedAdapter.Companion.getTouchHelper
import dev.matteomac81888.echo.ui.feed.FeedClickListener
import dev.matteomac81888.echo.ui.feed.FeedClickListener.Companion.getFeedListener
import dev.matteomac81888.echo.ui.feed.FeedViewModel
import dev.matteomac81888.echo.ui.media.MediaHeaderAdapter.Companion.getMediaHeaderListener
import dev.matteomac81888.echo.utils.ContextUtils.observe
import dev.matteomac81888.echo.utils.ui.FastScrollerHelper
import kotlinx.coroutines.flow.combine
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import org.koin.androidx.viewmodel.ext.android.viewModel

class MediaDetailsFragment : Fragment(R.layout.fragment_media_details) {

    interface Parent {
        val feedId: String
        val viewModel: MediaDetailsViewModel
        val fromPlayer: Boolean
    }

    val parent by lazy { requireParentFragment() as Parent }
    val viewModel by lazy { parent.viewModel }
    private val feedViewModel by lazy {
        requireParentFragment().viewModel<FeedViewModel>().value
    }

    private val trackFeedData by lazy {
        feedViewModel.getFeedData(
            "${parent.feedId}_tracks",
            Feed.Buttons(showPlayAndShuffle = true),
            true,
            viewModel.tracksLoadedFlow, viewModel.trackCachedFlow,
            cached = { viewModel.trackCachedFlow.value?.getOrThrow() },
            loader = { viewModel.tracksLoadedFlow.value?.getOrThrow() }
        )
    }

    private val feedData by lazy {
        feedViewModel.getFeedData(
            "${parent.feedId}_feed",
            Feed.Buttons(),
            false,
            viewModel.feedCachedFlow, viewModel.feedLoadedFlow,
            cached = { viewModel.feedCachedFlow.value?.getOrThrow() },
            loader = { viewModel.feedLoadedFlow.value?.getOrThrow() }
        )
    }

    private val mediaHeaderAdapter by lazy {
        MediaHeaderAdapter(
            requireParentFragment().getMediaHeaderListener(viewModel),
            parent.fromPlayer
        )
    }

    private val feedListener by lazy {
        if (!parent.fromPlayer) requireParentFragment().getFeedListener()
        else FeedClickListener(
            requireParentFragment(),
            requireActivity().supportFragmentManager,
            R.id.navHostFragment
        ) {
            val uiViewModel by activityViewModel<UiViewModel>()
            uiViewModel.collapsePlayer()
        }
    }

    private val trackAdapter by lazy {
        getFeedAdapter(trackFeedData, feedListener, true)
    }
    private val feedAdapter by lazy {
        getFeedAdapter(feedData, feedListener)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMediaDetailsBinding.bind(view)
        FastScrollerHelper.applyTo(binding.recyclerView)
        applyInsets(viewModel.uiResultFlow) {
            val item = viewModel.uiResultFlow.value?.getOrNull()?.item as? Playlist
            val bottom = if (item?.isEditable == true) 72 else 16
            binding.recyclerView.applyContentInsets(it, 20, 8, bottom)
        }
        val lineAdapter = LineAdapter()
        observe(trackFeedData.shouldShowEmpty) {
            lineAdapter.loadState = if (it) LoadState.Loading else LoadState.NotLoading(false)
        }
        observe(viewModel.uiResultFlow) { result ->
            mediaHeaderAdapter.result = result
        }
        getTouchHelper(feedListener).attachToRecyclerView(binding.recyclerView)
        configureGridLayout(
            binding.recyclerView,
            GridAdapter.Concat(
                mediaHeaderAdapter,
                trackAdapter.withLoading(this),
                lineAdapter,
                feedAdapter.withLoading(this)
            )
        )
        val loadingFlow = viewModel.isRefreshingFlow
            .combine(trackFeedData.isRefreshingFlow) { a, b -> a || b }
                .combine(feedData.isRefreshingFlow) { a, b -> a || b }
        binding.swipeRefresh.run {
            configure()
            setOnRefreshListener { viewModel.refresh() }
            observe(loadingFlow) {
                isRefreshing = it
            }
        }
    }
}