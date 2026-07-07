package com.magnusencoded.setlistcompanion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.magnusencoded.setlistcompanion.ui.AttendedScreen
import com.magnusencoded.setlistcompanion.ui.ConnectScreen
import com.magnusencoded.setlistcompanion.ui.SetlistDetailScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as SetlistCompanionApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(::maybeCompleteSpotifyAuth)
        setContent {
            MaterialTheme {
                AppNavHost(container)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(::maybeCompleteSpotifyAuth)
    }

    private fun maybeCompleteSpotifyAuth(uri: Uri) {
        if (uri.scheme == "setlist-companion") {
            lifecycleScope.launch { container.spotify.completeAuth(uri) }
        }
    }
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val start = if (container.setlistFm.isConfigured) "attended" else "connect"

    NavHost(navController = navController, startDestination = start) {
        composable("connect") {
            ConnectScreen(
                container = container,
                onDone = {
                    navController.navigate("attended") {
                        popUpTo("connect") { inclusive = true }
                    }
                },
            )
        }
        composable("attended") {
            AttendedScreen(
                container = container,
                onOpenSetlist = { id -> navController.navigate("setlist/$id") },
                onOpenSettings = { navController.navigate("connect") },
            )
        }
        composable(
            route = "setlist/{setlistId}",
            arguments = listOf(navArgument("setlistId") { type = NavType.StringType }),
        ) { entry ->
            SetlistDetailScreen(
                container = container,
                setlistId = entry.arguments?.getString("setlistId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate("connect") },
            )
        }
    }
}
