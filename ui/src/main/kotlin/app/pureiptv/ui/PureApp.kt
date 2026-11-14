package app.pureiptv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.pureiptv.core.data.library.UserRepository
import app.pureiptv.core.data.source.SourceManager
import app.pureiptv.ui.components.KeepLeftToRight
import app.pureiptv.ui.components.ProvideAppLanguage
import app.pureiptv.ui.components.resolveLanguage
import app.pureiptv.ui.components.uiLocale
import app.pureiptv.ui.home.HomeScreen
import app.pureiptv.ui.library.AboutScreen
import app.pureiptv.ui.player.PlayRequest
import app.pureiptv.ui.player.PlayerScreen
import app.pureiptv.ui.vod.MovieDetailsScreen
import app.pureiptv.ui.vod.SeriesDetailsScreen
import app.pureiptv.ui.onboarding.AddMode
import app.pureiptv.ui.onboarding.AddSourceScreen
import app.pureiptv.ui.onboarding.ImportScreen
import app.pureiptv.ui.onboarding.PhoneSetupScreen
import app.pureiptv.ui.components.isTv
import app.pureiptv.ui.onboarding.WelcomeScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/** Navigation destinations. Plain values; the back stack is ordinary state. */
sealed interface Route {
    data object Welcome : Route
    data class AddSource(val mode: AddMode) : Route
    data object PhoneSetup : Route
    data object About : Route
    data class Import(val sourceId: Long) : Route
    data object Home : Route
    data class Play(val request: PlayRequest) : Route
    data class Movie(val movieId: Long) : Route
    data class Series(val seriesId: Long) : Route
}

@HiltViewModel
class AppViewModel @Inject constructor(sources: SourceManager, user: UserRepository) : ViewModel() {
    /** Where the app opens: the library when a source exists, else the welcome screen. */
    val startRoute = flow { emit(if (sources.hasSources()) Route.Home else Route.Welcome) }

    /** The interface language; a change applies at once. */
    val language = user.uiLanguage.map(::resolveLanguage).onEach { uiLocale = it.locale }
}

@Composable
fun PureApp(viewModel: AppViewModel = hiltViewModel()) {
    val start by viewModel.startRoute.collectAsStateWithLifecycle(null)
    val language by viewModel.language.collectAsStateWithLifecycle(null)
    // The splash screen covers the few ms until these are known (no flash of the wrong language).
    val route = start ?: return
    ProvideAppLanguage(language ?: return) { AppNavigation(route) }
}

@Composable
private fun AppNavigation(start: Route) {
    val tv = isTv
    val backStack = remember { mutableStateListOf(start) }
    fun replaceAll(route: Route) {
        backStack.clear()
        backStack.add(route)
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    NavDisplay(
        backStack = backStack,
        // Back always does the obvious thing: pop one level; at the root the system exits the app.
        onBack = { pop() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<Route.Welcome> {
                WelcomeScreen(onAdd = { backStack.add(Route.AddSource(it)) }, onPhone = { backStack.add(Route.PhoneSetup) })
            }
            entry<Route.PhoneSetup> {
                PhoneSetupScreen(
                    onAdded = { id ->
                        replaceAll(Route.Home)
                        backStack.add(Route.Import(id))
                    },
                    onTypeInstead = {
                        pop()
                        backStack.add(Route.AddSource(AddMode.Link))
                    },
                )
            }
            entry<Route.AddSource> { key ->
                AddSourceScreen(
                    initialMode = key.mode,
                    // Replace the form with the progress screen: Back from progress goes to the library.
                    onAdded = { id ->
                        replaceAll(Route.Home)
                        backStack.add(Route.Import(id))
                    },
                    onCancel = ::pop,
                )
            }
            entry<Route.Import> { key -> ImportScreen(key.sourceId, onDone = { replaceAll(Route.Home) }) }
            entry<Route.Home> {
                HomeScreen(
                    onPlay = { backStack.add(Route.Play(it)) },
                    onOpenMovie = { backStack.add(Route.Movie(it)) },
                    onOpenSeries = { backStack.add(Route.Series(it)) },
                    onAddSource = { backStack.add(if (tv) Route.PhoneSetup else Route.AddSource(AddMode.Link)) },
                    onAbout = { backStack.add(Route.About) },
                )
            }
            entry<Route.About> { AboutScreen() }
            entry<Route.Play> { key -> KeepLeftToRight { PlayerScreen(key.request, onExit = ::pop) } }
            entry<Route.Movie> { key -> MovieDetailsScreen(key.movieId, onPlay = { backStack.add(Route.Play(it)) }, onBack = ::pop) }
            entry<Route.Series> { key -> SeriesDetailsScreen(key.seriesId, onPlay = { backStack.add(Route.Play(it)) }, onBack = ::pop) }
        },
    )
}
