package com.bdavidgm.sharedmusic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import com.bdavidgm.sharedmusic.ui.SessionViewModel
import com.bdavidgm.sharedmusic.ui.session.SessionScreen
import com.bdavidgm.sharedmusic.ui.setup.SetupScreen
import com.bdavidgm.sharedmusic.ui.theme.SharedMusicTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        enableEdgeToEdge()
        setContent {
            SharedMusicTheme {
                AppNavigation(modifier = Modifier.fillMaxSize())
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

private object Routes {
    const val SETUP = "setup"
    const val SESSION = "session"
}

@Composable
private fun AppNavigation(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val viewModel: SessionViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = Routes.SETUP, modifier = modifier) {
        composable(Routes.SETUP) {
            SetupScreen(
                onStartServer = viewModel::startServer,
                onStartClient = viewModel::startClient,
                onStartRepeater = viewModel::startRepeater,
                onStarted = { navController.navigate(Routes.SESSION) }
            )
        }
        composable(Routes.SESSION) {
            SessionScreen(
                state = state,
                onStopSession = {
                    viewModel.stopSession()
                    navController.popBackStack(Routes.SETUP, inclusive = false)
                },
                onAddPlaylistItem = { uri -> viewModel.addPlaylistItems(listOf(uri)) },
                onAddPlaylistFolder = viewModel::addPlaylistFromTree,
                onTogglePlaylistTransport = viewModel::togglePlaylistTransport,
                onRemovePlaylistItem = viewModel::removePlaylistItemAt
            )
        }
    }
}
