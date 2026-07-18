package dev.matteomac81888.echo

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color.TRANSPARENT
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.add
import androidx.fragment.app.commit
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.navigation.NavigationBarView
import dev.matteomac81888.echo.databinding.ActivityMainBinding
import dev.matteomac81888.echo.extensions.ExtensionLoader
import dev.matteomac81888.echo.ui.common.ExceptionUtils.setupExceptionHandler
import dev.matteomac81888.echo.ui.common.FragmentUtils.setupIntents
import dev.matteomac81888.echo.ui.common.SnackBarHandler.Companion.setupSnackBar
import dev.matteomac81888.echo.ui.common.UiViewModel
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.setupNavBarAndInsets
import dev.matteomac81888.echo.ui.common.UiViewModel.Companion.setupPlayerBehavior
import dev.matteomac81888.echo.ui.extensions.ExtensionsViewModel.Companion.configureExtensionsUpdater
import dev.matteomac81888.echo.ui.main.MainFragment
import dev.matteomac81888.echo.ui.player.PlayerFragment
import dev.matteomac81888.echo.ui.player.PlayerFragment.Companion.PLAYER_COLOR
import dev.matteomac81888.echo.utils.ContextUtils.getSettings
import dev.matteomac81888.echo.utils.PermsUtils.checkAppPermissions
import dev.matteomac81888.echo.utils.ui.UiUtils.isNightMode
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

open class MainActivity : AppCompatActivity() {

    class Back : MainActivity()

    val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val uiViewModel by viewModel<UiViewModel>()
    private val extensionLoader by inject<ExtensionLoader>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(getAppTheme())
        DynamicColors.applyToActivityIfAvailable(
            this, applyUiChanges(this, uiViewModel)
        )

        setContentView(binding.root)

        enableEdgeToEdge(
            SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            if (isNightMode()) SystemBarStyle.dark(TRANSPARENT)
            else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
        )

        setupNavBarAndInsets(uiViewModel, binding.root, binding.navView as NavigationBarView)
        setupPlayerBehavior(uiViewModel, binding.playerFragmentContainer)
        setupExceptionHandler(setupSnackBar(uiViewModel, binding.root))
        checkAppPermissions { extensionLoader.setPermGranted() }
        configureExtensionsUpdater()
        supportFragmentManager.commit {
            if (savedInstanceState != null) return@commit
            add<MainFragment>(R.id.navHostFragment, "main")
            add<PlayerFragment>(R.id.playerFragmentContainer, "player")
        }
        setupIntents(uiViewModel)
    }

    companion object {
        const val THEME_KEY = "theme"
        const val AMOLED_KEY = "amoled"
        const val BIG_COVER = "big_cover"
        const val CUSTOM_THEME_KEY = "custom_theme"
        const val COLOR_KEY = "color"
        const val LIQUID_GLASS_KEY = "liquid_glass"

        fun Context.getAppTheme(): Int {
            val settings = getSettings()
            val bigCover = settings.getBoolean(BIG_COVER, false)
            val amoled = settings.getBoolean(AMOLED_KEY, false)
            val liquidGlass = settings.getBoolean(LIQUID_GLASS_KEY, false)
            return when {
                amoled && bigCover && liquidGlass -> R.style.AmoledBigCoverLiquidGlass
                amoled && liquidGlass -> R.style.AmoledLiquidGlass
                bigCover && liquidGlass -> R.style.BigCoverLiquidGlass
                liquidGlass -> R.style.LiquidGlass
                amoled && bigCover -> R.style.AmoledBigCover
                amoled -> R.style.Amoled
                bigCover -> R.style.BigCover
                else -> R.style.Default
            }
        }

        fun Context.defaultColor() =
            ContextCompat.getColor(this, R.color.app_color)

        fun Context.isAmoled() = getSettings().getBoolean(AMOLED_KEY, false)

        fun applyUiChanges(context: Context, uiViewModel: UiViewModel): DynamicColorsOptions {
            val settings = context.getSettings()
            val mode = when (settings.getString(THEME_KEY, "system")) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(mode)

            val custom = settings.getBoolean(CUSTOM_THEME_KEY, true)
            val color = if (custom) settings.getInt(COLOR_KEY, context.defaultColor()) else null
            val playerColor = settings.getBoolean(PLAYER_COLOR, false)
            val customColor = uiViewModel.playerColors.value?.accent?.takeIf { playerColor }

            val builder = DynamicColorsOptions.Builder()
            (customColor ?: color)?.let { builder.setContentBasedSource(it) }
            return builder.build()
        }

        const val BACK_ANIM = "back_anim"

        /**
         * Restituisce l'Activity da usare (MainActivity o Back) in base alla preferenza.
         * Prima di restituirla, verifica che il componente sia davvero abilitato nel
         * PackageManager e, se non lo è (es. dopo un ripristino di backup, un crash a
         * metà toggle o una migrazione dati), lo allinea al volo. Questo evita che
         * un disallineamento tra la SharedPreference e lo stato reale del componente
         * causi un ActivityNotFoundException quando si tenta di avviarla.
         */
        fun Context.getMainActivity(): Class<out MainActivity> {
            val useBackAnim = getSettings().getBoolean(BACK_ANIM, false)
            val target = if (useBackAnim) Back::class.java else MainActivity::class.java
            val other = if (useBackAnim) MainActivity::class.java else Back::class.java
            ensureComponentEnabled(enable = target, disable = other)
            return target
        }

        private fun Context.ensureComponentEnabled(enable: Class<*>, disable: Class<*>) {
            val pm = packageManager
            val enabledSetting = pm.getComponentEnabledSetting(ComponentName(this, enable))
            val isAlreadyEnabled = enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (
                            enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT &&
                                    enable == MainActivity::class.java
                            )
            if (isAlreadyEnabled) return

            pm.setComponentEnabledSetting(
                ComponentName(this, enable),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                ComponentName(this, disable),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}