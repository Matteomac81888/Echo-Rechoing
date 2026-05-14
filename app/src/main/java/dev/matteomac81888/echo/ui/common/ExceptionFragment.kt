package dev.matteomac81888.echo.ui.common

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.matteomac81888.echo.R
import dev.matteomac81888.echo.databinding.FragmentExceptionBinding
import dev.matteomac81888.echo.ui.common.ExceptionUtils.getPasteLink
import dev.matteomac81888.echo.ui.common.SnackBarHandler.Companion.createSnack
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyBackPressCallback
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyContentInsets
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyFabInsets
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.applyInsets
import dev.matteomac81888.echo.utils.ContextUtils.copyToClipboard
import dev.matteomac81888.echo.utils.Serializer.getSerialized
import dev.matteomac81888.echo.utils.Serializer.putSerialized
import dev.matteomac81888.echo.utils.ui.AnimationUtils.setupTransition
import dev.matteomac81888.echo.utils.ui.AutoClearedValue.Companion.autoCleared
import dev.matteomac81888.echo.utils.ui.UiUtils.configureAppBar
import kotlinx.coroutines.launch

class ExceptionFragment : Fragment() {
    companion object {
        fun getBundle(data: ExceptionUtils.Data) = Bundle().apply {
            putSerialized("data", data)
        }
    }

    private val data by lazy {
        requireArguments().getSerialized<ExceptionUtils.Data>("data")!!.getOrThrow()
    }

    private var binding by autoCleared<FragmentExceptionBinding>()
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentExceptionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupTransition(view)
        applyInsets {
            binding.nestedScrollView.applyContentInsets(it)
            binding.fabContainer.applyFabInsets(it, systemInsets.value)
        }
        applyBackPressCallback()
        binding.appBarLayout.configureAppBar { offset ->
            binding.toolbarOutline.alpha = offset
            binding.exceptionIconContainer.alpha = 1 - offset
        }

        binding.exceptionMessage.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.exceptionMessage.title = data.title
        binding.exceptionDetails.text = data.trace
        binding.fabCopy.setOnClickListener {
            copyException()
        }

        requireActivity().run {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@run
            registerScreenCaptureCallback(mainExecutor, screenCaptureCallback)
        }
    }

    private fun copyException() {
        createSnack(R.string.copying_the_error)
        lifecycleScope.launch {
            val toCopy = getPasteLink(data).getOrElse { data.trace }
            requireContext().copyToClipboard("Error", toCopy)
        }
    }

    private val screenCaptureCallback by lazy {
        Activity.ScreenCaptureCallback {
            copyException()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        requireActivity().run {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@run
            unregisterScreenCaptureCallback(screenCaptureCallback)
        }
    }
}