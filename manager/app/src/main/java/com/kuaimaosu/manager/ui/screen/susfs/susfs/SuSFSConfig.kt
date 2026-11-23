package com.kuaimaosu.manager.ui.screen.susfs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.kuaimaosu.manager.BuildConfig
import com.kuaimaosu.manager.R
import com.kuaimaosu.manager.ui.component.HorizontalPagerWithInteraction
import com.kuaimaosu.manager.ui.component.SwipeableSnackbarHost
import com.kuaimaosu.manager.ui.navigation.LocalNavigator
import com.kuaimaosu.manager.ui.screen.susfs.subpages.OpenRedirectTab
import com.kuaimaosu.manager.ui.screen.susfs.subpages.StandardFeaturesTab
import com.kuaimaosu.manager.ui.screen.susfs.subpages.StatusTab
import com.kuaimaosu.manager.ui.screen.susfs.subpages.SusKstatTab
import com.kuaimaosu.manager.ui.screen.susfs.subpages.SusMapTab
import com.kuaimaosu.manager.ui.screen.susfs.component.SuSFSHeader
import com.kuaimaosu.manager.ui.screen.susfs.subpages.SusPathTab
import com.kuaimaosu.manager.ui.theme.blurEffect
import com.kuaimaosu.manager.ui.theme.blurSource
import com.kuaimaosu.manager.ui.util.ActivityResumeEffect
import com.kuaimaosu.manager.ui.util.LocalSnackbarHost
import com.kuaimaosu.manager.ui.util.adaptiveScaffoldWindowInsets
import com.kuaimaosu.manager.ui.util.showReplacingSnackbar
import com.kuaimaosu.manager.ui.viewmodel.SuSFSUiAction
import com.kuaimaosu.manager.ui.viewmodel.SuSFSUiEvent
import com.kuaimaosu.manager.ui.viewmodel.SuSFSViewModel
import com.kuaimaosu.manager.ui.viewmodel.awaitSuSFSBoolean
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** 顶栏固定，不需要折叠；给子页一个空连接，避免抢走列表的滚动。 */
private val NoopNestedScroll = object : NestedScrollConnection {}

private class SuSFSConfigSubpage(
    val requirePersist: Boolean,
    val title: String,
    val content: @Composable (PaddingValues, NestedScrollConnection) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SuSFSConfigScreen() {
    val configHelper = koinViewModel<SuSFSViewModel>()
    val navigator = LocalNavigator.current
    val snackBarHost = LocalSnackbarHost.current
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    var configEnabled by remember { mutableStateOf<Boolean?>(null) }
    val operationFailedMsg = stringResource(R.string.susfs_operation_failed)
    val pullRefreshState = rememberPullToRefreshState()
    val refreshCoordinator = remember(coroutineScope, configHelper) {
        SuSFSRefreshCoordinator(coroutineScope, configHelper)
    }
    val onRegisterRefresh: SuSFSRefreshRegistrar = remember(refreshCoordinator) {
        refreshCoordinator::register
    }

    LaunchedEffect(configHelper) {
        configHelper.events.collectLatest { event ->
            when (event) {
                is SuSFSUiEvent.Error -> if (event.message.isNotBlank()) {
                    snackBarHost.showReplacingSnackbar(event.message)
                }
            }
        }
    }

    fun requestRefresh() {
        if (isRefreshing) return
        isRefreshing = true
        coroutineScope.launch {
            try {
                refreshCoordinator.refresh(forceRefresh = true) { config ->
                    configEnabled = config.enabled
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    val handleConfigEnabledChange: (Boolean) -> Unit = { newValue ->
        coroutineScope.launch {
            if (awaitSuSFSBoolean(configHelper) { reply ->
                    SuSFSUiAction.SetEnabled(newValue, reply)
                }
            ) {
                configEnabled = newValue
            } else {
                snackBarHost.showReplacingSnackbar(operationFailedMsg)
            }
        }
    }

    val subpages = listOf(
        SuSFSConfigSubpage(
            requirePersist = false,
            title = stringResource(R.string.susfs_tab_status),
        ) { innerPadding, nestedScrollConnection ->
            StatusTab(
                nestedScrollConnection = nestedScrollConnection,
                innerPadding = innerPadding,
                onRegisterRefresh = onRegisterRefresh,
                configEnabled = configEnabled ?: false,
                configEnabledLoaded = configEnabled != null,
                onConfigEnabledChange = handleConfigEnabledChange,
                onConfigRestored = ::requestRefresh,
            )
        },
        SuSFSConfigSubpage(
            requirePersist = true,
            title = stringResource(R.string.susfs_tab_standard),
        ) { innerPadding, nestedScrollConnection ->
            StandardFeaturesTab(
                nestedScrollConnection = nestedScrollConnection,
                innerPadding = innerPadding,
                onRegisterRefresh = onRegisterRefresh,
            )
        },
        SuSFSConfigSubpage(
            requirePersist = true,
            title = stringResource(R.string.susfs_tab_sus_path),
        ) { innerPadding, nestedScrollConnection ->
            SusPathTab(
                nestedScrollConnection = nestedScrollConnection,
                innerPadding = innerPadding,
                onRegisterRefresh = onRegisterRefresh,
            )
        },
        SuSFSConfigSubpage(
            requirePersist = true,
            title = stringResource(R.string.susfs_tab_sus_kstat),
        ) { innerPadding, nestedScrollConnection ->
            SusKstatTab(
                nestedScrollConnection = nestedScrollConnection,
                innerPadding = innerPadding,
                onRegisterRefresh = onRegisterRefresh,
            )
        },
        SuSFSConfigSubpage(
            requirePersist = true,
            title = stringResource(R.string.susfs_tab_open_redirect),
        ) { innerPadding, nestedScrollConnection ->
            OpenRedirectTab(
                nestedScrollConnection = nestedScrollConnection,
                innerPadding = innerPadding,
                onRegisterRefresh = onRegisterRefresh,
            )
        },
        SuSFSConfigSubpage(
            requirePersist = true,
            title = stringResource(R.string.susfs_tab_sus_map),
        ) { innerPadding, nestedScrollConnection ->
            SusMapTab(
                nestedScrollConnection = nestedScrollConnection,
                innerPadding = innerPadding,
                onRegisterRefresh = onRegisterRefresh,
            )
        },
    )
    val defaultPage = subpages.indexOfFirst { !it.requirePersist }.coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = defaultPage,
        pageCount = { subpages.size },
    )
    val visibleSubpages = subpages.withIndex().filter { (_, subpage) ->
        !subpage.requirePersist || configEnabled == true
    }
    val selectedTabIndex = visibleSubpages
        .indexOfFirst { it.index == pagerState.currentPage }
        .coerceAtLeast(0)

    ActivityResumeEffect(refreshCoordinator) {
        refreshCoordinator.refresh(forceRefresh = true) { config ->
            configEnabled = config.enabled
        }
    }

    LaunchedEffect(configEnabled, defaultPage) {
        if (configEnabled == false && subpages[pagerState.currentPage].requirePersist) {
            pagerState.scrollToPage(defaultPage)
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .blurEffect(),
            ) {
                SuSFSHeader(
                    title = stringResource(R.string.susfs_config_title),
                    subtitle = stringResource(R.string.susfs_header_subtitle) +
                        " · v${BuildConfig.VERSION_NAME}",
                    tabs = visibleSubpages.map { it.value.title },
                    selectedTab = selectedTabIndex,
                    status = configEnabled,
                    onSelectTab = { index ->
                        val page = visibleSubpages.getOrNull(index)?.index ?: return@SuSFSHeader
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(page)
                        }
                    },
                    onBack = { navigator.pop() },
                    statusOnLabel = stringResource(R.string.susfs_state_on),
                    statusOffLabel = stringResource(R.string.susfs_state_off),
                    statusLoadingLabel = stringResource(R.string.susfs_state_loading),
                )

                BackHandler(
                    enabled = pagerState.currentPage != defaultPage,
                ) {
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(defaultPage)
                    }
                }
            }
        },
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = adaptiveScaffoldWindowInsets(),
        snackbarHost = { SwipeableSnackbarHost(hostState = snackBarHost) },
    ) { innerPadding ->
        PullToRefreshBox(
            state = pullRefreshState,
            isRefreshing = isRefreshing,
            onRefresh = ::requestRefresh,
            modifier = Modifier
                .fillMaxSize()
                .blurSource(),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    modifier = Modifier
                        .padding(top = innerPadding.calculateTopPadding())
                        .align(Alignment.TopCenter),
                    state = pullRefreshState,
                    isRefreshing = isRefreshing,
                )
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                HorizontalPagerWithInteraction(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = configEnabled == true,
                ) { page ->
                    subpages[page].content(innerPadding, NoopNestedScroll)
                }
            }
        }
    }
}
