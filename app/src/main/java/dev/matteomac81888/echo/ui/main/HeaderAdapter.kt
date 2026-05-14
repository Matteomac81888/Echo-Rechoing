package dev.matteomac81888.echo.ui.main

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.google.android.material.color.MaterialColors
import dev.matteomac81888.echo.R
import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.User
import dev.matteomac81888.echo.databinding.ItemMainHeaderBinding
import dev.matteomac81888.echo.extensions.db.models.UserEntity.Companion.toEntity
import dev.matteomac81888.echo.ui.common.GridAdapter
import dev.matteomac81888.echo.ui.extensions.list.ExtensionsListBottomSheet
import dev.matteomac81888.echo.ui.extensions.login.LoginUserListViewModel
import dev.matteomac81888.echo.ui.settings.SettingsBottomSheet
import dev.matteomac81888.echo.utils.ContextUtils.observe
import dev.matteomac81888.echo.utils.image.ImageUtils.loadAsCircle
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimRecyclerAdapter
import dev.matteomac81888.echo.utils.ui.scrolling.ScrollAnimViewHolder
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class HeaderAdapter(
    private val fragment: Fragment,
) : ScrollAnimRecyclerAdapter<HeaderAdapter.ViewHolder>(), GridAdapter {
    private val viewModel by fragment.activityViewModel<LoginUserListViewModel>()

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val holder = ViewHolder(parent)
        val binding = holder.binding
        val parentFragmentManager = fragment.parentFragmentManager
        binding.extensionsCont.setOnClickListener {
            ExtensionsListBottomSheet.newInstance(ExtensionType.MUSIC)
                .show(parentFragmentManager, null)
        }

        binding.accountsCont.setOnClickListener {
            SettingsBottomSheet().show(parentFragmentManager, null)
        }
        return holder
    }

    private var extension: MusicExtension? = null
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    private var triple: Triple<Extension<*>?, Boolean, List<Pair<User, Boolean>>> =
        Triple(null, false, emptyList())
        set(value) {
            field = value
            notifyItemChanged(0)
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = with(holder.binding) {
        super.onBindViewHolder(holder, position)
        val context = root.context
        title.text = extension?.name ?: context.getString(R.string.app_name)
        extensions.loadBigIcon(extension?.metadata?.icon, R.drawable.ic_extension_32dp)
        extensionsCont.setLoopedLongClick(
            viewModel.extensionLoader.music.value.filter { it.isEnabled },
            { viewModel.extensionLoader.current.value }
        ) {
            viewModel.extensionLoader.setupMusicExtension(it, true)
        }

        val (ext, isLoginClient, all) = triple
        accounts.loadBigIcon(
            viewModel.currentUser.value?.cover,
            if (isLoginClient) R.drawable.ic_account_circle_32dp else R.drawable.ic_settings_outline_32dp
        )
        accountsCont.setLoopedLongClick(all, { all.find { it.second } }) {
            ext ?: return@setLoopedLongClick
            viewModel.setLoginUser(it.first.toEntity(ext.type, ext.id))
        }
    }

    class ViewHolder(
        parent: ViewGroup,
        val binding: ItemMainHeaderBinding = ItemMainHeaderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        ),
    ) : ScrollAnimViewHolder(binding.root)

    init {
        with(fragment) {

            observe(viewModel.extensionLoader.current) {
                viewModel.currentExtension.value = it
                extension = it
            }
            observe(viewModel.allUsersWithClient) {
                triple = it
            }
        }
    }

    companion object {
        fun <T> View.setLoopedLongClick(
            list: List<T>,
            getCurrent: (View) -> T?,
            onSelect: (T) -> Unit,
        ) {
            setOnLongClickListener {
                val current = getCurrent(this)
                val index = list.indexOf(current)
                if (index == -1) return@setOnLongClickListener false
                val next = list[(index + 1) % list.size]
                if (next == current) return@setOnLongClickListener false
                onSelect(next)
                true
            }
        }

        fun ImageView.loadBigIcon(image: ImageHolder?, placeholder: Int) {
            val color = ColorStateList.valueOf(
                MaterialColors.getColor(
                    this,
                    androidx.appcompat.R.attr.colorControlNormal
                )
            )
            image.loadAsCircle(this) {
                if (it == null) {
                    imageTintList = color
                    setImageResource(placeholder)
                } else {
                    imageTintList = null
                    setImageDrawable(it)
                }
            }
        }
    }
}