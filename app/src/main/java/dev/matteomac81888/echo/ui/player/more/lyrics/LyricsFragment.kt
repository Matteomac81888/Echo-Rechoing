package dev.matteomac81888.echo.ui.player.more.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.CONSUMED
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.LoadState.Error
import androidx.paging.LoadState.NotLoading
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.behavior.HideViewOnScrollBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.transition.MaterialSharedAxis
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.clients.LyricsSearchClient
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.matteomac81888.echo.databinding.FragmentPlayerLyricsBinding
import dev.matteomac81888.echo.databinding.ItemLyricsItemBinding
import dev.matteomac81888.echo.extensions.ExtensionUtils.isClient
import dev.matteomac81888.echo.ui.common.GridAdapter
import dev.matteomac81888.echo.ui.common.UiViewModel
import dev.matteomac81888.echo.ui.extensions.list.ExtensionsListBottomSheet
import dev.matteomac81888.echo.ui.feed.FeedLoadingAdapter
import dev.matteomac81888.echo.ui.feed.FeedLoadingAdapter.Companion.createListener
import dev.matteomac81888.echo.ui.player.PlayerColors.Companion.defaultPlayerColors
import dev.matteomac81888.echo.ui.player.PlayerViewModel
import dev.matteomac81888.echo.utils.ContextUtils.observe
import dev.matteomac81888.echo.utils.image.ImageUtils.loadAsCircle
import dev.matteomac81888.echo.utils.ui.AnimationUtils.setupTransition
import dev.matteomac81888.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.matteomac81888.echo.utils.ui.FastScrollerHelper
import dev.matteomac81888.echo.playback.MediaItemUtils.track
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class LyricsFragment : Fragment() {

    private var binding by autoCleared<FragmentPlayerLyricsBinding>()
    private val viewModel by activityViewModel<LyricsViewModel>()
    private val playerVM by activityViewModel<PlayerViewModel>()
    private val uiViewModel by activityViewModel<UiViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        binding = FragmentPlayerLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var currentLyricsPos = -1
    private var currentLyrics: Lyrics.Lyric? = null

    private var currentFlatList: List<Lyrics.Item> = emptyList()
    private var currentWordByWordList: List<List<Lyrics.Item>> = emptyList()
    private var currentWordByWord: Lyrics.WordByWord? = null

    private val lyricAdapter by lazy {
        LyricAdapter(uiViewModel) { adapter, lyric ->
            if (adapter.itemCount <= 1) return@LyricAdapter
            currentLyricsPos = -1
            playerVM.seekTo(lyric.startTime)
            updateProgress(lyric.startTime)
        }
    }

    private val karaokeAdapter by lazy {
        KaraokeLyricAdapter(uiViewModel) { firstWordOfLine ->
            currentLyricsPos = -1
            playerVM.seekTo(firstWordOfLine.startTime)
            updateProgress(firstWordOfLine.startTime)
        }
    }

    private val lyricsErrorAdapter by lazy {
        FeedLoadingAdapter(createListener {
            viewModel.reloadCurrent()
        }) { LyricAdapter.Loading(it) }
    }

    private var shouldAutoScroll = true
    val layoutManager by lazy {
        binding.lyricsRecyclerView.layoutManager as LinearLayoutManager
    }

    private fun updateProgress(current: Long) {
        when (viewModel.lyricsModeFlow.value) {
            LyricsMode.UNSYNCED -> { }
            LyricsMode.SYNCED   -> updateSyncedMode(current)
            LyricsMode.KARAOKE  -> updateKaraokeMode(current)
        }
    }

    private fun updateSyncedMode(current: Long) {
        val lyrics = currentLyrics as? Lyrics.Timed ?: return
        val currentTime = lyrics.list.getOrNull(currentLyricsPos)?.endTime ?: -1
        if (currentTime < current || current <= 0) {
            val currentIndex = lyrics.list.indexOfLast { it.startTime <= current }
            lyricAdapter.updateCurrent(currentIndex)
            if (!shouldAutoScroll) return
            binding.appBarLayout.setExpanded(false)
            slideDown()
            if (currentIndex < 0) return
            val smoothScroller = CenterSmoothScroller(requireContext())
            smoothScroller.targetPosition = currentIndex
            layoutManager.startSmoothScroll(smoothScroller)
        }
    }

    private var currentKaraokeLineIndex = -1

    // Anticipa lo scroll verticale di 300ms: quando la voce attacca la riga
    // è già centrata a schermo. Il rendering parola-per-parola avviene invece
    // nel Choreographer dell'adapter, leggendo currentPosition direttamente.
    private val KARAOKE_SCROLL_ANTICIPATION_MS = 300L

    private fun updateKaraokeMode(current: Long) {
        val wbw = currentWordByWord ?: return

        // Scroll verticale predittivo: usiamo il tempo anticipato solo per decidere
        // quale riga centrare. Il rendering delle parole è completamente indipendente.
        val anticipatedTime = current + KARAOKE_SCROLL_ANTICIPATION_MS
        val lineIndex = wbw.list.indexOfLast { line ->
            line.firstOrNull()?.startTime?.let { it <= anticipatedTime } == true
        }

        if (shouldAutoScroll && lineIndex >= 0 && lineIndex != currentKaraokeLineIndex) {
            currentKaraokeLineIndex = lineIndex
            binding.appBarLayout.setExpanded(false)
            slideDown()
            val smoothScroller = CenterSmoothScroller(requireContext())
            smoothScroller.targetPosition = lineIndex
            layoutManager.startSmoothScroll(smoothScroller)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view, false, axis = MaterialSharedAxis.Y)
        FastScrollerHelper.applyTo(binding.lyricsRecyclerView)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, _ -> CONSUMED }

        observe(uiViewModel.moreSheetState) {
            binding.root.keepScreenOn = it == BottomSheetBehavior.STATE_EXPANDED
        }

        binding.searchBarText.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_share_lyrics -> { shareLyrics(); true }
                R.id.menu_lyrics -> {
                    ExtensionsListBottomSheet.newInstance(ExtensionType.LYRICS)
                        .show(parentFragmentManager, null)
                    true
                }
                else -> false
            }
        }

        val menu = binding.searchBarText.menu
        val extMenu = binding.searchBarText.findViewById<View>(R.id.menu_lyrics)
        extMenu.setOnLongClickListener {
            val ext = viewModel.currentSelectionFlow.value ?: return@setOnLongClickListener false
            val all = viewModel.extensionsFlow.value
            val index = all.indexOf(ext)
            val nextIndex = (index + 1) % all.size
            if (nextIndex == index) return@setOnLongClickListener false
            viewModel.selectExtension(nextIndex)
            true
        }

        val lyricsItemAdapter = LyricsItemAdapter { lyrics ->
            viewModel.onLyricsSelected(lyrics)
            binding.searchView.hide()
        }
        GridAdapter.configureGridLayout(
            binding.searchRecyclerView,
            lyricsItemAdapter.withLoaders(this, viewModel),
        )

        observe(viewModel.currentSelectionFlow) { current ->
            binding.searchBarText.hint = current?.name
            current?.metadata?.icon.loadAsCircle(extMenu, R.drawable.ic_extension_32dp) {
                menu.findItem(R.id.menu_lyrics).icon = it
            }
            val isSearchable = current?.isClient<LyricsSearchClient>() ?: false
            binding.searchBarText.setNavigationIcon(
                if (isSearchable) R.drawable.ic_search_outline else R.drawable.ic_queue_music
            )
            binding.searchView.editText.isEnabled = isSearchable
            binding.searchView.hint =
                if (isSearchable) getString(R.string.search_x, current.name) else current?.name
        }

        binding.searchView.editText.setOnEditorActionListener { v, _, _ ->
            viewModel.queryFlow.value = v.text.toString().trim()
            true
        }

        observe(viewModel.queryFlow) {
            binding.searchView.editText.setText(it)
        }

        observe(viewModel.pagingFlow) {
            lyricsItemAdapter.submitData(it)
        }

        var job: Job? = null
        binding.lyricsRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) return
                shouldAutoScroll = false
                job?.cancel()
                job = lifecycleScope.launch {
                    delay(3500)
                    shouldAutoScroll = true
                }
            }
        })

        observe(uiViewModel.playerColors) {
            lyricAdapter.updateColors()
            karaokeAdapter.updateColors()
            val colors = it ?: requireContext().defaultPlayerColors()
            binding.noLyrics.setTextColor(colors.onBackground)
        }

        setupModeChips()

        binding.lyricsRecyclerView.itemAnimator = null
        observe(viewModel.lyricsState) { state ->
            binding.noLyrics.isVisible = state == LyricsViewModel.State.Empty
            lyricsErrorAdapter.loadState = when (state) {
                LyricsViewModel.State.Initial -> LoadState.Loading
                LyricsViewModel.State.Empty   -> NotLoading(true)
                LyricsViewModel.State.Loading -> LoadState.Loading
                is LyricsViewModel.State.Loaded -> state.result.fold(
                    { NotLoading(true) }, { Error(it) }
                )
            }

            val lyricsItem = (state as? LyricsViewModel.State.Loaded)?.result?.getOrNull()
            binding.lyricsItem.bind(lyricsItem)

            currentLyricsPos = -1
            currentKaraokeLineIndex = -1
            currentLyrics = lyricsItem?.lyrics
            currentWordByWord = null

            viewModel.applyDefaultModeForLyrics(currentLyrics)
            prepareLyricsData(currentLyrics)
        }

        observe(viewModel.richSyncFlow) { wbw ->
            binding.chipKaraoke.isEnabled = viewModel.canUseKaraoke(currentLyrics)
            if (wbw != null) {
                currentWordByWord = wbw
                currentWordByWordList = wbw.list
                if (viewModel.lyricsModeFlow.value == LyricsMode.SYNCED) {
                    viewModel.setMode(LyricsMode.KARAOKE)
                } else if (viewModel.lyricsModeFlow.value == LyricsMode.KARAOKE) {
                    karaokeAdapter.submitList(currentWordByWordList)
                }
            } else {
                if (viewModel.lyricsModeFlow.value == LyricsMode.KARAOKE && currentLyrics !is Lyrics.WordByWord) {
                    viewModel.setMode(LyricsMode.SYNCED)
                }
            }
        }

        // progress (100ms) usato solo per lo scroll verticale in modalità KARAOKE
        // e per il highlight di riga in modalità SYNCED.
        // Il rendering parola-per-parola in KARAOKE avviene nel Choreographer
        // dell'adapter, che legge currentPosition direttamente dal MediaController.
        observe(playerVM.progress) { updateProgress(it.first) }

        // Passa il MediaController direttamente all'adapter karaoke.
        // In questo modo il tempo viene letto a 60fps da currentPosition nativo,
        // identico per tutte le estensioni (non dipende da come ogni estensione
        // emette gli aggiornamenti di progresso).
        observe(playerVM.browser) { controller ->
            karaokeAdapter.mediaController = controller
        }

        observe(viewModel.lyricsModeFlow) { mode ->
            applyMode(mode)
            syncChipSelection(mode)
        }
    }

    private fun prepareLyricsData(lyrics: Lyrics.Lyric?) {
        currentFlatList = when (lyrics) {
            is Lyrics.Simple     -> listOf(Lyrics.Item(lyrics.text, 0, 0))
            is Lyrics.Timed      -> lyrics.list
            is Lyrics.WordByWord -> lyrics.list.flatten()
            null                 -> emptyList()
        }

        binding.chipKaraoke.isEnabled = viewModel.canUseKaraoke(lyrics)

        currentWordByWord = when (lyrics) {
            is Lyrics.WordByWord -> lyrics
            else                 -> null
        }
        currentWordByWordList = currentWordByWord?.list ?: emptyList()

        val track = playerVM.playerState.current.value?.mediaItem?.track
        viewModel.fetchRichSyncIfNeeded(lyrics, track)

        applyMode(viewModel.lyricsModeFlow.value)
    }

    private fun applyMode(mode: LyricsMode) {
        when (mode) {
            LyricsMode.UNSYNCED -> {
                lyricAdapter.mode = LyricsMode.UNSYNCED
                binding.lyricsRecyclerView.adapter = ConcatAdapter(lyricsErrorAdapter, lyricAdapter)
                lyricAdapter.submitList(currentFlatList)
                lyricAdapter.updateCurrent(-1)
            }
            LyricsMode.SYNCED -> {
                lyricAdapter.mode = LyricsMode.SYNCED
                binding.lyricsRecyclerView.adapter = ConcatAdapter(lyricsErrorAdapter, lyricAdapter)
                lyricAdapter.submitList(currentFlatList)
            }
            LyricsMode.KARAOKE -> {
                binding.lyricsRecyclerView.adapter = ConcatAdapter(lyricsErrorAdapter, karaokeAdapter)
                karaokeAdapter.submitList(currentWordByWordList)
            }
        }
    }

    private fun setupModeChips() {
        binding.chipUnsynced.setOnClickListener { viewModel.setMode(LyricsMode.UNSYNCED) }
        binding.chipSynced.setOnClickListener { viewModel.setMode(LyricsMode.SYNCED) }
        binding.chipKaraoke.setOnClickListener { viewModel.setMode(LyricsMode.KARAOKE) }
    }

    private fun syncChipSelection(mode: LyricsMode) {
        binding.chipUnsynced.isChecked = mode == LyricsMode.UNSYNCED
        binding.chipSynced.isChecked   = mode == LyricsMode.SYNCED
        binding.chipKaraoke.isChecked  = mode == LyricsMode.KARAOKE
    }

    private fun shareLyrics() {
        val lyrics = currentLyrics ?: return
        val track = playerVM.playerState.current.value?.mediaItem?.track ?: return

        val lines = when (lyrics) {
            is Lyrics.Simple     -> lyrics.text.split("\n").filter { it.isNotBlank() }
            is Lyrics.Timed      -> lyrics.list.map { it.text }.filter { it.isNotBlank() }
            is Lyrics.WordByWord -> lyrics.list.map { line ->
                line.joinToString(" ") { it.text }
            }.filter { it.isNotBlank() }
        }
        if (lines.isEmpty()) return
        LyricSelectionBottomSheet.newInstance(track, lines)
            .show(parentFragmentManager, "LyricSelection")
    }

    fun ItemLyricsItemBinding.bind(lyrics: dev.brahmkshatriya.echo.common.models.Lyrics?) =
        root.run {
            if (lyrics == null) { isVisible = false; return }
            isVisible = true
            setTitle(lyrics.title)
            setSubtitle(lyrics.subtitle)
            setBackgroundResource(R.color.amoled_bg)
        }

    class CenterSmoothScroller(context: Context) : LinearSmoothScroller(context) {
        override fun calculateDtToFit(
            viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int,
        ): Int {
            val midPoint = boxEnd / 2
            val targetMidPoint = ((viewEnd - viewStart) / 2) + viewStart
            return midPoint - targetMidPoint
        }
        override fun getVerticalSnapPreference() = SNAP_TO_START
        // 400ms: abbastanza fluido, arriva a destinazione prima che la voce parta
        override fun calculateTimeForDeceleration(dx: Int) = 400
    }

    @SuppressLint("WrongConstant")
    private fun slideDown() {
        val params = binding.lyricsItem.root.layoutParams as CoordinatorLayout.LayoutParams
        val behavior = params.behavior as HideViewOnScrollBehavior
        behavior.setViewEdge(1)
        behavior.slideOut(binding.lyricsItem.root)
    }
}