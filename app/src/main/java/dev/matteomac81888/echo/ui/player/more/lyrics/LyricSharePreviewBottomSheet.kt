

package dev.matteomac81888.echo.ui.player.more.lyrics

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.brahmkshatriya.echo.common.models.Track
import dev.matteomac81888.echo.databinding.DialogLyricSharePreviewBinding
import dev.matteomac81888.echo.databinding.LayoutLyricShareCardBinding
import dev.matteomac81888.echo.ui.common.SnackBarHandler.Companion.createSnack
// IMPORT FONDAMENTALI PER I BUNDLE
import dev.matteomac81888.echo.utils.Serializer.getSerialized
import dev.matteomac81888.echo.utils.Serializer.putSerialized
import dev.matteomac81888.echo.utils.image.ImageUtils.loadDrawable
import dev.matteomac81888.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
class LyricSharePreviewBottomSheet : BottomSheetDialogFragment() {

    private var binding by autoCleared<DialogLyricSharePreviewBinding>()

    private val track by lazy { requireArguments().getSerialized<Track>("track")!!.getOrThrow() }
    private val lyrics by lazy { requireArguments().getString("lyrics")!! }

    private var currentBitmap: Bitmap? = null
    private lateinit var colorPairs: List<Pair<Int, Int>>
    private lateinit var selectedColors: Pair<Int, Int>

    companion object {
        fun newInstance(track: Track, selectedLines: List<String>) = LyricSharePreviewBottomSheet().apply {
            arguments = Bundle().apply {
                putSerialized("track", track)
                putString("lyrics", selectedLines.joinToString("\n"))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = DialogLyricSharePreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { dismiss() }

        setupColors()
        setupColorPicker()
        updatePreview(colorPairs.first()) // Inizializza con il primo colore

        binding.btnShare.setOnClickListener { currentBitmap?.let { shareBitmap(it) } }
        binding.btnSave.setOnClickListener { currentBitmap?.let { saveBitmapToGallery(it) } }
    }

    private fun setupColors() {
        colorPairs = listOf(
            0xFF8B2525.toInt() to 0xFF501212.toInt(), // Rosso
            0xFF255B8B.toInt() to 0xFF122C50.toInt(), // Blu
            0xFF258B4C.toInt() to 0xFF125026.toInt(), // Verde
            0xFF8B4D25.toInt() to 0xFF502C12.toInt(), // Arancione
            0xFF6B258B.toInt() to 0xFF3D1250.toInt(), // Viola
            0xFF333333.toInt() to 0xFF000000.toInt()  // Nero/Grigio
        )
    }

    private fun setupColorPicker() {
        binding.colorRecyclerView.layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        binding.colorRecyclerView.adapter = ColorGradientAdapter(colorPairs) { colors ->
            updatePreview(colors)
        }
    }

    private fun updatePreview(colors: Pair<Int, Int>) {
        selectedColors = colors
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            binding.previewImageView.visibility = View.INVISIBLE
            currentBitmap = createBitmapFromLayout(lyrics, colors)
            binding.previewImageView.setImageBitmap(currentBitmap)
            binding.progressBar.visibility = View.GONE
            binding.previewImageView.visibility = View.VISIBLE
        }
    }

    private suspend fun createBitmapFromLayout(lyricsText: String, colors: Pair<Int, Int>): Bitmap? = withContext(Dispatchers.Main) {
        val cardBinding = LayoutLyricShareCardBinding.inflate(layoutInflater)

        // Creiamo un gradiente con i BORDI ARROTONDATI (64px) per l'immagine finale
        val backgroundDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colors.first, colors.second)
        ).apply {
            cornerRadius = 64f // ARROTONDAMENTO DELLA CARD PNG!
        }

        cardBinding.cardContainer.background = backgroundDrawable

        cardBinding.songTitle.text = track.title
        cardBinding.songArtist.text = track.artists.joinToString(", ") { it.name }
        cardBinding.lyricsText.text = lyricsText

        val drawable = track.cover.loadDrawable(requireContext())
        if (drawable != null) {
            cardBinding.songCover.setImageDrawable(drawable)
        } else {
            cardBinding.songCover.setBackgroundColor(android.graphics.Color.DKGRAY)
        }

        // Misura il layout offline
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        cardBinding.root.measure(widthSpec, heightSpec)
        val width = cardBinding.root.measuredWidth
        val height = cardBinding.root.measuredHeight

        if (width <= 0 || height <= 0) return@withContext null
        cardBinding.root.layout(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        cardBinding.root.draw(canvas)

        bitmap
    }

    private fun shareBitmap(bitmap: Bitmap) {
        lifecycleScope.launch {
            val uri = saveBitmapToCache(bitmap, "echo_lyric_share_temp.png")
            if (uri != null) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(intent, "Condividi con..."))
            } else {
                createSnack("Errore durante la creazione del link di condivisione.")
            }
        }
    }

    private suspend fun saveBitmapToCache(bitmap: Bitmap, filename: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val cachePath = File(requireContext().cacheDir, "apks")
            if (!cachePath.exists()) cachePath.mkdirs()

            val file = File(cachePath, filename)
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        lifecycleScope.launch(Dispatchers.IO) {
            val filename = "ECHO_Lyrics_${System.currentTimeMillis()}.png"
            var fos: OutputStream? = null

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = requireContext().contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Echo")
                    }
                    val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    fos = imageUri?.let { resolver.openOutputStream(it) }
                } else {
                    @Suppress("DEPRECATION")
                    val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + "/Echo")
                    if (!imagesDir.exists()) imagesDir.mkdirs()
                    val image = File(imagesDir, filename)
                    fos = FileOutputStream(image)
                }

                fos?.use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    withContext(Dispatchers.Main) {
                        createSnack("Immagine salvata nella galleria.")
                    }
                } ?: throw Exception("Impossibile ottenere l'output stream.")
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    createSnack("Errore durante il salvataggio dell'immagine.")
                }
            }
        }
    }
}