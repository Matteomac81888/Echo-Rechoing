package dev.matteomac81888.echo.ui.player.more.lyrics

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.databinding.DialogLyricSelectionBinding
import dev.matteomac81888.echo.databinding.LayoutLyricShareCardBinding
import dev.matteomac81888.echo.utils.Serializer.getSerialized
import dev.matteomac81888.echo.utils.Serializer.putSerialized
import dev.matteomac81888.echo.utils.image.ImageUtils.loadDrawable
import dev.matteomac81888.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LyricSelectionBottomSheet : BottomSheetDialogFragment() {

    private var binding by autoCleared<DialogLyricSelectionBinding>()
    private lateinit var adapter: LyricSelectionAdapter

    private val track by lazy { requireArguments().getSerialized<Track>("track")!!.getOrThrow() }
    private val lyricsLines by lazy { requireArguments().getStringArrayList("lyrics")!!.toList() }

    companion object {
        fun newInstance(track: Track, lyricsLines: List<String>) = LyricSelectionBottomSheet().apply {
            arguments = Bundle().apply {
                putSerialized("track", track)
                putStringArrayList("lyrics", ArrayList(lyricsLines))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogLyricSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { dismiss() }

        adapter = LyricSelectionAdapter(lyricsLines) { selectedCount ->
            binding.toolbar.subtitle = "$selectedCount/5 versi selezionati"
            binding.btnContinue.isEnabled = selectedCount > 0
        }
        binding.recyclerView.adapter = adapter

        // Dentro LyricSelectionBottomSheet.kt -> onViewCreated()
        binding.btnContinue.setOnClickListener {
            // Invece di generare l'immagine qui...
            // Apriamo il nuovo BottomSheet per l'anteprima e la personalizzazione
            LyricSharePreviewBottomSheet.newInstance(track, adapter.getSelectedLines())
                .show(parentFragmentManager, "LyricSharePreview")

            // Chiudiamo il selettore attuale
            dismiss()
        }
    }

    private fun generateAndShareImage(lyrics: String) {
        lifecycleScope.launch {
            try {
                binding.btnContinue.text = "Creazione in corso..."
                binding.btnContinue.isEnabled = false

                val bitmap = createBitmapOffline(lyrics)
                if (bitmap != null) {
                    val uri = saveBitmapToCache(bitmap)
                    if (uri != null) {
                        shareImage(uri)
                        dismissAllowingStateLoss()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                binding.btnContinue.text = "Condividi"
                binding.btnContinue.isEnabled = true
            }
        }
    }

    private suspend fun createBitmapOffline(lyricsText: String): Bitmap? = withContext(Dispatchers.Main) {
        val cardBinding = dev.matteomac81888.echo.databinding.LayoutLyricShareCardBinding.inflate(layoutInflater)

        cardBinding.songTitle.text = track.title
        cardBinding.songArtist.text = track.artists.joinToString(", ") { it.name }
        cardBinding.lyricsText.text = lyricsText

        // Carica la copertina
        val drawable = track.cover.loadDrawable(requireContext())
        if (drawable != null) {
            cardBinding.songCover.setImageDrawable(drawable)
        } else {
            cardBinding.songCover.setBackgroundColor(android.graphics.Color.DKGRAY)
        }

        // Misura il layout
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        cardBinding.root.measure(widthSpec, heightSpec)

        val width = cardBinding.root.measuredWidth
        val height = cardBinding.root.measuredHeight

        // Evita il crash se l'altezza misurata è 0
        if (width <= 0 || height <= 0) return@withContext null

        cardBinding.root.layout(0, 0, width, height)

        // Genera l'immagine
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        cardBinding.root.draw(canvas)

        bitmap
    }

    private suspend fun saveBitmapToCache(bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        try {
            // Echo ha già configurato nel suo provider la cartella "apks" dentro la cache.
            // Usiamo questa per essere sicuri che Android permetta la condivisione esterna senza crash.
            val cachePath = File(requireContext().cacheDir, "apks")
            if (!cachePath.exists()) cachePath.mkdirs()

            val file = File(cachePath, "echo_lyric_share.png")
            val stream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.close()

            FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun shareImage(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Aggiungiamo i flag necessari per dare permessi ad altre app (WhatsApp, Instagram, ecc.)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(intent, "Condividi con..."))
    }
}
